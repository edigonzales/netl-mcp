package ch.so.agi.netl;

import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

final class ConfigService {
    final Configuration config;
    ConfigService(Path workspace) throws Exception { config = new Configuration(workspace); }

    Path manifestPath(String theme) throws Exception {
        Path path = config.directory(theme).resolve("schemas.json");
        Configuration.require(!Files.isSymbolicLink(path), "Manifest must not be a symlink");
        return path;
    }
    String revision(Path path) throws Exception {
        return Files.exists(path) ? Json.hashBytes(Files.readAllBytes(path)) : "ABSENT";
    }
    Map<String,Object> context(String theme) throws Exception {
        Path path = manifestPath(theme), directory = path.getParent();
        var result = new TreeMap<String,Object>();
        result.put("status", "OK");
        result.put("revision", revision(path));
        if (Files.exists(path)) result.put("manifest", Json.object(Files.readAllBytes(path)));
        try (var files = Files.walk(directory)) {
            var models = new ArrayList<String>();
            for (Path file : files.filter(p -> p.toString().endsWith(".ili") && Files.isRegularFile(p)).sorted().toList()) {
                Configuration.require(file.toRealPath().startsWith(directory), "Model outside theme directory");
                models.add(directory.relativize(file).toString());
            }
            result.put("modelFiles", models);
        }
        result.put("profiles", config.profiles);
        boolean legacy = Files.exists(path) && Integer.valueOf(1).equals(Json.object(Files.readAllBytes(path)).get("formatVersion"));
        result.put("manifestSchema", Json.object(Json.resource(legacy ? "schemas-v1.schema.json" : "schemas-v2.schema.json")));
        result.put("supportedFormatVersions", List.of(1,2));
        result.put("runner", Configuration.RUNNER);
        result.put("inheritedDefaults", "Options absent from the profile use the documented runner defaults.");
        result.put("modelCompilationChecked", false);
        return result;
    }
    void preserveTargets(Path path, Map<String,Object> manifest) throws Exception {
        if (!Files.exists(path)) return;
        var previous = Json.object(Files.readAllBytes(path));
        Configuration.require(previous.get("schemas") instanceof List<?>, "Invalid existing manifest");
        for (Object old : (List<?>) previous.get("schemas")) {
            Configuration.require(old instanceof Map<?,?>, "Invalid existing entry");
            Map<?,?> entry = (Map<?,?>) old;
            var next = ((List<?>) manifest.get("schemas")).stream().map(v -> (Map<?,?>) v)
                .filter(v -> Objects.equals(v.get("ident"), entry.get("ident"))).findFirst();
            Configuration.require(next.isPresent(), "Existing schema identifiers cannot be removed or renamed");
            Configuration.require(Objects.equals(entry.get("database"), next.get().get("database")), "Existing database cannot change");
            boolean oldV2 = Integer.valueOf(2).equals(previous.get("formatVersion"));
            boolean newV2 = Integer.valueOf(2).equals(manifest.get("formatVersion"));
            if (oldV2 && newV2) {
                Configuration.require(Objects.equals(entry.get("baseName"), next.get().get("baseName")), "Existing baseName cannot change");
                Configuration.require(entry.containsKey("schemaVersion") == next.get().containsKey("schemaVersion"), "Cannot switch versioning on an existing identifier");
                Configuration.require(suffix(entry).equals(suffix(next.get())), "Existing roleSuffix cannot change");
            } else {
                Configuration.require(physicalName(entry,oldV2).equals(physicalName(next.get(),newV2)) && suffix(entry).equals(suffix(next.get())), "Format conversion must preserve physical target and roles");
            }
        }
    }
    static String suffix(Map<?,?> entry) { return entry.containsKey("roleSuffix") ? String.valueOf(entry.get("roleSuffix")) : ""; }
    static String physicalName(Map<?,?> entry, boolean v2) {
        return v2 ? String.valueOf(entry.get("baseName")) + (entry.containsKey("schemaVersion") ? "_v" + entry.get("schemaVersion") : "") : String.valueOf(entry.get("name"));
    }
    Map<String,Object> validate(String theme, Map<String,Object> manifest) throws Exception {
        var specs = config.resolve(theme, manifest);
        preserveTargets(manifestPath(theme), manifest);
        return Map.of("status", "VALID", "modelCompilationChecked", false,
            "schemas", specs.stream().map(s -> Map.of("configuration", s.effective(), "fingerprint", s.fingerprint())).toList());
    }
    Map<String,Object> save(String theme, Map<String,Object> manifest, String expectedRevision) throws Exception {
        Path path = manifestPath(theme);
        Path root = config.workspace.resolve(".netl/config");
        new SchemaService(config.workspace).validateLocalPath(root);
        Files.createDirectories(root);
        Path lockPath = root.resolve(theme.replace('/', '-') + ".lock");
        Configuration.require(!Files.isSymbolicLink(lockPath), "Lock must not be a symlink");
        try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try { lock = channel.tryLock(); } catch (OverlappingFileLockException e) { lock = null; }
            if (lock == null) throw new Failure("BUSY", "Configuration is being saved");
            try (var held = lock) {
                path = manifestPath(theme);
                if (!revision(path).equals(expectedRevision))
                    throw new Failure("CONFIG_CONFLICT", "Configuration changed; read context and validate again");
                var validated = validate(theme, manifest);
                Path audit = Files.createTempDirectory(root, "change-");
                if (Files.exists(path)) Files.copy(path, audit.resolve("before.json"));
                Json.write(audit.resolve("after.json"), manifest);
                Json.write(audit.resolve("result.json"), Map.of("status", "PREPARED", "theme", theme, "previousRevision", expectedRevision));
                Json.write(path, manifest);
                String current = revision(path);
                Json.write(audit.resolve("result.json"), Map.of("status", "SAVED", "theme", theme, "revision", current, "previousRevision", expectedRevision));
                return Map.of("status", "SAVED", "revision", current, "validation", validated, "auditPath", audit.toString());
            }
        }
    }
}
