package ch.so.agi.netl;

import java.nio.file.*;
import java.nio.channels.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

class LocalRuntime {
    static final String GRETL_IMAGE = "netl/gretl:0.3.0";
    static final String POSTGIS_IMAGE = "postgis/postgis:18-3.6@sha256:60f6ad1d21ea86a67d47780b9a0d1e1d200500f62b19293fa834d0dea80b8677";
    final Path workspace;
    LocalRuntime(Path workspace) { this.workspace = workspace; }
    private Map<?,?> container(String service, String expectedImage) throws Exception {
        Path output = Files.createTempFile("netl-inspect-", ".json");
        byte[] bytes;
        try {
            var process = new ProcessBuilder("docker", "inspect", "themenintegration-lab-" + service + "-1")
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new Failure("ENVIRONMENT_UNAVAILABLE", "Docker inspection timed out");
            }
            if (process.exitValue() != 0) throw new Failure("ENVIRONMENT_UNAVAILABLE", "Local container unavailable: " + service);
            bytes = Files.readAllBytes(output);
        } finally { Files.deleteIfExists(output); }
        List<?> entries = Json.MAPPER.readValue(bytes, List.class);
        Map<?,?> info = (Map<?,?>)entries.getFirst();
        Map<?,?> config = (Map<?,?>)info.get("Config"), state = (Map<?,?>)info.get("State");
        if (!expectedImage.equals(config.get("Image")) || !Boolean.TRUE.equals(state.get("Running")))
            throw new Failure("ENVIRONMENT_MISMATCH", "Unexpected image or stopped container: " + service);
        Map<?,?> labels = (Map<?,?>)config.get("Labels");
        if (!"themenintegration-lab".equals(labels.get("com.docker.compose.project")))
            throw new Failure("ENVIRONMENT_MISMATCH", "Unexpected Compose project");
        return info;
    }
    void runnerReady() throws Exception {
        Map<?,?> info = container("gretl", GRETL_IMAGE);
        boolean mounted = false;
        for (Object item : (List<?>)info.get("Mounts")) {
            Map<?,?> mount = (Map<?,?>)item;
            if ("/workspace".equals(mount.get("Destination")) &&
                Path.of((String)mount.get("Source")).toRealPath().equals(workspace.resolve(".netl").toRealPath())) mounted = true;
        }
        if (!mounted) throw new Failure("ENVIRONMENT_MISMATCH", "GRETL must mount this workspace's .netl directory");
        var args = new ArrayList<>(List.of("docker","exec","themenintegration-lab-gretl-1","sha256sum"));
        var hashes = Configuration.runnerHashes();
        for (String file : hashes.keySet()) args.add(file.equals("netl-run") ? "/usr/local/bin/netl-run" : file.equals("init.gradle") ? "/home/gradle/init.gradle" : "/opt/netl/schema/" + file);
        String[] lines = command(args).strip().split("\n");
        int index = 0;
        for (String expected : hashes.values()) {
            if (index >= lines.length || !lines[index++].startsWith(expected + " "))
                throw new Failure("ENVIRONMENT_MISMATCH", "NETL image resources differ from this JAR; rebuild image and JAR together");
        }
    }
    Connection connect(String database) throws Exception {
        int port = database.equals("edit") ? 55431 : 55432;
        var info = container(database + "-db", POSTGIS_IMAGE);
        Map<?,?> network = (Map<?,?>)info.get("NetworkSettings");
        Map<?,?> ports = (Map<?,?>)network.get("Ports");
        Object bindings = ports.get("5432/tcp");
        boolean bound = bindings instanceof List<?> list && list.stream().anyMatch(item -> {
            Map<?,?> binding = (Map<?,?>)item;
            return "127.0.0.1".equals(binding.get("HostIp")) && Integer.toString(port).equals(binding.get("HostPort"));
        });
        if (!bound) throw new Failure("ENVIRONMENT_MISMATCH", "Unexpected local database port");
        try {
            return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + port + "/" + database + "?connectTimeout=3&socketTimeout=15", "netl", "netl-local");
        } catch (SQLException e) {
            throw new Failure("DB_UNAVAILABLE", "Cannot connect to local " + database + " database (SQLState " + e.getSQLState() + ")");
        }
    }
    void execute(Path runDirectory, Path log, Duration timeout) throws Exception {
        execute(runDirectory, log, timeout, List.of("createSchema"));
    }
    AutoCloseable lock() throws Exception {
        Path root = workspace.resolve(".netl");
        Files.createDirectories(root);
        Path path = root.resolve("runner.lock");
        if (Files.isSymbolicLink(root) || Files.isSymbolicLink(path)) throw new Failure("ENVIRONMENT_MISMATCH", "Symlinked runner lock");
        var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock held;
        try { held = channel.tryLock(); } catch (OverlappingFileLockException e) { held = null; }
        if (held == null) { channel.close(); throw new Failure("BUSY", "Another runner operation is in progress"); }
        var lock = held;
        if (Files.exists(root.resolve("runner-recovery.json")) || Files.exists(root.resolve("runner-active.json"))) {
            lock.close(); channel.close();
            throw new Failure("RUNNER_RECOVERY_REQUIRED", "Runner termination was not confirmed; inspect runner-recovery.json before manual recovery");
        }
        try { Json.write(root.resolve("runner-active.json"), Map.of("hostPid", ProcessHandle.current().pid(), "startedAt", java.time.Instant.now().toString())); }
        catch (Exception e) { lock.close(); channel.close(); throw e; }
        return () -> {
            try { Files.deleteIfExists(root.resolve("runner-active.json")); }
            finally { try { lock.close(); } finally { channel.close(); } }
        };
    }
    void execute(Path runDirectory, Path log, Duration timeout, List<String> tasks) throws Exception {
        executeProject(runDirectory, log, timeout, tasks, false);
    }
    void executeProject(Path runDirectory, Path log, Duration timeout, List<String> tasks, boolean job) throws Exception {
        runnerReady();
        Path root = workspace.resolve(".netl").toRealPath(), actual = runDirectory.toRealPath();
        if (!actual.startsWith(root)) throw new Failure("INVALID_CONFIG", "Run outside workspace");
        var args = new ArrayList<>(List.of("docker", "exec", "--user", "1001", "themenintegration-lab-gretl-1",
            "netl-run"));
        if (job) args.addAll(List.of("--job-project", "/workspace/" + root.relativize(actual)));
        args.add("-PrunDirectory=/workspace/" + root.relativize(actual));
        args.addAll(tasks);
        var pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true).redirectOutput(log.toFile());
        Process process = pb.start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                recover();
                throw new Failure("RUNNER_TIMEOUT", "GRETL exceeded " + timeout.toSeconds() + " seconds; container stopped and restarted, no retry");
            }
            if (process.exitValue() != 0) {
                recover(); // Also covers a broken docker-exec connection while Gradle is still alive.
                throw new Failure("RUNNER_FAILED", "GRETL exited with code " + process.exitValue());
            }
        } catch (InterruptedException e) {
            recover(); Thread.currentThread().interrupt(); throw e;
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    void recover() throws Exception {
        Path marker = workspace.resolve(".netl/runner-recovery.json");
        Json.write(marker, Map.of("container", "themenintegration-lab-gretl-1", "reason", "Unconfirmed termination"));
        command(List.of("docker", "stop", "--time", "5", "themenintegration-lab-gretl-1"));
        String state = command(List.of("docker", "inspect", "--format", "{{.State.Running}}", "themenintegration-lab-gretl-1")).trim();
        if (!state.equals("false")) throw new Failure("RUNNER_RECOVERY_REQUIRED", "Container stop was not confirmed");
        // Stop database work too: a disconnected client alone does not prove a long
        // server-side statement has finished. Only this runner's tagged sessions.
        for (String database : List.of("edit", "pub")) {
            try (var c = connect(database); var st = c.createStatement();
                 var rs = st.executeQuery("SELECT pg_terminate_backend(pid,5000) FROM pg_stat_activity WHERE (application_name='netl-schema-runner' OR application_name LIKE 'netl-job-%') AND datname=current_database() AND pid<>pg_backend_pid()")) {
                while (rs.next()) if (!rs.getBoolean(1)) throw new Failure("RUNNER_RECOVERY_REQUIRED", "Database job did not terminate");
            }
        }
        command(List.of("docker", "start", "themenintegration-lab-gretl-1"));
        runnerReady();
        Files.delete(marker);
    }
    static String command(List<String> args) throws Exception {
        Path output = Files.createTempFile("netl-docker-", ".log");
        try {
            var p = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new Failure("ENVIRONMENT_UNAVAILABLE", "Docker command timed out"); }
            if (p.exitValue() != 0) throw new Failure("ENVIRONMENT_UNAVAILABLE", "Docker command failed: " + Files.readString(output));
            return Files.readString(output);
        } finally { Files.deleteIfExists(output); }
    }
}
