package ch.so.agi.netl;

import java.nio.file.*;
import java.util.*;

final class Configuration {
    static final String RUNNER = "netl-0.2.0/gretl-3.2.861/ili2pg-5.5.1/postgis-18-3.6";
    final Path workspace;
    final Map<String,Object> profiles;
    Configuration(Path workspace) throws Exception {
        this.workspace = workspace.toRealPath();
        profiles = Json.object(Json.resource("profiles.json"));
    }
    static void require(boolean ok, String message) {
        if (!ok) throw new Failure("INVALID_CONFIG", message);
    }
    static void keys(Map<?,?> value, Set<String> allowed) {
        require(allowed.containsAll(value.keySet()), "Unknown keys: " + value.keySet().stream().filter(k -> !allowed.contains(k)).toList());
    }
    static String string(Map<?,?> map, String key) {
        Object value = map.get(key);
        require(value instanceof String && !((String)value).isBlank(), "Missing or invalid " + key);
        return (String)value;
    }
    static List<String> strings(Map<?,?> map, String key) {
        Object value = map.get(key);
        require(value instanceof List<?> && !((List<?>)value).isEmpty(), "Missing or empty " + key);
        var result = new ArrayList<String>();
        for (Object item : (List<?>)value) {
            require(item instanceof String && !((String)item).isBlank(), "Invalid " + key);
            result.add((String)item);
        }
        require(new HashSet<>(result).size() == result.size(), "Duplicate " + key);
        return List.copyOf(result);
    }
    Path inside(Path path) throws Exception {
        Path real = path.toRealPath();
        require(real.startsWith(workspace), "Path outside workspace: " + path);
        return real;
    }
    Path directory(String theme) throws Exception {
        require(theme != null && theme.matches("[a-z][a-z0-9_-]*/[a-z][a-z0-9_-]*"), "Theme must be amt/thema");
        Path directory = inside(workspace.resolve("themes").resolve(theme));
        require(directory.startsWith(workspace.resolve("themes").toRealPath()), "Theme outside themes directory");
        require(directory.equals(workspace.resolve("themes").resolve(theme)), "Symlinked theme directory");
        return directory;
    }
    List<Spec> list(String theme) throws Exception {
        Path directory = directory(theme);
        return resolve(theme, Json.object(Files.readAllBytes(inside(directory.resolve("schemas.json")))));
    }
    List<Spec> resolve(String theme, Map<String,Object> root) throws Exception {
        Path directory = directory(theme);
        require(root != null, "Manifest must be an object");
        keys(root, Set.of("formatVersion", "schemas"));
        require(root.get("formatVersion") instanceof Integer f && Set.of(1,2).contains(f), "formatVersion must be 1 or 2");
        boolean versioned = Integer.valueOf(2).equals(root.get("formatVersion"));
        require(root.get("schemas") instanceof List<?> l && !l.isEmpty(), "schemas must be an array");
        var result = new ArrayList<Spec>();
        var ids = new HashSet<String>();
        var targets = new HashSet<String>();
        for (Object entry : (List<?>)root.get("schemas")) {
            require(entry instanceof Map<?,?>, "Schema must be an object");
            Map<?,?> m = (Map<?,?>)entry;
            keys(m, versioned ? Set.of("ident","baseName","schemaVersion","database","models","modelFiles","profile","overrides","roleSuffix","schemaComment","sqlFiles") : Set.of("ident","name","database","models","modelFiles","profile","overrides"));
            String id = string(m,"ident"), base = string(m,versioned ? "baseName" : "name"), database = string(m,"database"), profile = string(m,"profile");
            Integer version = null;
            if (m.containsKey("schemaVersion")) {
                require(m.get("schemaVersion") instanceof Integer n && n > 0, "schemaVersion must be a positive integer");
                version = (Integer)m.get("schemaVersion");
            }
            String name = base + (version == null ? "" : "_v" + version);
            require(!m.containsKey("roleSuffix") || m.get("roleSuffix") instanceof String, "roleSuffix must be a string");
            String roleSuffix = m.containsKey("roleSuffix") ? (String)m.get("roleSuffix") : "";
            require(roleSuffix != null && roleSuffix.matches("(_[a-z][a-z0-9_]*)?"), "Invalid roleSuffix");
            require((name + roleSuffix + "_write").length() <= 63, "Schema role name exceeds 63 characters");
            require(id.matches("[a-z][a-z0-9_-]{0,62}"), "Invalid schema identifier");
            require(name.matches("[a-z][a-z0-9_]{0,62}") && !name.startsWith("pg_") && !Set.of("public","information_schema").contains(name), "Invalid schema name");
            require(Set.of("edit","pub").contains(database), "Only edit/pub supported");
            require(ids.add(id) && targets.add(database + "/" + name), "Duplicate schema identifier or target");
            require(profiles.containsKey(profile), "Unknown profile " + profile);
            @SuppressWarnings("unchecked") var options = new TreeMap<String,Object>((Map<String,Object>)profiles.get(profile));
            Object overrides = m.getOrDefault("overrides", null);
            if (m.containsKey("overrides")) {
                require(overrides instanceof Map<?,?>, "overrides must be an object");
                for (var o : ((Map<?,?>)overrides).entrySet()) {
                    require(options.containsKey(o.getKey()), "Unknown option " + o.getKey());
                    Object defaultValue = options.get(o.getKey());
                    require(o.getValue() != null && o.getValue().getClass() == defaultValue.getClass(), "Wrong type for " + o.getKey());
                    if (o.getKey().equals("defaultSrsCode")) require(((String)o.getValue()).matches("[0-9]{1,6}"), "Invalid SRS code");
                    options.put((String)o.getKey(), o.getValue());
                }
            }
            var models = strings(m,"models");
            for (String model : models) require(model.matches("[A-Za-z][A-Za-z0-9_]*"), "Invalid model name");
            var modelFiles = strings(m,"modelFiles");
            var files = new TreeMap<String,byte[]>();
            for (String file : modelFiles) {
                Path relative = Path.of(file);
                require(!relative.isAbsolute() && !Arrays.asList(file.split("/", -1)).contains("..") && file.endsWith(".ili"), "Invalid model path");
                Path source = inside(directory.resolve(relative));
                require(source.startsWith(directory), "Model outside theme directory");
                require(Files.isRegularFile(source) && Files.size(source) <= 10_000_000, "Invalid model file");
                String filename = source.getFileName().toString();
                require(!files.containsKey(filename), "Duplicate model basename " + filename);
                files.put(filename, Files.readAllBytes(source));
            }
            var effective = new TreeMap<String,Object>();
            effective.put("theme", theme); effective.put("ident",id); effective.put("name",name);
            effective.put("database",database); effective.put("models",models); effective.put("profile",profile);
            effective.put("formatVersion", root.get("formatVersion"));
            effective.put("baseName", base);
            if (version != null) effective.put("schemaVersion", version);
            effective.put("roleSuffix", roleSuffix);
            Object comment = m.getOrDefault("schemaComment", null);
            require(comment == null || comment instanceof String, "schemaComment must be a string");
            effective.put("schemaComment", comment == null ? "" : comment);
            var sqlFiles = new TreeMap<String,byte[]>();
            if (m.containsKey("sqlFiles")) {
                require(m.get("sqlFiles") instanceof Map<?,?>, "sqlFiles must be an object");
                Map<?,?> sql = (Map<?,?>)m.get("sqlFiles");
                keys(sql, Set.of("views", "postscript", "stdcols", "grants"));
                for (var script : sql.entrySet()) {
                    String relative = string(sql, (String)script.getKey());
                    require(!Path.of(relative).isAbsolute() && !Arrays.asList(relative.split("/", -1)).contains("..") && relative.endsWith(".sql"), "Invalid SQL path");
                    Path source = inside(directory.resolve(relative));
                    require(source.startsWith(directory) && Files.isRegularFile(source) && Files.size(source) <= 10_000_000, "Invalid SQL file");
                    sqlFiles.put((String)script.getKey(), Files.readAllBytes(source));
                }
            }
            effective.put("sqlFiles", m.containsKey("sqlFiles") ? m.get("sqlFiles") : Map.of());
            var sqlHashes = new TreeMap<String,String>();
            for (var file : sqlFiles.entrySet()) sqlHashes.put(file.getKey(), Json.hashBytes(file.getValue()));
            effective.put("sqlHashes", sqlHashes);
            effective.put("options",options); effective.put("modelFiles",modelFiles); effective.put("runner",RUNNER);
            var hashes = new TreeMap<String,String>();
            for (var file : files.entrySet()) hashes.put(file.getKey(), Json.hashBytes(file.getValue()));
            effective.put("modelHashes",hashes);
            effective.put("images", Map.of("gretl", LocalRuntime.GRETL_IMAGE, "postgis", LocalRuntime.POSTGIS_IMAGE));
            effective.put("runnerHashes", runnerHashes());
            String fingerprint = Json.hash(effective);
            result.add(new Spec(theme,id,name,database,Map.copyOf(effective),files,sqlFiles,fingerprint));
        }
        return List.copyOf(result);
    }
    Spec get(String theme, String ident) throws Exception {
        return list(theme).stream().filter(s -> s.ident().equals(ident)).findFirst()
            .orElseThrow(() -> new Failure("UNKNOWN_SCHEMA", "Unknown schema identifier: " + ident));
    }
    static Map<String,String> runnerHashes() throws Exception {
        var hashes = new TreeMap<String,String>();
        for (String file : List.of("build.gradle", "settings.gradle", "grants.sql", "netl-run"))
            hashes.put(file,Json.hashBytes(Json.resource("runner/" + file)));
        return hashes;
    }
    record Spec(String theme, String ident, String name, String database, Map<String,Object> effective,
                SortedMap<String,byte[]> files, SortedMap<String,byte[]> sqlFiles, String fingerprint) {
        String baseName() { return (String)effective.get("baseName"); }
        Integer version() { return (Integer)effective.get("schemaVersion"); }
        List<String> roles() { return List.of(name + effective.get("roleSuffix") + "_read", name + effective.get("roleSuffix") + "_write"); }
        String previousName() throws Failure {
            if (version() == null || version() <= 1) throw new Failure("NO_PREVIOUS_VERSION", "No previous positive schema version");
            return baseName() + "_v" + (version() - 1);
        }
        Map<String,Object> identity() {
            return Map.of("theme",theme,"ident",ident,"name",name,"database",database);
        }
    }
}
