package ch.so.agi.netl;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class SchemaIntegrationTest {
    Path workspace, directory;
    String theme, edit, pub;
    SchemaService service;
    List<Map<String,Object>> entries;
    int formatVersion = 1;
    Set<String> extraEditSchemas = new HashSet<>();
    @BeforeEach void setup() throws Exception {
        workspace=Path.of(System.getProperty("netl.workspace")).toRealPath();
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,12);
        theme="tests/c"+suffix; edit="netl_it_"+suffix+"_edit"; pub="netl_it_"+suffix+"_pub";
        directory=Files.createDirectories(workspace.resolve("themes/"+theme));
        entries=new ArrayList<>();
        for (String db:List.of("edit","pub")) {
            String model="Lab_Standorte_"+(db.equals("edit")?"Edit":"Pub");
            Files.copy(workspace.resolve("themes/demo/standorte/modelle/"+model+".ili"),directory.resolve(model+".ili"));
            entries.add(new LinkedHashMap<>(Map.of("ident",db,"name",db.equals("edit")?edit:pub,"database",db,
                "profile","lab-"+db+"-v1","models",List.of(model),"modelFiles",List.of(model+".ili"))));
        }
        write(); service=new SchemaService(workspace);
    }
    void write() throws Exception { Json.write(directory.resolve("schemas.json"),Map.of("formatVersion",formatVersion,"schemas",entries)); }
    @AfterEach void cleanup() throws Exception {
        for (String name : extraEditSchemas) {
            assertTrue(name.startsWith(edit + "_"));
            try (var c=service.runtime.connect("edit"); var st=c.createStatement()) {
                st.execute("DROP SCHEMA IF EXISTS " + name + " CASCADE");
                st.execute("DROP ROLE IF EXISTS " + name + "_read, " + name + "_write");
            }
            Files.deleteIfExists(workspace.resolve(".netl/state/edit-"+name+".json"));
        }
        for (String db:List.of("edit","pub")) {
            String name=db.equals("edit")?edit:pub;
            if (name==null || !name.matches("netl_it_[a-f0-9]{12}_(edit|pub)")) continue;
            try (var c=service.runtime.connect(db); var st=c.createStatement()) {
                st.execute("DROP SCHEMA IF EXISTS \""+name+"\" CASCADE");
                st.execute("DROP ROLE IF EXISTS " + name + "_read, " + name + "_write");
            }
            Files.deleteIfExists(workspace.resolve(".netl/state/"+db+"-"+name+".json"));
        }
        try (var paths=Files.walk(directory)) { for (Path p:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
    }
    void versioned() throws Exception {
        formatVersion = 2;
        for (var entry : entries) entry.put("baseName",entry.remove("name"));
        entries.getFirst().put("schemaVersion",1);
        Files.writeString(directory.resolve("grants.sql"), "GRANT ${dbSchema}${roleSuffix}_read TO netl_reader; GRANT ${dbSchema}${roleSuffix}_write TO netl_writer;");
        entries.getFirst().put("sqlFiles", Map.of("grants","grants.sql"));
        extraEditSchemas.add(edit+"_v1"); extraEditSchemas.add(edit+"_v2");
        write();
    }
    @Test void versionsCoexistAndDeletionIsExplicitAndScoped() throws Exception {
        versioned();
        assertEquals("CREATED",service.call("create",theme,"edit").get("status"));
        assertEquals("BLOCKED",service.plan(theme,"edit","drop-previous").get("status"));
        entries.getFirst().put("schemaVersion",2); write();
        assertEquals("MISSING",service.call("inspect",theme,"edit").get("status"));
        assertEquals("CREATED",service.call("create",theme,"edit").get("status"));
        try (var c=service.runtime.connect("edit")) {
            assertTrue(Database.exists(c,edit+"_v1")); assertTrue(Database.exists(c,edit+"_v2"));
            assertEquals(4,Database.roleIds(c,List.of(edit+"_v1_read",edit+"_v1_write",edit+"_v2_read",edit+"_v2_write")).size());
        }
        assertEquals(2,service.knownVersions(service.config.get(theme,"edit")).size());
        var plan=service.plan(theme,"edit","drop-previous"); assertEquals("READY",plan.get("status"),plan.toString());
        String token=(String)plan.get("planToken");
        assertEquals("STALE_PLAN",service.recreate(theme,"edit",token).get("code"));
        var deleted=service.dropPrevious(theme,"edit",token); assertEquals("DELETED",deleted.get("status"),deleted.toString());
        assertEquals("INVALID_PLAN",service.dropPrevious(theme,"edit",token).get("code"));
        try (var c=service.runtime.connect("edit")) {
            assertFalse(Database.exists(c,edit+"_v1")); assertTrue(Database.exists(c,edit+"_v2"));
            assertTrue(Database.roleIds(c,List.of(edit+"_v1_read",edit+"_v1_write")).isEmpty());
        }
        assertEquals("MATCHING",service.call("inspect",theme,"edit").get("status"));
    }
    @Test void actualReaderWriterPrivilegesAndDrift() throws Exception {
        versioned();
        Files.writeString(directory.resolve("postscript.sql"), "CREATE TABLE ${dbSchema}.probe(id bigserial PRIMARY KEY, value text); CREATE VIEW ${dbSchema}.probe_view AS SELECT * FROM ${dbSchema}.probe;");
        entries.getFirst().put("sqlFiles",Map.of("grants","grants.sql","postscript","postscript.sql")); write();
        var created=service.call("create",theme,"edit"); assertEquals("CREATED",created.get("status"),created.toString());
        try (var writer=DriverManager.getConnection("jdbc:postgresql://127.0.0.1:55431/edit","netl_writer","netl-writer-local"); var st=writer.createStatement()) {
            st.execute("INSERT INTO "+edit+"_v1.probe(value) VALUES ('synthetic')");
            st.execute("UPDATE "+edit+"_v1.probe SET value='changed'");
            st.executeQuery("SELECT * FROM "+edit+"_v1.probe_view").close();
        }
        try (var reader=DriverManager.getConnection("jdbc:postgresql://127.0.0.1:55431/edit","netl_reader","netl-reader-local"); var st=reader.createStatement()) {
            try(var r=st.executeQuery("SELECT value FROM "+edit+"_v1.probe_view")) { assertTrue(r.next()); assertEquals("changed",r.getString(1)); }
            assertThrows(SQLException.class,()->st.execute("INSERT INTO "+edit+"_v1.probe(value) VALUES ('denied')"));
            assertThrows(SQLException.class,()->st.execute("DELETE FROM "+edit+"_v1.probe"));
        }
        try(var c=service.runtime.connect("edit");var st=c.createStatement()) {
            st.execute("REVOKE "+edit+"_v1_read FROM netl_reader");
            assertEquals("DRIFTED",service.call("inspect",theme,"edit").get("status"));
            st.execute("GRANT "+edit+"_v1_read TO netl_reader");
            assertEquals("MATCHING",service.call("inspect",theme,"edit").get("status"));
            st.execute("REVOKE SELECT ON "+edit+"_v1.probe FROM "+edit+"_v1_read");
        }
        assertEquals("DRIFTED",service.call("inspect",theme,"edit").get("status"));
    }
    @Test void foreignRoleAndExternalRoleDependencyAreNeverDeleted() throws Exception {
        try(var c=service.runtime.connect("edit");var st=c.createStatement()) { st.execute("CREATE ROLE "+edit+"_read"); }
        assertEquals("UNMANAGED_ROLE",service.call("create",theme,"edit").get("code"));
        try(var c=service.runtime.connect("edit");var st=c.createStatement()) { st.execute("DROP ROLE "+edit+"_read"); }
        assertEquals("CREATED",service.call("create",theme,"edit").get("status"));
        String outside=edit+"_outside"; extraEditSchemas.add(outside);
        try(var c=service.runtime.connect("edit");var st=c.createStatement()) {
            st.execute("CREATE SCHEMA "+outside); st.execute("GRANT USAGE ON SCHEMA "+outside+" TO "+edit+"_read");
            var plan=service.plan(theme,"edit","recreate");
            assertEquals("BLOCKED",plan.get("status"));
            assertEquals("EXTERNAL_DEPENDENCY",((Map<?,?>)plan.get("problem")).get("code"));
            assertTrue(Database.exists(c,edit));
        }
    }
    @Test void sqlFailureRecordsRolesAndStopsWithoutRetry() throws Exception {
        versioned(); Files.writeString(directory.resolve("grants.sql"),"SELECT no_such_column;");
        var result=service.call("create",theme,"edit"); assertEquals("RUNNER_FAILED",result.get("code"),result.toString());
        assertEquals(2,((Map<?,?>)service.state(service.config.get(theme,"edit")).get("managedRoles")).size());
        assertEquals("BLOCKED",service.call("create",theme,"edit").get("status"));
        Files.writeString(directory.resolve("grants.sql"),"GRANT ${dbSchema}_read TO netl_reader;");
        assertEquals("RECREATED",service.recreate(theme,"edit",token()).get("status"));
    }
    @Test void replacementRoleOidAndLegacyEvidenceCannotAuthorizeRoleDeletion() throws Exception {
        assertEquals("CREATED",service.call("create",theme,"edit").get("status"));
        var spec=service.config.get(theme,"edit");
        var record=new TreeMap<String,Object>(service.state(spec));
        Path result=Path.of((String)record.get("logPath")).getParent().resolve("result.json");
        Object roles=record.remove("managedRoles");
        Json.write(result,record); Json.write(service.statePath(spec),record);
        assertEquals("LEGACY_UNVERIFIED",service.call("inspect",theme,"edit").get("roleManagement"));
        assertEquals("UNMANAGED_ROLE",((Map<?,?>)service.plan(theme,"edit","recreate").get("problem")).get("code"));
        record.put("managedRoles",roles); Json.write(result,record); Json.write(service.statePath(spec),record);
        try(var c=service.runtime.connect("edit");var st=c.createStatement()) {
            st.execute("REVOKE ALL ON SCHEMA "+edit+" FROM "+edit+"_read");
            st.execute("REVOKE ALL ON ALL TABLES IN SCHEMA "+edit+" FROM "+edit+"_read");
            st.execute("DROP ROLE "+edit+"_read"); st.execute("CREATE ROLE "+edit+"_read");
        }
        assertEquals("UNMANAGED_ROLE",((Map<?,?>)service.plan(theme,"edit","recreate").get("problem")).get("code"));
        try(var c=service.runtime.connect("edit")) { assertTrue(Database.exists(c,edit)); }
    }
    @Test void previousDeletionRejectsStaleInputsAndExternalDependencies() throws Exception {
        versioned(); assertEquals("CREATED",service.call("create",theme,"edit").get("status"));
        entries.getFirst().put("schemaVersion",2); write();
        var plan=service.plan(theme,"edit","drop-previous"); assertEquals("READY",plan.get("status"),plan.toString());
        entries.getFirst().put("schemaComment","changed"); write();
        assertEquals("STALE_PLAN",service.dropPrevious(theme,"edit",(String)plan.get("planToken")).get("code"));
        String outside=edit+"_outside"; extraEditSchemas.add(outside);
        try(var c=service.runtime.connect("edit");var st=c.createStatement()) {
            st.execute("CREATE SCHEMA "+outside);
            st.execute("CREATE VIEW "+outside+".dependent AS SELECT * FROM "+edit+"_v1.standorte_standort");
            plan=service.plan(theme,"edit","drop-previous"); assertEquals("BLOCKED",plan.get("status"));
            assertEquals("EXTERNAL_DEPENDENCY",((Map<?,?>)plan.get("problem")).get("code"));
            assertTrue(Database.exists(c,edit+"_v1"));
        }
    }
    @Test void runnerIsExclusiveAndDaemonIsReused() throws Exception {
        try(var held=service.runtime.lock()) {
            assertEquals("BUSY",service.call("create",theme,"edit").get("code"));
            assertEquals("BUSY",service.call("create",theme,"pub").get("code"));
        }
        service.runtime.recover(); // Deliberate cold baseline on the dedicated lab runner.
        String container=LocalRuntime.command(List.of("docker","inspect","--format","{{.Id}}","themenintegration-lab-gretl-1")).trim();
        long start=System.nanoTime();
        assertEquals("CREATED",service.call("create",theme,"edit").get("status"));
        long first=System.nanoTime()-start;
        var daemon=service.state(service.config.get(theme,"edit")).get("runtime");
        start=System.nanoTime();
        assertEquals("CREATED",service.call("create",theme,"pub").get("status"));
        long second=System.nanoTime()-start;
        assertEquals(daemon,service.state(service.config.get(theme,"pub")).get("runtime"));
        start=System.nanoTime();
        assertEquals("RECREATED",service.recreate(theme,"edit",token()).get("status"));
        long third=System.nanoTime()-start;
        assertEquals(daemon,service.state(service.config.get(theme,"edit")).get("runtime"));
        assertEquals(container,LocalRuntime.command(List.of("docker","inspect","--format","{{.Id}}","themenintegration-lab-gretl-1")).trim());
        Path evidence=Files.createDirectories(workspace.resolve(".netl/acceptance"));
        Json.write(evidence.resolve("daemon-reuse.json"),Map.of("container",container,"runtime",daemon,"coldMs",first/1_000_000,"warmMs",second/1_000_000,"warmRecreateMs",third/1_000_000));
    }
    @Test void timeoutTerminatesDatabaseWorkAndNextJobCanRun() throws Exception {
        versioned();
        Files.writeString(directory.resolve("sleep.sql"), "SELECT pg_sleep(30); CREATE TABLE ${dbSchema}.must_not_exist(value text);");
        entries.getFirst().put("sqlFiles",Map.of("postscript","sleep.sql")); write();
        var shortRun = new SchemaService(workspace,Duration.ofSeconds(8));
        var result=shortRun.call("create",theme,"edit");
        assertEquals("RUNNER_TIMEOUT",result.get("code"),result.toString());
        try(var c=service.runtime.connect("edit");var st=c.createStatement()) {
            try(var r=st.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE application_name='netl-schema-runner'")) { r.next(); assertEquals(0,r.getInt(1)); }
            try(var r=st.executeQuery("SELECT to_regclass('"+edit+"_v1.must_not_exist')")) { r.next(); assertNull(r.getObject(1)); }
        }
        assertEquals("INCOMPLETE",service.call("inspect",theme,"edit").get("status"));
        assertEquals("CREATED",service.call("create",theme,"pub").get("status"));
    }
    @Test void createsBothInspectsAndRepeatsWithoutChanges() throws Exception {
        for (String db:List.of("edit","pub")) {
            assertEquals("MISSING",service.call("inspect",theme,db).get("status"));
            assertEquals("READY",service.call("plan",theme,db).get("status"));
            var created=service.call("create",theme,db); assertEquals("CREATED",created.get("status"),created.toString());
            var inspected=service.call("inspect",theme,db); assertEquals("MATCHING",inspected.get("status"));
            Map<?,?> structure=(Map<?,?>)inspected.get("structure");
            String table=db.equals("edit")?"standorte_standort":"standort";
            assertTrue(((List<?>)structure.get("tables")).contains(Map.of("name",table)));
            Map<?,?> geom=(Map<?,?>)((List<?>)structure.get("geometries")).getFirst();
            assertEquals(2056,geom.get("srid")); assertEquals("POINT",geom.get("type"));
            var first=Files.readString(workspace.resolve(".netl/state/"+db+"-"+(db.equals("edit")?edit:pub)+".json"));
            assertEquals("ALREADY_PRESENT",service.call("create",theme,db).get("status"));
            assertEquals(first,Files.readString(workspace.resolve(".netl/state/"+db+"-"+(db.equals("edit")?edit:pub)+".json")));
        }
    }
    @Test void usesLocalModelAndDetectsFileAndDatabaseDrift() throws Exception {
        Path model=directory.resolve("Lab_Standorte_Edit.ili");
        String original=Files.readString(model);
        Files.writeString(model,original.replace("Kennung :", "LocalOnly : TEXT*12;\n      Kennung :"));
        assertEquals("CREATED",service.call("create",theme,"edit").get("status"));
        var structure=(Map<?,?>)service.call("inspect",theme,"edit").get("structure");
        assertTrue(((List<?>)structure.get("columns")).stream().anyMatch(c->"localonly".equals(((Map<?,?>)c).get("column_name"))));
        String imported=Files.readString(model);
        Files.writeString(model,imported+"\n!! modified\n");
        assertEquals("DRIFTED",service.call("inspect",theme,"edit").get("status"));
        assertEquals("BLOCKED",service.call("create",theme,"edit").get("status"));
        Files.writeString(model,imported);
        try(var c=service.runtime.connect("edit");var st=c.createStatement()) { st.execute("ALTER TABLE "+edit+".standorte_standort ADD COLUMN unexpected text"); }
        assertEquals("DRIFTED",service.call("inspect",theme,"edit").get("status"));
    }
    @Test void foreignSchemaIsUntouchedAndConcurrentCreateIsBusy() throws Exception {
        try(var c=service.runtime.connect("edit");var st=c.createStatement()) {
            assertTrue(Database.lock(c,edit));
            assertEquals("BUSY",service.call("create",theme,"edit").get("code"));
            st.execute("CREATE SCHEMA "+edit);
        }
        assertEquals("UNMANAGED",service.call("inspect",theme,"edit").get("status"));
        assertEquals("BLOCKED",service.call("create",theme,"edit").get("status"));
    }
    @Test void invalidModelRecordsFailureAndDoesNotRetry() throws Exception {
        Files.writeString(directory.resolve("Lab_Standorte_Edit.ili"),"INTERLIS 2.3; invalid model");
        var result=service.call("create",theme,"edit");
        assertEquals("RUNNER_FAILED",result.get("code"),result.toString());
        assertTrue(Files.exists(Path.of((String)result.get("logPath"))));
        assertEquals("INCOMPLETE",service.call("inspect",theme,"edit").get("status"));
        assertEquals("BLOCKED",service.call("create",theme,"edit").get("status"));
    }
    @Test void timeoutIsRecordedAndCannotBeRetriedAutomatically() throws Exception {
        var shortRun=new SchemaService(workspace,Duration.ofMillis(1));
        var result=shortRun.call("create",theme,"edit");
        assertEquals("RUNNER_TIMEOUT",result.get("code"),result.toString());
        assertEquals("INCOMPLETE",service.call("inspect",theme,"edit").get("status"));
        assertEquals("BLOCKED",service.call("create",theme,"edit").get("status"));
    }
    String token() {
        var plan = service.plan(theme, "edit", "recreate");
        assertEquals("READY", plan.get("status"), plan.toString());
        return (String) plan.get("planToken");
    }
    @Test void recreateChangesStructureRemovesDataAndConsumesToken() throws Exception {
        assertEquals("CREATED", service.call("create",theme,"edit").get("status"));
        try (var c=service.runtime.connect("edit"); var st=c.createStatement()) {
            st.execute("CREATE TABLE " + edit + ".old_data(value text)");
            st.execute("INSERT INTO " + edit + ".old_data VALUES ('synthetic')");
        }
        entries.getFirst().put("overrides", Map.of("nameByTopic", false));
        write();
        String token = token();
        String supersededToken = token();
        assertEquals("BLOCKED", service.call("create",theme,"edit").get("status"));
        var rebuilt = service.recreate(theme,"edit",token);
        assertEquals("RECREATED", rebuilt.get("status"), rebuilt.toString());
        assertEquals("MATCHING", ((Map<?,?>) rebuilt.get("inspection")).get("status"));
        try (var c=service.runtime.connect("edit")) {
            var tables = (List<?>) Database.snapshot(c,edit).get("tables");
            assertTrue(tables.contains(Map.of("name","standort")));
            assertFalse(tables.contains(Map.of("name","old_data")));
        }
        assertEquals("INVALID_PLAN", service.recreate(theme,"edit",token).get("code"));
        assertEquals("STALE_PLAN", service.recreate(theme,"edit",supersededToken).get("code"));
    }
    @Test void recreateBlocksUnmanagedStaleBusyAndForeignAssignment() throws Exception {
        assertEquals("BLOCKED", service.plan(theme,"edit","recreate").get("status"));
        assertEquals("CREATED", service.call("create",theme,"edit").get("status"));
        String token = token();
        Files.writeString(directory.resolve("Lab_Standorte_Edit.ili"), "\n!! drift", StandardOpenOption.APPEND);
        assertEquals("STALE_PLAN", service.recreate(theme,"edit",token).get("code"));
        try (var c=service.runtime.connect("edit")) {
            assertTrue(Database.exists(c,edit));
            assertTrue(Database.lock(c,edit));
            assertEquals("BUSY", service.recreate(theme,"edit",token).get("code"));
        }
        entries.getFirst().put("ident","other"); write();
        assertEquals("BLOCKED", service.plan(theme,"other","recreate").get("status"));
    }
    @Test void externalCascadeIsRolledBackAndNoGuardRemains() throws Exception {
        assertEquals("CREATED", service.call("create",theme,"edit").get("status"));
        String token = token();
        String foreign = edit + "_outside";
        try (var c=service.runtime.connect("edit"); var st=c.createStatement()) {
            st.execute("CREATE SCHEMA " + foreign);
            try {
                st.execute("CREATE VIEW " + foreign + ".dependent AS SELECT * FROM " + edit + ".standorte_standort");
                assertEquals("EXTERNAL_DEPENDENCY", service.recreate(theme,"edit",token).get("code"));
                assertEquals("BLOCKED", service.plan(theme,"edit","recreate").get("status"));
                assertTrue(Database.exists(c,edit));
                try (var r=st.executeQuery("SELECT count(*) FROM pg_event_trigger WHERE evtname LIKE 'netl_guard_%'")) {
                    r.next(); assertEquals(0,r.getInt(1));
                }
                st.executeQuery("SELECT * FROM " + foreign + ".dependent").close();
            } finally { st.execute("DROP SCHEMA " + foreign + " CASCADE"); }
        }
    }
    @Test void failedRecreateIsIncompleteAndExplicitNewPlanRecovers() throws Exception {
        assertEquals("CREATED", service.call("create",theme,"edit").get("status"));
        Path model=directory.resolve("Lab_Standorte_Edit.ili");
        String original=Files.readString(model);
        Files.writeString(model,"INTERLIS 2.3; invalid");
        var failed=service.recreate(theme,"edit",token());
        assertEquals("RUNNER_FAILED",failed.get("code"),failed.toString());
        assertTrue(Files.exists(Path.of((String) failed.get("logPath"))));
        assertEquals("INCOMPLETE",service.call("inspect",theme,"edit").get("status"));
        assertEquals("BLOCKED",service.call("create",theme,"edit").get("status"));
        Files.writeString(model,original);
        assertEquals("RECREATED",service.recreate(theme,"edit",token()).get("status"));
    }

    @Test void recreateUnavailableAndTimeoutDoNotRetry() throws Exception {
        assertEquals("CREATED", service.call("create",theme,"edit").get("status"));
        String token = token();
        var offline = new SchemaService(service.config, new LocalRuntime(workspace) {
            @Override void runnerReady() {}
            @Override Connection connect(String db) { throw new Failure("DB_UNAVAILABLE", "offline"); }
        });
        assertEquals("BLOCKED", offline.plan(theme,"edit","recreate").get("status"));
        assertEquals("DB_UNAVAILABLE", offline.recreate(theme,"edit",token).get("code"));
        assertEquals("MATCHING", service.call("inspect",theme,"edit").get("status"));
        var shortRun = new SchemaService(workspace, Duration.ofMillis(1));
        var result = shortRun.recreate(theme,"edit",token);
        assertEquals("RUNNER_TIMEOUT",result.get("code"),result.toString());
        assertEquals("INCOMPLETE",service.call("inspect",theme,"edit").get("status"));
        assertEquals("INVALID_PLAN",service.recreate(theme,"edit",token).get("code"));
    }
    @Test void guardRejectsExternalForeignKeyAndHandlesInternalToast() throws Exception {
        String foreign = edit + "_outside";
        try (var c=service.runtime.connect("edit"); var st=c.createStatement()) {
            st.execute("CREATE SCHEMA " + edit);
            st.execute("CREATE SCHEMA " + foreign);
            try {
                st.execute("CREATE TABLE " + edit + ".parent(id int PRIMARY KEY, value text)");
                st.execute("CREATE TABLE " + foreign + ".child(id int REFERENCES " + edit + ".parent)");
                var error = assertThrows(Failure.class, () -> Database.guardedDrop(c,edit,true));
                assertEquals("EXTERNAL_DEPENDENCY",error.code);
                assertTrue(Database.exists(c,edit));
                assertEquals("BLOCKED",service.plan(theme,"edit","recreate").get("status"));
                st.execute("DROP TABLE " + foreign + ".child");
                Database.guardedDrop(c,edit,false);
                assertTrue(Database.exists(c,edit));
                Database.guardedDrop(c,edit,true);
                assertFalse(Database.exists(c,edit));
            } finally { st.execute("DROP SCHEMA " + foreign + " CASCADE"); }
        }
    }

}
