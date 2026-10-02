package ch.so.agi.netl;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.*;

/** A prepared compiler closure, independent of the desired input fingerprint. */
final class ModelSnapshot {
    static void safe(Path workspace, Path path) throws Exception {
        if (!path.normalize().startsWith(workspace.resolve(".netl")))
            throw new Failure("MODEL_EVIDENCE_INVALID", "Model evidence outside .netl");
        for (Path p=path; p!=null && p.startsWith(workspace); p=p.getParent())
            if (Files.isSymbolicLink(p)) throw new Failure("MODEL_EVIDENCE_INVALID", "Symlinked model evidence");
    }
    @SuppressWarnings("unchecked")
    static Map<String,byte[]> files(Path workspace, Map<String,Object> evidence) throws Exception {
        try {
            Path directory=Path.of((String)evidence.get("modelSnapshot")); safe(workspace,directory);
            var metadata=Json.object(Files.readAllBytes(directory.resolve("resolved-models.json")));
            if (!Json.hash(metadata).equals(evidence.get("modelEvidenceHash")))
                throw new Failure("MODEL_EVIDENCE_INVALID", "Model metadata differs from its recorded hash");
            var hashes=(Map<String,String>)metadata.get("fileHashes");
            if (hashes==null || hashes.isEmpty() || !Json.hash(hashes).equals(evidence.get("resolvedModelsHash")))
                throw new Failure("MODEL_EVIDENCE_INVALID", "Missing or mismatching model hashes");
            var files=new TreeMap<String,byte[]>();
            for (var entry:hashes.entrySet()) {
                if (!Path.of(entry.getKey()).getFileName().toString().equals(entry.getKey()) || !entry.getKey().endsWith(".ili"))
                    throw new Failure("MODEL_EVIDENCE_INVALID", "Invalid model filename");
                Path file=directory.resolve("models").resolve(entry.getKey()); safe(workspace,file);
                if (!Files.isRegularFile(file) || Files.size(file)>10_000_000)
                    throw new Failure("MODEL_EVIDENCE_INVALID", "Missing or oversized model snapshot");
                byte[] bytes=Files.readAllBytes(file);
                if (!Json.hashBytes(bytes).equals(entry.getValue())) throw new Failure("MODEL_EVIDENCE_INVALID", "Model bytes differ: " + entry.getKey());
                files.put(entry.getKey(),bytes);
            }
            return files;
        } catch (Failure e) { throw e; }
        catch (Exception e) { throw new Failure("MODEL_EVIDENCE_INVALID", "Cannot read recorded model snapshot: " + e.getMessage()); }
    }
    static Map<String,Object> evidence(Path directory) throws Exception {
        var metadata=Json.object(Files.readAllBytes(directory.resolve("resolved-models.json")));
        return Map.of("modelSnapshot",directory.toString(),"resolvedModelsHash",Json.hash(metadata.get("fileHashes")),"modelEvidenceHash",Json.hash(metadata));
    }
    static void verifyArchive(Path workspace, Map<String,Object> record) throws Exception {
        try {
            Path archive=Path.of((String)record.get("logPath")).getParent().resolve("db-models.json"); safe(workspace,archive);
            var rows=Json.MAPPER.readValue(Files.readAllBytes(archive),List.class);
            if(!Json.hash(rows).equals(record.get("dbModelsHash"))) throw new Failure("MODEL_EVIDENCE_INVALID","Archived database models differ from their recorded hash");
        } catch(Failure e) { throw e; }
        catch(Exception e) { throw new Failure("MODEL_EVIDENCE_INVALID","Cannot read archived database models: " + e.getMessage()); }
    }
    static List<Map<String,Object>> database(Connection c, String schema) throws Exception {
        if (!schema.matches("[a-z][a-z0-9_]{0,62}")) throw new Failure("MODEL_EVIDENCE_INVALID", "Invalid schema");
        var rows=new ArrayList<Map<String,Object>>();
        try (var st=c.createStatement(); var rs=st.executeQuery("SELECT filename,iliversion,modelname,content FROM \""+schema+"\".t_ili2db_model ORDER BY filename,iliversion,modelname")) {
            while(rs.next()) {
                var row=new TreeMap<String,Object>();
                for(String key:List.of("filename","iliversion","modelname","content")) row.put(key,rs.getString(key));
                rows.add(row);
            }
        }
        return rows;
    }
    static void verifyDatabase(List<Map<String,Object>> rows, Map<String,byte[]> files) throws Exception {
        var actual=new TreeMap<String,String>();
        for(var row:rows) {
            String filename=(String)row.get("filename"), content=(String)row.get("content");
            if (content==null || actual.put(filename,content)!=null) throw new Failure("MODEL_VERIFICATION_FAILED", "Invalid model metadata rows");
        }
        // Compare complete file contents, including files that define several models.
        if(actual.isEmpty()) throw new Failure("MODEL_VERIFICATION_FAILED", "No persisted models");
        for(var entry:actual.entrySet()) {
            byte[] bytes=files.get(entry.getKey());
            if(bytes==null || !new String(bytes,StandardCharsets.UTF_8).equals(entry.getValue()))
                throw new Failure("MODEL_VERIFICATION_FAILED", "Persisted model differs: " + entry.getKey());
        }
        if (!actual.keySet().equals(files.keySet())) throw new Failure("MODEL_VERIFICATION_FAILED", "Persisted model closure is incomplete");
    }
}
