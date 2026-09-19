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
                case "list" -> list(theme);
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
    Map<String,Object> list(String theme) throws Exception {
        var schemas = new ArrayList<Map<String,Object>>();
        for (var s : config.list(theme)) {
            var entry = new TreeMap<String,Object>(s.identity());
            entry.put("baseName", s.baseName());
            entry.put("version", s.version() == null ? "UNVERSIONED" : s.version());
            entry.put("knownVersions", knownVersions(s));
            schemas.add(entry);
        }
        return Map.of("status", "OK", "schemas", schemas);
    }
    List<Map<String,Object>> knownVersions(Configuration.Spec s) throws Exception {
        Path root = config.workspace.resolve(".netl/state");
        validateLocalPath(root);
        var found = new ArrayList<Map<String,Object>>();
        if (!Files.isDirectory(root)) return found;
        try (var paths = Files.list(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                validateLocalPath(path);
                var record = Json.object(Files.readAllBytes(path));
                if (!(record.get("target") instanceof Map<?,?> target)) continue;
                if (s.theme().equals(target.get("theme")) && s.ident().equals(target.get("ident")) && s.database().equals(target.get("database"))) {
                    var entry = new TreeMap<String,Object>();
                    entry.put("target",target); entry.put("recordedStatus",record.get("status"));
                    if (record.get("configuration") instanceof Map<?,?> configuration) {
                        entry.put("baseName",configuration.get("baseName"));
                        entry.put("schemaVersion",configuration.get("schemaVersion"));
                    }
                    found.add(entry);
                }
            }
        }
        return found;
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
        result.put("baseName", s.baseName());
        result.put("version", s.version() == null ? "UNVERSIONED" : s.version());
        var permissions = Database.permissions(c, s.name(), s.roles());
        result.put("permissions", permissions);
        boolean exists=Database.exists(c,s.name());
        String status;
        if (!exists) status = !record.isEmpty() && !Set.of("SUCCESS","DELETED").contains(record.get("status")) ? "INCOMPLETE" : "MISSING";
        else {
            var snapshot=Database.snapshot(c,s.name());
            result.put("structure",snapshot);
            if (record.isEmpty()) status="UNMANAGED";
            else if (!"SUCCESS".equals(record.get("status"))) status="INCOMPLETE";
            else if (!s.fingerprint().equals(record.get("fingerprint")) || !Json.hash(snapshot).equals(record.get("structureHash")) || !Json.hash(permissions).equals(record.get("permissionsHash"))) status="DRIFTED";
            else status="MATCHING";
        }
        result.put("status",status);
        if (!record.isEmpty() && !record.containsKey("managedRoles")) result.put("roleManagement", "LEGACY_UNVERIFIED");
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
            if ("MISSING".equals(inspection.get("status"))) {
                try (var c = runtime.connect(s.database())) {
                    if (!Database.roleIds(c,s.roles()).isEmpty()) throw new Failure("UNMANAGED_ROLE", "Target roles already exist");
                }
            }
            result.put("inspection",inspection);
            result.put("status",Set.of("MISSING","MATCHING").contains(inspection.get("status")) ? "READY" : "BLOCKED");
            result.put("action","MATCHING".equals(inspection.get("status")) ? "NONE" : "CREATE_IF_MISSING");
        } catch (Exception e) { result.put("status","BLOCKED"); result.put("problem",error(e)); }
        return result;
    }
    void requireManaged(Configuration.Spec s) throws Exception {
        var record = state(s);
        if ((record.containsKey("workspace") && !config.workspace.toString().equals(record.get("workspace"))) ||
            !s.identity().equals(record.get("target")))
            throw new Failure("UNMANAGED", "No run assigned to this theme, identifier and target");
        Object logPath = record.get("logPath");
        if (!(logPath instanceof String)) throw new Failure("UNMANAGED", "Missing run evidence");
        Path log = Path.of((String) logPath);
        Path runs = config.workspace.resolve(".netl/runs").toRealPath();
        Path run = log.getParent().toRealPath();
        if (!run.startsWith(runs) || !runs.startsWith(config.workspace) ||
            !Json.object(Files.readAllBytes(run.resolve("result.json"))).equals(record))
            throw new Failure("UNMANAGED", "Run evidence does not belong to this workspace");
    }
    Map<String,Object> recreatePlan(Configuration.Spec s) throws Exception {
        var result = new TreeMap<String,Object>();
        result.put("schema", s.identity());
        result.put("configuration", s.effective());
        result.put("fingerprint", s.fingerprint());
        result.put("action", "DROP_AND_RECREATE");
        result.put("warning", "Deletes all data in the target schema. Import failure does not restore the old schema.");
        try {
            runtime.runnerReady();
            Map<String,Object> inspection;
            // Dry-run the guarded DROP in a transaction which is always rolled back.
            try (var runnerLock = runtime.lock(); var c = runtime.connect(s.database())) {
                if (!Database.lock(c, s.name())) throw new Failure("BUSY", "Schema operation in progress");
                requireManaged(s);
                inspection = inspect(s, c);
                Database.guardedDrop(c, s.name(), managedRoles(s,c), false);
            }
            Path tokens = config.workspace.resolve(".netl/plans");
            validateLocalPath(tokens);
            Files.createDirectories(tokens);
            String token = UUID.randomUUID().toString();
            Json.write(tokens.resolve(token + ".json"), Map.of("workspace", config.workspace.toString(),
                "operation", "recreate", "fingerprint", s.fingerprint(), "target", s.identity(),
                "inspectionHash", Json.hash(inspection)));
            result.put("inspection", inspection);
            result.put("planToken", token);
            result.put("status", "READY");
        } catch (Exception e) {
            result.put("status", "BLOCKED"); result.put("problem", error(e));
        }
        return result;
    }
    public Map<String,Object> plan(String theme, String schema, String operation) {
        try {
            var spec = config.get(theme, schema);
            if (operation == null || operation.equals("create")) return plan(spec);
            if (operation.equals("recreate")) return recreatePlan(spec);
            if (operation.equals("drop-previous")) return dropPreviousPlan(spec);
            throw new Failure("INVALID_OPERATION", "operation must be create, recreate or drop-previous");
        } catch (Exception e) { return error(e); }
    }
    public Map<String,Object> recreate(String theme, String schema, String token) {
        try { return execute(config.get(theme, schema), token, true); }
        catch (Exception e) { return error(e); }
    }
    Map<String,Object> create(Configuration.Spec s) throws Exception {
        return execute(s, null, false);
    }
    Map<String,Object> execute(Configuration.Spec s, String token, boolean recreate) throws Exception {
        runtime.runnerReady();
        try (var runnerLock = runtime.lock()) { return executeLocked(s, token, recreate); }
    }
    // Caller holds the container lock for the complete isolated job lifecycle.
    Map<String,Object> executeLocked(Configuration.Spec s, String token, boolean recreate) throws Exception {
        try (var c=runtime.connect(s.database())) {
            if (!Database.lock(c,s.name())) throw new Failure("BUSY","Another creation is in progress for this schema");
            var before=inspect(s,c);
            if (recreate) {
                if (!s.fingerprint().equals(config.get(s.theme(), s.ident()).fingerprint()))
                    throw new Failure("STALE_PLAN", "Configuration changed while acquiring the schema lock");
                requireManaged(s);
                if (token == null || !token.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"))
                    throw new Failure("INVALID_PLAN", "A recreate plan token is required");
                Path tokenPath = config.workspace.resolve(".netl/plans/" + token + ".json");
                validateLocalPath(tokenPath);
                if (!Files.isRegularFile(tokenPath)) throw new Failure("INVALID_PLAN", "Unknown or consumed plan token");
                var planned = Json.object(Files.readAllBytes(tokenPath));
                if (!config.workspace.toString().equals(planned.get("workspace")) ||
                    !"recreate".equals(planned.get("operation")) ||
                    !s.identity().equals(planned.get("target")) ||
                    !s.fingerprint().equals(planned.get("fingerprint")) ||
                    !Json.hash(before).equals(planned.get("inspectionHash")))
                    throw new Failure("STALE_PLAN", "Inputs or recorded state changed; plan again");
                Database.guardedDrop(c, s.name(), managedRoles(s,c), false);
                Files.delete(tokenPath);
            }
            if (!recreate && "MATCHING".equals(before.get("status"))) return Map.of("status","ALREADY_PRESENT","inspection",before);
            if (!recreate && !"MISSING".equals(before.get("status"))) return Map.of("status","BLOCKED","inspection",before);
            if (!recreate && !Database.roleIds(c,s.roles()).isEmpty()) throw new Failure("UNMANAGED_ROLE", "Target roles already exist");
            Path root=config.workspace.resolve(".netl");
            validateLocalPath(root.resolve("runs")); validateLocalPath(root.resolve("state"));
            Files.createDirectories(root.resolve("runs")); Files.createDirectories(root.resolve("state"));
            Path run=Files.createTempDirectory(root.resolve("runs"),s.database()+"-"+s.name()+"-");
            Path log=Files.createFile(run.resolve("runner.log"));
            Path models=Files.createDirectory(run.resolve("models"));
            for (var file:s.files().entrySet()) Files.write(models.resolve(file.getKey()),file.getValue());
            Path sql = Files.createDirectory(run.resolve("sql"));
            for (var file : s.sqlFiles().entrySet()) Files.write(sql.resolve(file.getKey() + ".sql"), file.getValue());
            Json.write(run.resolve("input.json"),s.effective());
            var record=new TreeMap<String,Object>();
            record.put("managedRoles", Map.of());
            if (recreate) record.put("managedRoles", managedRoles(s,c));
            record.put("operation", recreate ? "recreate" : "create");
            record.put("workspace", config.workspace.toString());
            if (recreate) Json.write(run.resolve("before.json"), before);
            record.put("status","RUNNING"); record.put("startedAt",Instant.now().toString());
            record.put("fingerprint",s.fingerprint()); record.put("configuration",s.effective());
            record.put("logPath",log.toString()); record.put("target",s.identity());
            Json.write(run.resolve("result.json"),record); Json.write(statePath(s),record);
            try {
                if (recreate) {
                    record.put("phase", "DROPPING");
                    Json.write(run.resolve("result.json"), record); Json.write(statePath(s), record);
                    Database.guardedDrop(c, s.name(), managedRoles(s,c), true);
                    record.put("phase", "IMPORTING");
                    Json.write(run.resolve("result.json"), record); Json.write(statePath(s), record);
                }
                runtime.execute(run,log,timeout);
                captureRoles(run,record);
                if (!Json.hash(record.getOrDefault("managedRoles", Map.of())).equals(Json.hash(Database.roleIds(c,s.roles()))))
                    throw new Failure("UNMANAGED_ROLE", "SQL changed the managed roles");
                if (!Database.exists(c,s.name())) throw new Failure("VERIFICATION_FAILED","GRETL succeeded but schema is absent");
                var snapshot=Database.snapshot(c,s.name());
                if (((List<?>)snapshot.get("tables")).isEmpty()) throw new Failure("VERIFICATION_FAILED","Schema has no tables");
                record.put("status","SUCCESS"); record.put("structureHash",Json.hash(snapshot));
                var permissions = Database.permissions(c,s.name(),s.roles());
                record.put("permissionsHash", Json.hash(permissions));
                Json.write(run.resolve("permissions.json"), permissions);
                record.put("finishedAt",Instant.now().toString());
                Json.write(run.resolve("structure.json"),snapshot);
                Json.write(run.resolve("result.json"),record); Json.write(statePath(s),record);
                return Map.of("status",recreate ? "RECREATED" : "CREATED","inspection",inspect(s,c));
            } catch (Exception e) {
                captureRoles(run,record);
                record.put("status","FAILED"); record.put("finishedAt",Instant.now().toString()); record.put("error",error(e));
                Json.write(run.resolve("result.json"),record); Json.write(statePath(s),record);
                var result=new TreeMap<String,Object>(error(e)); result.put("logPath",log.toString());
                result.put("recovery","Inspect the dedicated lab environment. No automatic retry or repair was performed.");
                return result;
            }
        }
    }
    void captureRoles(Path run, Map<String,Object> record) throws Exception {
        if (Files.exists(run.resolve("roles.json"))) record.put("managedRoles", Json.object(Files.readAllBytes(run.resolve("roles.json"))));
        if (Files.exists(run.resolve("runtime.json"))) record.put("runtime", Json.object(Files.readAllBytes(run.resolve("runtime.json"))));
    }
    @SuppressWarnings("unchecked")
    Map<String,Object> managedRoles(Configuration.Spec s, Connection c) throws Exception {
        requireManaged(s);
        var record = state(s);
        Map<String,Object> recorded = (Map<String,Object>) record.getOrDefault("managedRoles", Map.of());
        if (!s.roles().containsAll(recorded.keySet())) throw new Failure("UNMANAGED_ROLE", "Recorded roles differ from configured roles");
        for (var role : Database.roleIds(c,s.roles()).entrySet()) {
            if (!role.getValue().toString().equals(String.valueOf(recorded.get(role.getKey()))))
                throw new Failure("UNMANAGED_ROLE", "Role lacks matching creation evidence: " + role.getKey());
        }
        return recorded;
    }
    @SuppressWarnings("unchecked")
    Configuration.Spec previous(Configuration.Spec current) throws Exception {
        String name = current.previousName();
        Path path = config.workspace.resolve(".netl/state/" + current.database() + "-" + name + ".json");
        validateLocalPath(path);
        if (!Files.isRegularFile(path)) throw new Failure("UNMANAGED", "No run evidence for previous version");
        var record = Json.object(Files.readAllBytes(path));
        var effective = (Map<String,Object>)record.get("configuration");
        if (effective == null || !current.baseName().equals(effective.get("baseName")) || !Integer.valueOf(current.version()-1).equals(effective.get("schemaVersion")))
            throw new Failure("UNMANAGED", "Previous run does not belong to this version family");
        var s = new Configuration.Spec(current.theme(),current.ident(),name,current.database(),effective,new TreeMap<>(),new TreeMap<>(),(String)record.get("fingerprint"));
        requireManaged(s);
        if ("DELETED".equals(record.get("status"))) throw new Failure("ALREADY_DELETED", "Previous version already deleted");
        return s;
    }
    Map<String,Object> dropPreviousPlan(Configuration.Spec current) throws Exception {
        var result = new TreeMap<String,Object>();
        result.put("action", "DROP_PREVIOUS_VERSION");
        try (var held = runtime.lock()) {
            runtime.runnerReady();
            var s = previous(current);
            try (var c = runtime.connect(s.database())) {
                if (!Database.lock(c,s.name())) throw new Failure("BUSY", "Previous schema in use");
                var inspection = inspect(s,c);
                Database.guardedDrop(c,s.name(),managedRoles(s,c),false);
                Path tokens = config.workspace.resolve(".netl/plans");
                validateLocalPath(tokens); Files.createDirectories(tokens);
                String token = UUID.randomUUID().toString();
                Json.write(tokens.resolve(token + ".json"),Map.of("operation","drop-previous","workspace",config.workspace.toString(),"fingerprint",current.fingerprint(),"target",s.identity(),"inspectionHash",Json.hash(inspection)));
                result.put("status","READY"); result.put("planToken",token); result.put("inspection",inspection); result.put("schema",s.identity());
            }
        } catch (Exception e) { result.put("status","BLOCKED"); result.put("problem",error(e)); }
        return result;
    }
    public Map<String,Object> dropPrevious(String theme, String ident, String token) {
        try (var held = runtime.lock()) {
            runtime.runnerReady();
            var current = config.get(theme,ident);
            if (token == null || !token.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")) throw new Failure("INVALID_PLAN","Invalid token");
            Path path = config.workspace.resolve(".netl/plans/" + token + ".json");
            validateLocalPath(path);
            if (!Files.isRegularFile(path)) throw new Failure("INVALID_PLAN","Unknown or consumed token");
            var planned = Json.object(Files.readAllBytes(path));
            var s = previous(current);
            try (var c = runtime.connect(s.database())) {
                if (!Database.lock(c,s.name())) throw new Failure("BUSY","Previous schema in use");
                var before = inspect(s,c);
                if (!"drop-previous".equals(planned.get("operation")) || !config.workspace.toString().equals(planned.get("workspace")) || !current.fingerprint().equals(planned.get("fingerprint")) || !s.identity().equals(planned.get("target")) || !Json.hash(before).equals(planned.get("inspectionHash"))) throw new Failure("STALE_PLAN","Inputs or previous version changed");
                var roles = managedRoles(s,c);
                Database.guardedDrop(c,s.name(),roles,false);
                Files.delete(path);
                Path run = Files.createTempDirectory(config.workspace.resolve(".netl/runs"),"drop-previous-");
                Path log = Files.createFile(run.resolve("runner.log"));
                Json.write(run.resolve("before.json"),before);
                var record = new TreeMap<String,Object>(state(s));
                record.put("operation","drop-previous"); record.put("logPath",log.toString()); record.put("status","RUNNING");
                record.put("startedAt",Instant.now().toString());
                Json.write(run.resolve("result.json"),record); Json.write(statePath(s),record);
                try { Database.guardedDrop(c,s.name(),roles,true); record.put("status","DELETED"); }
                catch (Exception e) { record.put("status","FAILED"); record.put("error",error(e)); }
                record.put("finishedAt",Instant.now().toString());
                Json.write(run.resolve("result.json"),record); Json.write(statePath(s),record);
                return Map.of("status", "DELETED".equals(record.get("status")) ? "DELETED" : "ERROR", "schema",s.identity(),"result",record);
            }
        } catch (Exception e) { return error(e); }
    }
}
