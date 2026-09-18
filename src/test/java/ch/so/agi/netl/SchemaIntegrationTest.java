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
    void write() throws Exception { Json.write(directory.resolve("schemas.json"),Map.of("formatVersion",1,"schemas",entries)); }
    @AfterEach void cleanup() throws Exception {
        for (String db:List.of("edit","pub")) {
            String name=db.equals("edit")?edit:pub;
            if (name==null || !name.matches("netl_it_[a-f0-9]{12}_(edit|pub)")) continue;
            try (var c=service.runtime.connect(db); var st=c.createStatement()) { st.execute("DROP SCHEMA IF EXISTS \""+name+"\" CASCADE"); }
            Files.deleteIfExists(workspace.resolve(".netl/state/"+db+"-"+name+".json"));
        }
        try (var paths=Files.walk(directory)) { for (Path p:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
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
        var shortRun=new SchemaService(workspace,Duration.ofSeconds(1));
        var result=shortRun.call("create",theme,"edit");
        assertEquals("RUNNER_TIMEOUT",result.get("code"),result.toString());
        assertEquals("INCOMPLETE",service.call("inspect",theme,"edit").get("status"));
        assertEquals("BLOCKED",service.call("create",theme,"edit").get("status"));
    }
}
