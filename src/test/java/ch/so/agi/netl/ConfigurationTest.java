package ch.so.agi.netl;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.Connection;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationTest {
    @TempDir Path root;
    Map<String,Object> entry;
    @BeforeEach void fixture() throws Exception {
        Files.createDirectories(root.resolve("themes/demo/test"));
        Files.writeString(root.resolve("themes/demo/test/Test.ili"),"local model bytes");
        entry=new LinkedHashMap<>(Map.of("ident","edit","name","test_edit_v1","database","edit",
            "models",List.of("Test"),"modelFiles",List.of("Test.ili"),"profile","lab-edit-v1"));
        write();
    }
    void write() throws Exception { Json.write(root.resolve("themes/demo/test/schemas.json"),Map.of("formatVersion",1,"schemas",List.of(entry))); }
    Configuration.Spec spec() throws Exception { return new Configuration(root).get("demo/test","edit"); }
    Configuration.Spec v2() throws Exception {
        return new Configuration(root).resolve("demo/test", Map.of("formatVersion",2,"schemas",List.of(entry))).getFirst();
    }
    @Test void versionsNamesAndRoles() throws Exception {
        assertEquals("test_edit_v1", spec().name());
        entry.remove("name"); entry.put("baseName","custom");
        assertEquals("custom",v2().name());
        assertEquals("NO_PREVIOUS_VERSION",assertThrows(Failure.class,()->v2().previousName()).code);
        entry.put("schemaVersion",1);
        assertEquals("custom_v1",v2().name());
        assertThrows(Failure.class,()->v2().previousName());
        entry.put("schemaVersion",2); entry.put("roleSuffix","_editdb");
        assertEquals("custom_v1",v2().previousName());
        assertEquals(List.of("custom_v2_editdb_read","custom_v2_editdb_write"),v2().roles());
        for (Object version : List.of(0,-1,"2",1.5)) {
            entry.put("schemaVersion",version); assertThrows(Failure.class,this::v2);
        }
        entry.put("schemaVersion",1); entry.put("baseName","a".repeat(55));
        assertThrows(Failure.class,this::v2);
    }
    @Test void sqlContentIsFingerprintedAndMustRemainLocal() throws Exception {
        entry.remove("name"); entry.put("baseName","custom");
        Path sql = root.resolve("themes/demo/test/grants.sql"); Files.writeString(sql,"SELECT 1;");
        entry.put("sqlFiles",Map.of("grants","grants.sql"));
        String before = v2().fingerprint(); Files.writeString(sql,"SELECT 2;");
        assertNotEquals(before,v2().fingerprint());
        entry.put("sqlFiles",Map.of("grants","../grants.sql")); assertThrows(Failure.class,this::v2);
        entry.put("sqlFiles",Map.of("unknown","grants.sql")); assertThrows(Failure.class,this::v2);
    }
    @Test void profilesAndExplicitFalse() throws Exception {
        assertEquals(true,((Map<?,?>)spec().effective().get("options")).get("nameByTopic"));
        entry.put("overrides",Map.of("createFk",false)); write();
        assertEquals(false,((Map<?,?>)spec().effective().get("options")).get("createFk"));
        entry.put("profile","lab-pub-v1"); write();
        assertEquals(false,((Map<?,?>)spec().effective().get("options")).get("nameByTopic"));
    }
    @Test void rejectsUnknownAndWrongTypes() throws Exception {
        entry.put("overrides",Map.of("createFkk",true)); write(); assertThrows(Failure.class,this::spec);
        entry.put("overrides",Map.of("createFk","false")); write(); assertThrows(Failure.class,this::spec);
        entry.put("overrides",Map.of("defaultSrsCode",2056)); write(); assertThrows(Failure.class,this::spec);
        entry.remove("overrides"); entry.put("command","drop"); write(); assertThrows(Failure.class,this::spec);
    }
    @Test void rejectsTraversalAndSymlinkEscape(@TempDir Path external) throws Exception {
        assertThrows(Failure.class,()->new Configuration(root).list("../test"));
        entry.put("modelFiles",List.of("../../../outside.ili")); write(); assertThrows(Failure.class,this::spec);
        Files.writeString(external.resolve("Outside.ili"),"outside");
        Files.createSymbolicLink(root.resolve("themes/demo/test/Outside.ili"),external.resolve("Outside.ili"));
        entry.put("modelFiles",List.of("Outside.ili")); write(); assertThrows(Failure.class,this::spec);
        Files.createSymbolicLink(root.resolve(".netl"),external);
        assertThrows(Failure.class,()->new SchemaService(root).validateLocalPath(root.resolve(".netl/state/test.json")));
    }
    @Test void modelBytesAndOptionsAffectFingerprint() throws Exception {
        String first=spec().fingerprint();
        Files.writeString(root.resolve("themes/demo/test/Test.ili"),"changed local model");
        String second=spec().fingerprint(); assertNotEquals(first,second);
        entry.put("overrides",Map.of("createFk",false)); write(); assertNotEquals(second,spec().fingerprint());
    }
    @Test void rejectsDuplicateTargetAndKeys() throws Exception {
        Json.write(root.resolve("themes/demo/test/schemas.json"),Map.of("formatVersion",1,"schemas",List.of(entry,entry)));
        assertThrows(Failure.class,this::spec);
        Files.writeString(root.resolve("themes/demo/test/schemas.json"),"{\"formatVersion\":1,\"formatVersion\":1,\"schemas\":[]}");
        assertThrows(Exception.class,this::spec);
    }
    @Test void unavailableDatabaseIsNeverMissingAndListWorksOffline() throws Exception {
        var cfg=new Configuration(root);
        var offline=new LocalRuntime(root) {
            @Override Connection connect(String db) { throw new Failure("DB_UNAVAILABLE","offline"); }
            @Override void runnerReady() {}
        };
        var service=new SchemaService(cfg,offline);
        assertEquals("OK",service.call("list","demo/test",null).get("status"));
        assertEquals("DB_UNAVAILABLE",service.call("inspect","demo/test","edit").get("code"));
        assertEquals("BLOCKED",service.call("plan","demo/test","edit").get("status"));
        assertEquals("DB_UNAVAILABLE",service.call("create","demo/test","edit").get("code"));
    }
    @Test void managedEvidenceSupportsInterruptedRunsButRejectsAnotherWorkspace() throws Exception {
        var service = new SchemaService(root);
        var spec = spec();
        Path run = Files.createDirectories(root.resolve(".netl/runs/test-run"));
        Files.createDirectories(root.resolve(".netl/state"));
        var record = new TreeMap<String,Object>();
        record.put("status", "RUNNING");
        record.put("target", spec.identity());
        record.put("logPath", run.resolve("runner.log").toString());
        record.put("workspace", root.toRealPath().toString());
        Json.write(run.resolve("result.json"), record);
        Json.write(service.statePath(spec), record);
        assertDoesNotThrow(() -> service.requireManaged(spec));
        record.put("workspace", root.resolve("another-workspace").toString());
        Json.write(run.resolve("result.json"), record);
        Json.write(service.statePath(spec), record);
        assertEquals("UNMANAGED", assertThrows(Failure.class, () -> service.requireManaged(spec)).code);
    }

}
