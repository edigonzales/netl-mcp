package ch.so.agi.netl;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class JobIntegrationTest {
    Path workspace, directory, job;
    String theme;
    JobService service;
    @BeforeEach void setup() throws Exception {
        workspace=Path.of(System.getProperty("netl.workspace")).toRealPath();
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,12);
        theme="tests/job_"+suffix; directory=Files.createDirectories(workspace.resolve("themes/"+theme));
        Path demo=workspace.resolve("themes/demo/standorte");
        try(var paths=Files.walk(demo)) { for(Path p:paths.toList()) {
            Path to=directory.resolve(demo.relativize(p)); if(Files.isDirectory(p))Files.createDirectories(to);else if(!p.getFileName().toString().equals(".DS_Store"))Files.copy(p,to);
        }}
        var config=Json.object(Files.readAllBytes(directory.resolve("schemas.json")));
        for(Object obj:(List<?>)config.get("schemas")) { @SuppressWarnings("unchecked") var s=(Map<String,Object>)obj; s.put("baseName","netl_ji_"+suffix+"_"+s.get("database")); s.remove("sqlFiles"); }
        Json.write(directory.resolve("schemas.json"),config);
        job=directory.resolve("jobs/edit-to-pub"); service=new JobService(workspace);
    }
    void confirm() throws Exception {
        assertEquals("CONFIRMED",service.call("confirm",theme,"edit-to-pub",service.jobs.load(theme,"edit-to-pub").contract()).get("status"));
    }
    Map<String,Object> test() { return service.call("test",theme,"edit-to-pub",null); }
    @AfterEach void cleanup() throws Exception {
        for(var s:service.schemas.config.list(theme)) {
            try(var c=service.runtime.connect(s.database())) {
                if(Database.exists(c,s.name())) Database.guardedDrop(c,s.name(),service.schemas.managedRoles(s,c),true);
            }
            Files.deleteIfExists(service.schemas.statePath(s));
        }
        try(var paths=Files.walk(directory)) { for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p); }
    }
    @Test void realDb2DbFixturesChecksAndDaemonReuse() throws Exception {
        confirm(); var result=test(); assertEquals("PASSED",result.get("status"),result.toString());
        Path run=Path.of((String)result.get("runDirectory"));
        var first=Json.object(Files.readAllBytes(run.resolve("first.log.runtime.json")));
        var repeat=Json.object(Files.readAllBytes(run.resolve("repeat.log.runtime.json")));
        assertEquals(first.get("containerId"),repeat.get("containerId")); assertEquals(first.get("runtime"),repeat.get("runtime"));
        assertFalse(Files.exists(run.resolve("gradle.properties")));
        assertEquals("MISSING",service.schemas.call("inspect",theme,"edit").get("status"));
        assertEquals("MISSING",service.schemas.call("inspect",theme,"pub").get("status"));
        assertEquals("ERROR",service.call("plan",theme,"edit-to-pub",null).get("status"));
    }
    @Test void wrongMappingAndEmptyResultAreNotGradleSuccess() throws Exception {
        confirm(); Path sql=job.resolve("sql/standorte.sql"); String correct=Files.readString(sql);
        Files.writeString(sql,correct.replace("o.aname AS organisation","'Wrong' AS organisation"));
        var wrong=test(); assertEquals("FAILED",wrong.get("status"),wrong.toString()); assertEquals(true,wrong.get("gradleSucceeded"));
        assertEquals("UNCHANGED_JOB",test().get("code"));
        Files.writeString(sql,correct.replace("ORDER BY", "WHERE false ORDER BY"));
        var empty=test(); assertEquals("FAILED",empty.get("status")); assertEquals(2,empty.get("attempt"));
        Files.writeString(sql,correct); assertEquals("PASSED",test().get("status"));
    }
    @Test void invalidAssertionAndSqlErrorsFail() throws Exception {
        Files.writeString(job.resolve("assertions/expected.sql"),"SELECT nonexistent_column;"); confirm();
        var result=test(); assertEquals("FAILED",result.get("status"),result.toString());
        assertTrue(((List<?>)result.get("checks")).stream().anyMatch(x->"ERROR".equals(((Map<?,?>)x).get("status"))));
        Files.writeString(job.resolve("sql/standorte.sql"),"SELECT nonexistent_column;");
        result=test(); assertEquals("FAILED",result.get("status")); assertEquals("RUNNER_FAILED",result.get("code"));
    }
    @Test void localTokenAndPostCommitFailure() throws Exception {
        confirm(); assertEquals("PASSED",test().get("status"));
        for(String db:List.of("edit","pub")) assertEquals("CREATED",service.schemas.call("create",theme,db).get("status"));
        var spec=service.jobs.load(theme,"edit-to-pub");
        try(var c=service.runtime.connect("edit");var st=c.createStatement()) { st.execute(JobChecks.sql(spec.files().get("fixtures/standorte.sql"),spec.source().name(),spec.target().name())); }
        var plan=service.call("plan",theme,"edit-to-pub",null); assertEquals("READY",plan.get("status"),plan.toString());
        String token=(String)plan.get("planToken");
        var result=service.call("run",theme,"edit-to-pub",token); assertEquals("PASSED",result.get("status"),result.toString());
        assertEquals("INVALID_PLAN",service.call("run",theme,"edit-to-pub",token).get("code"));
        plan=service.call("plan",theme,"edit-to-pub",null); assertEquals("READY",plan.get("status"),plan.toString());
        try(var c=service.runtime.connect("edit");var st=c.createStatement()) { st.execute("DELETE FROM "+spec.source().name()+".standorte_standort"); }
        result=service.call("run",theme,"edit-to-pub",(String)plan.get("planToken"));
        assertEquals("FAILED",result.get("status"),result.toString()); assertEquals(true,result.get("targetMayHaveChanged")); assertEquals(true,result.get("gradleSucceeded"));
        try(var c=service.runtime.connect("pub");var st=c.createStatement();var rs=st.executeQuery("SELECT count(*) FROM "+spec.target().name()+".standort")) { rs.next(); assertEquals(0,rs.getInt(1)); }
    }
    @Test void concurrentBusyAndTimeoutRecovery() throws Exception {
        try(var held=service.runtime.lock()) { assertEquals("BUSY",test().get("code")); }
        Files.writeString(job.resolve("sql/standorte.sql"),Files.readString(job.resolve("sql/standorte.sql")).replace("ORDER BY", "CROSS JOIN pg_sleep(30) ORDER BY"));
        confirm(); var fast=new JobService(workspace,Duration.ofSeconds(3));
        var result=fast.call("test",theme,"edit-to-pub",null); assertEquals("RUNNER_TIMEOUT",result.get("code"),result.toString());
        service.runtime.runnerReady();
        for(String db:List.of("edit","pub")) try(var c=service.runtime.connect(db);var st=c.createStatement();var rs=st.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE application_name LIKE 'netl-job-%'")) { rs.next(); assertEquals(0,rs.getInt(1)); }
        Files.writeString(job.resolve("build.gradle"),Files.readString(job.resolve("build.gradle"))+"// changed\n");
        assertEquals("RETRY_BLOCKED",test().get("code"));
        try(var held=service.runtime.lock()) { assertNotNull(held); }
    }
    @Test void duplicatesAndReadOnlyAssertionAreRejected() throws Exception {
        confirm();
        String sql=Files.readString(job.resolve("sql/standorte.sql")).replace("ORDER BY s.kennung;","");
        Files.writeString(job.resolve("sql/standorte.sql"),sql+" UNION ALL "+sql+";");
        var result=test(); assertEquals("FAILED",result.get("status"),result.toString());
        assertEquals("RUNNER_FAILED",result.get("code")); // unique constraint catches duplicate inserts atomically
        try(var c=service.runtime.connect("pub")) {
            var check=JobChecks.check(c,"readonly","Cannot write in an assertion","CREATE TABLE public.netl_should_never_exist(id integer)");
            assertEquals("ERROR",check.get("status"));
            assertTrue(Database.query(c,"SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename=?","netl_should_never_exist").isEmpty());
        }
    }
    @Test void unconfirmedTestsCannotAuthorizeRunAndChangedFilesInvalidatePlan() throws Exception {
        var result=test(); assertEquals("GENERIC_ONLY",result.get("status"),result.toString());
        assertEquals("ERROR",service.call("plan",theme,"edit-to-pub",null).get("status"));
        confirm(); assertEquals("PASSED",test().get("status"));
        for(String db:List.of("edit","pub")) assertEquals("CREATED",service.schemas.call("create",theme,db).get("status"));
        var plan=service.call("plan",theme,"edit-to-pub",null); assertEquals("READY",plan.get("status"));
        Files.writeString(job.resolve("sql/standorte.sql"),Files.readString(job.resolve("sql/standorte.sql"))+"\n-- changed\n");
        assertEquals("STALE_PLAN",service.call("run",theme,"edit-to-pub",(String)plan.get("planToken")).get("code"));
        assertEquals("TEST_REQUIRED",service.call("plan",theme,"edit-to-pub",null).get("code"));
    }
    @Test void dmlConnectionsHaveOnlySelectedRightsAndRestorePermissions() throws Exception {
        for(String db:List.of("edit","pub")) assertEquals("CREATED",service.schemas.call("create",theme,db).get("status"));
        var context=service.call("context",theme,null,null);
        String encoded=Json.MAPPER.writeValueAsString(context);
        assertTrue(encoded.length()<20000,"Job catalog must remain compact enough for agent tools");
        assertTrue(encoded.contains("standorte_organisation")); assertFalse(encoded.contains("lastRun"));
        var spec=service.jobs.load(theme,"edit-to-pub");
        try(var a=service.runtime.connect("edit");var b=service.runtime.connect("pub")) {
            try(var credentials=service.new Credentials(a,b,spec.source().name(),spec.target().name(),false)) {
                try(var source=credentials.connect("edit");var st=source.createStatement()) {
                    assertThrows(java.sql.SQLException.class,()->st.execute("INSERT INTO "+spec.source().name()+".standorte_organisation(aname) VALUES ('Denied')"));
                    try(var r=st.executeQuery("SELECT rolsuper FROM pg_roles WHERE rolname=current_user")) { r.next(); assertFalse(r.getBoolean(1)); }
                }
                try(var target=credentials.connect("pub");var st=target.createStatement()) {
                    st.execute("INSERT INTO "+spec.target().name()+".standort(kennung,aname,organisation,geometrie) VALUES ('T','Test','Synthetic',ST_SetSRID(ST_MakePoint(2600000,1200000),2056))");
                    assertThrows(java.sql.SQLException.class,()->st.execute("CREATE TABLE "+spec.target().name()+".forbidden(id integer)"));
                }
            }
        }
        for(String db:List.of("edit","pub")) assertEquals("MATCHING",service.schemas.call("inspect",theme,db).get("status"));
    }
}
