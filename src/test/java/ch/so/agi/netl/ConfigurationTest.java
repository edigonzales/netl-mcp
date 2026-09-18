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
}
