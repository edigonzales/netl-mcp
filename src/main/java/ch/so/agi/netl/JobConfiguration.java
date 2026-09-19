package ch.so.agi.netl;

import java.nio.file.*;
import java.util.*;
import static ch.so.agi.netl.Configuration.*;

/** Job code and the independently confirmed test contract have separate revisions. */
final class JobConfiguration {
    final Configuration schemas;
    JobConfiguration(Configuration schemas) { this.schemas = schemas; }
    Path directory(String theme, String job) throws Exception {
        require(job != null && job.matches("[a-z][a-z0-9_-]{0,50}"), "Invalid job identifier");
        Path path = schemas.directory(theme).resolve("jobs").resolve(job);
        safe(path);
        return path;
    }
    void safe(Path path) throws Exception {
        require(path.normalize().startsWith(schemas.workspace), "Path outside workspace");
        for (Path p = path; p != null && p.startsWith(schemas.workspace); p = p.getParent())
            require(!Files.isSymbolicLink(p), "Symlinks are not supported: " + p);
    }
    static String identifier(String value) {
        require(value.matches("[a-z][a-z0-9_]{0,62}"), "Invalid SQL identifier: " + value);
        return value;
    }
    static String relative(String value) {
        require(value.matches("[a-zA-Z0-9_-]+(/[a-zA-Z0-9_-]+)*\\.(sql|json|gradle)"), "Invalid artifact path");
        return value;
    }
    byte[] read(Path dir, String file) throws Exception {
        Path path = dir.resolve(relative(file)); safe(path);
        require(Files.isRegularFile(path) && Files.size(path) <= 1_000_000, "Missing or oversized artifact: " + file);
        return Files.readAllBytes(path);
    }
    @SuppressWarnings("unchecked")
    Spec load(String theme, String job) throws Exception {
        Path dir = directory(theme, job);
        var files = new TreeMap<String,byte[]>();
        for (String file : List.of("job.json", "build.gradle", "tests.json")) files.put(file, read(dir,file));
        var manifest = Json.object(files.get("job.json"));
        keys(manifest, Set.of("formatVersion","source","target","task","sqlFiles","tables"));
        require(Integer.valueOf(1).equals(manifest.get("formatVersion")), "job formatVersion must be 1");
        var source = schemas.get(theme,string(manifest,"source"));
        var target = schemas.get(theme,string(manifest,"target"));
        require(source.database().equals("edit") && target.database().equals("pub"), "Only edit to pub jobs supported");
        String task = string(manifest,"task");
        require(task.matches("[a-zA-Z][a-zA-Z0-9_]*"), "Invalid task");
        for (String file : strings(manifest,"sqlFiles")) {
            require(file.startsWith("sql/") && file.endsWith(".sql"), "Transform SQL must be under sql/");
            files.put(file,read(dir,file));
        }
        require(manifest.get("tables") instanceof List<?> l && !l.isEmpty(), "tables required");
        var names = new HashSet<String>();
        for (Object obj : (List<?>)manifest.get("tables")) {
            require(obj instanceof Map<?,?>, "tables entries must be objects with name, key array and columns array, not table-name strings");
            var table = (Map<String,Object>)obj;
            keys(table,Set.of("name","key","columns","allowEmpty"));
            require(names.add(identifier(string(table,"name"))), "Duplicate target table");
            strings(table,"key").forEach(JobConfiguration::identifier);
            strings(table,"columns").forEach(JobConfiguration::identifier);
            require(strings(table,"columns").containsAll(strings(table,"key")), "Comparison columns must include business key");
            require(!table.containsKey("allowEmpty") || table.get("allowEmpty") instanceof Boolean,"allowEmpty must be boolean");
        }
        var tests = Json.object(files.get("tests.json"));
        keys(tests,Set.of("formatVersion","requirements","fixtures","assertions"));
        require(Integer.valueOf(1).equals(tests.get("formatVersion")),"tests formatVersion must be 1");
        string(tests,"requirements");
        for (String file : strings(tests,"fixtures")) {
            require(file.startsWith("fixtures/") && file.endsWith(".sql"),"Fixtures must be under fixtures/");
            files.put(file,read(dir,file));
        }
        require(tests.get("assertions") instanceof List<?>, "assertions must be an array");
        var ids = new HashSet<String>();
        for (Object obj : (List<?>)tests.get("assertions")) {
            require(obj instanceof Map<?,?>,"Invalid assertion");
            var a = (Map<String,Object>)obj;
            keys(a,Set.of("id","description","origin","scope","sql","expected"));
            require(ids.add(identifier(string(a,"id"))),"Duplicate assertion id");
            string(a,"description"); string(a,"origin");
            require(Set.of("fixture","local").contains(string(a,"scope")),"scope must be fixture or local (local also runs in tests)");
            require("zero_violations".equals(string(a,"expected")),"Expected zero_violations");
            String file = string(a,"sql");
            require(file.startsWith("assertions/") && file.endsWith(".sql"),"Assertions must be under assertions/");
            files.put(file,read(dir,file));
        }
        var hashes = new TreeMap<String,String>();
        for (var f : files.entrySet()) hashes.put(f.getKey(),Json.hashBytes(f.getValue()));
        var expectations = new TreeMap<String,String>();
        hashes.forEach((k,v) -> { if (k.equals("tests.json") || k.startsWith("fixtures/") || k.startsWith("assertions/")) expectations.put(k,v); });
        // Generic expectations and schema versions are also part of the confirmed contract.
        String contract = Json.hash(Map.of("tests",expectations,"manifest",manifest,"source",source.fingerprint(),"target",target.fingerprint()));
        String fingerprint = Json.hash(Map.of("files",hashes,"contract",contract,"runner",runnerHashes()));
        var transform = new TreeMap<String,String>(hashes); transform.keySet().removeAll(expectations.keySet());
        return new Spec(theme,job,dir,source,target,manifest,tests,files,hashes,contract,fingerprint,Json.hash(transform));
    }
    record Spec(String theme, String job, Path directory, Configuration.Spec source, Configuration.Spec target,
                Map<String,Object> manifest, Map<String,Object> tests, SortedMap<String,byte[]> files,
                Map<String,String> hashes, String contract, String fingerprint, String transformHash) {
        String task() { return (String)manifest.get("task"); }
        @SuppressWarnings("unchecked") List<Map<String,Object>> tables() { return (List<Map<String,Object>>)manifest.get("tables"); }
        @SuppressWarnings("unchecked") List<Map<String,Object>> assertions() { return (List<Map<String,Object>>)tests.get("assertions"); }
    }
}
