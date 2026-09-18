package ch.so.agi.netl;

import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

class LocalRuntime {
    static final String GRETL_IMAGE = "sogis/gretl:3.2.861@sha256:5e1e2b89995d11d8317bf114c7ce9d74351c1167df59eee664376b825ff7ddea";
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
        runnerReady();
        // A dedicated container makes timeout cancellation kill every Gradle/Java child,
        // including detached Gradle daemons, without affecting another schema run.
        String name = "netl-run-" + UUID.randomUUID();
        var pb = new ProcessBuilder("docker", "run", "--rm", "--name", name,
            "--network", "themenintegration-lab_default",
            "--mount", "type=bind,source=" + runDirectory + ",target=/work",
            "--workdir", "/work", "--entrypoint", "gretl", GRETL_IMAGE,
            "--no-daemon", "--console=plain", "createSchema");
        pb.redirectErrorStream(true).redirectOutput(log.toFile());
        Process process = pb.start();
        try {
            if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                throw new Failure("RUNNER_TIMEOUT", "GRETL exceeded " + timeout.toSeconds() + " seconds; run cancelled");
            }
            if (process.exitValue() != 0) throw new Failure("RUNNER_FAILED", "GRETL exited with code " + process.exitValue());
        } finally {
            Process cleanup = new ProcessBuilder("docker", "rm", "-f", name)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!cleanup.waitFor(10, TimeUnit.SECONDS)) cleanup.destroyForcibly();
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
