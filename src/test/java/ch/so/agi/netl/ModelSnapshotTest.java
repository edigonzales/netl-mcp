package ch.so.agi.netl;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelSnapshotTest {
    @TempDir Path workspace;
    Path directory; Map<String,Object> evidence;
    @BeforeEach void setup() throws Exception {
        directory=Files.createDirectories(workspace.resolve(".netl/model-runs/test/models")).getParent();
        byte[] bytes="INTERLIS 2.3; !! synthetic".getBytes(StandardCharsets.UTF_8);
        Files.write(directory.resolve("models/Main.ili"),bytes);
        Json.write(directory.resolve("resolved-models.json"),Map.of("fileHashes",Map.of("Main.ili",Json.hashBytes(bytes)),"localFiles",List.of("Main.ili")));
        evidence=ModelSnapshot.evidence(directory);
    }
    @Test void freezesBytesAndRejectsChangedFilesOrMetadata() throws Exception {
        assertEquals(Set.of("Main.ili"),ModelSnapshot.files(workspace,evidence).keySet());
        Files.writeString(directory.resolve("models/Main.ili"),"changed");
        assertEquals("MODEL_EVIDENCE_INVALID",assertThrows(Failure.class,()->ModelSnapshot.files(workspace,evidence)).code);
        Files.delete(directory.resolve("models/Main.ili"));
        assertThrows(Failure.class,()->ModelSnapshot.files(workspace,evidence));
    }
    @Test void hashDoesNotDependOnOriginOrRequestsButEvidenceDoes() throws Exception {
        var metadata=Json.object(Files.readAllBytes(directory.resolve("resolved-models.json")));metadata.put("requests",List.of("https://example.invalid/"));
        Json.write(directory.resolve("resolved-models.json"),metadata);
        var next=ModelSnapshot.evidence(directory);
        assertEquals(evidence.get("resolvedModelsHash"),next.get("resolvedModelsHash"));assertNotEquals(evidence.get("modelEvidenceHash"),next.get("modelEvidenceHash"));
        assertThrows(Failure.class,()->ModelSnapshot.files(workspace,evidence));
    }
    @Test void pathsAndSymlinksCannotEscapeEvidence(@TempDir Path outside) throws Exception {
        var bad=new TreeMap<>(evidence);bad.put("modelSnapshot",outside.toString());assertThrows(Failure.class,()->ModelSnapshot.files(workspace,bad));
        Path file=directory.resolve("models/Main.ili");Files.delete(file);Files.writeString(outside.resolve("Main.ili"),"foreign");Files.createSymbolicLink(file,outside.resolve("Main.ili"));
        assertThrows(Failure.class,()->ModelSnapshot.files(workspace,evidence));
    }
    @Test void databaseContentsMustMatchEntireClosure() throws Exception {
        var files=ModelSnapshot.files(workspace,evidence);
        var row=Map.<String,Object>of("filename","Main.ili","content",new String(files.get("Main.ili"),StandardCharsets.UTF_8));
        ModelSnapshot.verifyDatabase(List.of(row),files);
        assertThrows(Failure.class,()->ModelSnapshot.verifyDatabase(List.of(),files));
        assertThrows(Failure.class,()->ModelSnapshot.verifyDatabase(List.of(Map.of("filename","Main.ili","content","changed")),files));
        assertThrows(Failure.class,()->ModelSnapshot.verifyDatabase(List.of(row,row),files));
    }
    @Test void databaseArchiveCannotBeChangedOrRemoved() throws Exception {
        Path run=Files.createDirectories(workspace.resolve(".netl/runs/test"));
        var rows=List.of(Map.of("filename","Main.ili","content","synthetic"));
        Json.write(run.resolve("db-models.json"),rows);
        var record=Map.<String,Object>of("logPath",run.resolve("runner.log").toString(),"dbModelsHash",Json.hash(rows));
        ModelSnapshot.verifyArchive(workspace,record);
        Json.write(run.resolve("db-models.json"),List.of());
        assertThrows(Failure.class,()->ModelSnapshot.verifyArchive(workspace,record));
        Files.delete(run.resolve("db-models.json"));
        assertThrows(Failure.class,()->ModelSnapshot.verifyArchive(workspace,record));
    }

}
