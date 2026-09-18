package ch.so.agi.netl;

import java.nio.file.*;
import java.sql.Connection;
import java.time.*;
import java.util.*;

public final class SchemaService {
    final Configuration config;
    final LocalRuntime runtime;
    final Duration timeout;
    public SchemaService(Path workspace) throws Exception {
        this(workspace, Duration.ofSeconds(120));
    }
    SchemaService(Path workspace, Duration timeout) throws Exception {
        this.timeout = timeout;
        config = new Configuration(workspace);
        runtime = new LocalRuntime(config.workspace);
    }
    SchemaService(Configuration config, LocalRuntime runtime) {
        this.config = config; this.runtime = runtime; this.timeout = Duration.ofSeconds(120);
    }
    public Map<String,Object> call(String operation, String theme, String schema) {
        try {
            return switch (operation) {
                case "list" -> Map.of("status","OK","schemas",config.list(theme).stream().map(Configuration.Spec::identity).toList());
                case "plan" -> plan(config.get(theme,schema));
                case "inspect" -> inspect(config.get(theme,schema));
                case "create" -> create(config.get(theme,schema));
                default -> throw new Failure("INVALID_OPERATION","Unknown operation " + operation);
            };
        } catch (Exception e) {
            return error(e);
        }
    }
    static Map<String,Object> error(Exception e) {
        return Map.of("status","ERROR","code",e instanceof Failure f ? f.code : "IO_ERROR",
            "message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
    Path statePath(Configuration.Spec s) throws Exception {
        Path path = config.workspace.resolve(".netl/state/" + s.database() + "-" + s.name() + ".json");
        validateLocalPath(path);
        return path;
    }
    void validateLocalPath(Path path) throws Exception {
        Path existing = path;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        Configuration.require(existing.toRealPath().startsWith(config.workspace),"State path outside workspace");
        Configuration.require(!Files.isSymbolicLink(existing),"State path must not be a symlink");
    }
    Map<String,Object> state(Configuration.Spec s) throws Exception {
        Path p=statePath(s);
        return Files.exists(p) ? Json.object(Files.readAllBytes(p)) : Map.of();
    }
    Map<String,Object> inspect(Configuration.Spec s) throws Exception {
        try (var c=runtime.connect(s.database())) { return inspect(s,c); }
    }
    Map<String,Object> inspect(Configuration.Spec s, Connection c) throws Exception {
        var record=state(s);
        var result=new TreeMap<String,Object>();
        result.put("schema",s.identity());
        result.put("fingerprint",s.fingerprint());
        boolean exists=Database.exists(c,s.name());
        String status;
        if (!exists) status = !record.isEmpty() && !"SUCCESS".equals(record.get("status")) ? "INCOMPLETE" : "MISSING";
        else {
            var snapshot=Database.snapshot(c,s.name());
            result.put("structure",snapshot);
            if (record.isEmpty()) status="UNMANAGED";
            else if (!"SUCCESS".equals(record.get("status"))) status="INCOMPLETE";
            else if (!s.fingerprint().equals(record.get("fingerprint")) || !Json.hash(snapshot).equals(record.get("structureHash"))) status="DRIFTED";
            else status="MATCHING";
        }
        result.put("status",status);
        if (!record.isEmpty()) result.put("lastRun",record);
        return result;
    }
    Map<String,Object> plan(Configuration.Spec s) throws Exception {
        var result=new TreeMap<String,Object>();
        result.put("schema",s.identity()); result.put("configuration",s.effective()); result.put("fingerprint",s.fingerprint());
        result.put("inheritedDefaults","All options not listed under options use GRETL 3.2.861 / ili2pg 5.5.1 defaults.");
        result.put("tasks",List.of("createSchema"));
        try {
            runtime.runnerReady();
            var inspection=inspect(s);
            result.put("inspection",inspection);
            result.put("status",Set.of("MISSING","MATCHING").contains(inspection.get("status")) ? "READY" : "BLOCKED");
            result.put("action","MATCHING".equals(inspection.get("status")) ? "NONE" : "CREATE_IF_MISSING");
        } catch (Exception e) { result.put("status","BLOCKED"); result.put("problem",error(e)); }
        return result;
    }
    Map<String,Object> create(Configuration.Spec s) throws Exception {
        runtime.runnerReady();
        try (var c=runtime.connect(s.database())) {
            if (!Database.lock(c,s.name())) throw new Failure("BUSY","Another creation is in progress for this schema");
            var before=inspect(s,c);
            if ("MATCHING".equals(before.get("status"))) return Map.of("status","ALREADY_PRESENT","inspection",before);
            if (!"MISSING".equals(before.get("status"))) return Map.of("status","BLOCKED","inspection",before);
            Path root=config.workspace.resolve(".netl");
            validateLocalPath(root.resolve("runs")); validateLocalPath(root.resolve("state"));
            Files.createDirectories(root.resolve("runs")); Files.createDirectories(root.resolve("state"));
            Path run=Files.createTempDirectory(root.resolve("runs"),s.database()+"-"+s.name()+"-");
            Path log=run.resolve("runner.log");
            Path models=Files.createDirectory(run.resolve("models"));
            for (var file:s.files().entrySet()) Files.write(models.resolve(file.getKey()),file.getValue());
            Files.write(run.resolve("build.gradle"),Json.resource("runner/build.gradle"));
            Json.write(run.resolve("input.json"),s.effective());
            var record=new TreeMap<String,Object>();
            record.put("status","RUNNING"); record.put("startedAt",Instant.now().toString());
            record.put("fingerprint",s.fingerprint()); record.put("configuration",s.effective());
            record.put("logPath",log.toString()); record.put("target",s.identity());
            Json.write(run.resolve("result.json"),record); Json.write(statePath(s),record);
            try {
                runtime.execute(run,log,timeout);
                if (!Database.exists(c,s.name())) throw new Failure("VERIFICATION_FAILED","GRETL succeeded but schema is absent");
                var snapshot=Database.snapshot(c,s.name());
                if (((List<?>)snapshot.get("tables")).isEmpty()) throw new Failure("VERIFICATION_FAILED","Schema has no tables");
                record.put("status","SUCCESS"); record.put("structureHash",Json.hash(snapshot));
                record.put("finishedAt",Instant.now().toString());
                Json.write(run.resolve("structure.json"),snapshot);
                Json.write(run.resolve("result.json"),record); Json.write(statePath(s),record);
                return Map.of("status","CREATED","inspection",inspect(s,c));
            } catch (Exception e) {
                record.put("status","FAILED"); record.put("finishedAt",Instant.now().toString()); record.put("error",error(e));
                Json.write(run.resolve("result.json"),record); Json.write(statePath(s),record);
                var result=new TreeMap<String,Object>(error(e)); result.put("logPath",log.toString());
                result.put("recovery","Inspect the dedicated lab environment. No automatic retry or repair was performed.");
                return result;
            }
        }
    }
}
