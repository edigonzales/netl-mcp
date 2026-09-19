package ch.so.agi.netl;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JobConfigurationTest {
    @TempDir Path workspace;
    Path directory;
    JobService service;
    @BeforeEach void setup() throws Exception {
        Path theme=Files.createDirectories(workspace.resolve("themes/test/demo"));
        Files.writeString(theme.resolve("model.ili"),"synthetic model");
        Json.write(theme.resolve("schemas.json"),Map.of("formatVersion",2,"schemas",List.of(
            Map.of("ident","edit","baseName","example_edit","schemaVersion",1,"database","edit","profile","lab-edit-v1","models",List.of("Model"),"modelFiles",List.of("model.ili")),
            Map.of("ident","pub","baseName","example_pub","schemaVersion",2,"database","pub","profile","lab-pub-v1","models",List.of("Model"),"modelFiles",List.of("model.ili")))));
        directory=Files.createDirectories(theme.resolve("jobs/transfer"));
        Files.createDirectories(directory.resolve("sql")); Files.createDirectories(directory.resolve("fixtures")); Files.createDirectories(directory.resolve("assertions"));
        Json.write(directory.resolve("job.json"),Map.of("formatVersion",1,"source","edit","target","pub","task","transfer","sqlFiles",List.of("sql/transfer.sql"),"tables",List.of(Map.of("name","standort","key",List.of("kennung"),"columns",List.of("kennung","aname")))));
        Json.write(directory.resolve("tests.json"),Map.of("formatVersion",1,"requirements","Explicit requirement","fixtures",List.of("fixtures/data.sql"),"assertions",List.of(Map.of("id","expected","description","Expected result","origin","user","scope","fixture","expected","zero_violations","sql","assertions/check.sql"))));
        Files.writeString(directory.resolve("build.gradle"),"// job"); Files.writeString(directory.resolve("sql/transfer.sql"),"SELECT 1;");
        Files.writeString(directory.resolve("fixtures/data.sql"),"SELECT 1;"); Files.writeString(directory.resolve("assertions/check.sql"),"SELECT 1 WHERE false;");
        Files.createDirectory(workspace.resolve(".netl")); service=new JobService(workspace);
    }
    @Test void revisionsSeparateTransformAndExpectations() throws Exception {
        var first=service.jobs.load("test/demo","transfer");
        assertEquals("example_pub_v2",first.target().name());
        Files.writeString(directory.resolve("sql/transfer.sql"),"SELECT 2;");
        var changed=service.jobs.load("test/demo","transfer");
        assertEquals(first.contract(),changed.contract()); assertNotEquals(first.fingerprint(),changed.fingerprint());
        Files.writeString(directory.resolve("fixtures/data.sql"),"SELECT 3;");
        assertNotEquals(changed.contract(),service.jobs.load("test/demo","transfer").contract());
        assertTrue(Configuration.runnerHashes().containsKey("init.gradle"));
    }
    @Test void capabilityAndConfirmationBoundaries() throws Exception {
        assertEquals("ERROR",service.write("test/demo","transfer","fixtures/data.sql","bad",false).get("status"));
        assertEquals("ERROR",service.write("test/demo","transfer","build.gradle","bad",true).get("status"));
        assertEquals("ERROR",service.write("test/demo","transfer","../../escape.sql","bad",true).get("status"));
        var spec=service.jobs.load("test/demo","transfer");
        assertEquals("ERROR",service.call("confirm","test/demo","transfer","wrong").get("status"));
        assertEquals("CONFIRMED",service.call("confirm","test/demo","transfer",spec.contract()).get("status"));
        assertEquals("ERROR",service.write("test/demo","transfer","assertions/check.sql","SELECT false",true).get("status"));
        assertEquals("SAVED",service.write("test/demo","transfer","sql/transfer.sql","SELECT 2",false).get("status"));
    }
    @Test void symlinkAndTaskInjectionRejected() throws Exception {
        Files.delete(directory.resolve("sql/transfer.sql")); Files.createSymbolicLink(directory.resolve("sql/transfer.sql"),directory.resolve("fixtures/data.sql"));
        assertThrows(Failure.class,()->service.jobs.load("test/demo","transfer"));
        Files.delete(directory.resolve("sql/transfer.sql")); Files.writeString(directory.resolve("sql/transfer.sql"),"SELECT 1");
        var manifest=Json.object(Files.readAllBytes(directory.resolve("job.json"))); manifest.put("task","--init-script=bad"); Json.write(directory.resolve("job.json"),manifest);
        assertThrows(Failure.class,()->service.jobs.load("test/demo","transfer"));
    }
    @Test void identifiersAndAssertionSemantics() {
        assertThrows(Failure.class,()->JobConfiguration.identifier("x; DROP SCHEMA y"));
        assertFalse(JobChecks.passed(List.of(Map.of("status","ERROR"))));
        assertFalse(JobChecks.passed(List.of(Map.of("status","FAILED"))));
        assertTrue(JobChecks.passed(List.of(Map.of("status","PASSED"))));
        assertEquals("SELECT * FROM \"pub_v2\".x",JobChecks.sql("SELECT * FROM ${targetSchema}.x".getBytes(),"edit_v1","pub_v2"));
    }
    @Test void schemaVersionChangesInvalidateAcceptance() throws Exception {
        var before=service.jobs.load("test/demo","transfer");
        Path file=workspace.resolve("themes/test/demo/schemas.json");
        var config=Json.object(Files.readAllBytes(file));
        @SuppressWarnings("unchecked") var target=(Map<String,Object>)((List<?>)config.get("schemas")).get(1);
        target.put("schemaVersion",3); Json.write(file,config);
        var after=service.jobs.load("test/demo","transfer");
        assertEquals("example_pub_v3",after.target().name());
        assertNotEquals(before.contract(),after.contract()); assertNotEquals(before.fingerprint(),after.fingerprint());
    }
    @Test void statusIsReadOnlyAndIncompleteManifestsFailBeforeSave() throws Exception {
        assertEquals("OK",service.call("status","test/demo","transfer",null).get("status"));
        assertFalse(Files.exists(workspace.resolve(".netl/jobs")));
        assertEquals("ERROR",service.write("test/demo","transfer","job.json","{\"formatVersion\":1,\"source\":\"edit\",\"target\":\"pub\"}",false).get("status"));
        assertEquals("ERROR",service.write("test/demo","transfer","tests.json","{\"fixtures\":[\"x.sql\"]}",true).get("status"));
        assertEquals("VALID",service.call("validate","test/demo","transfer",null).get("status"));
    }
}
