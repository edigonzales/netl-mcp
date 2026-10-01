package ch.so.agi.netl;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class ModelRepositoryIntegrationTest {
    Path workspace,directory; String theme,name; SchemaService service;
    SyntheticModelRepository repository; Map<String,Object> entry;
    @BeforeEach void setup() throws Exception {
        workspace=Path.of(System.getProperty("netl.workspace")).toRealPath();
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,12);
        theme="tests/m"+suffix; name="netl_it_"+suffix+"_edit";
        directory=Files.createDirectories(workspace.resolve("themes/"+theme));
        Files.writeString(directory.resolve("Main.ili"),"""
            INTERLIS 2.3;
            MODEL Remote_Main (en) AT "https://example.invalid" VERSION "2026-10-01" =
            IMPORTS RemoteTypes;
            TOPIC T =
              CLASS Item =
                value : RemoteTypes.Value;
              END Item;
            END T;
            END Remote_Main.
            """);
        repository=new SyntheticModelRepository();
        entry=new LinkedHashMap<>(Map.of("ident","edit","baseName",name,"database","edit","models",List.of("Remote_Main"),"modelFiles",List.of("Main.ili"),"modelRepositories",List.of(repository.url()),"profile","lab-edit-v1"));
        write(); service=new SchemaService(workspace);
    }
    void write() throws Exception { Json.write(directory.resolve("schemas.json"),Map.of("formatVersion",2,"schemas",List.of(entry))); }
    Map<String,Object> create() { var result=service.call("create",theme,"edit"); assertEquals("CREATED",result.get("status"),result.toString());return result; }
    @SuppressWarnings("unchecked") Map<String,Object> models(Map<String,Object> plan) { return (Map<String,Object>)plan.get("models"); }
    String token(Map<String,Object> plan) { assertEquals("READY",plan.get("status"),plan.toString());return (String)plan.get("planToken"); }
    @AfterEach void cleanup() throws Exception {
        repository.close();
        try(var c=service.runtime.connect("edit")) {
            var spec=service.config.get(theme,"edit");
            if(Database.exists(c,name)) Database.guardedDrop(c,name,service.managedRoles(spec,c),true);
            Files.deleteIfExists(service.statePath(spec));
        }
        try(var paths=Files.walk(directory)) { for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p); }
    }
    @Test void transitiveModelsAreArchivedAndInspectionDoesNotPoll() throws Exception {
        create(); var record=service.state(service.config.get(theme,"edit"));
        var files=ModelSnapshot.files(workspace,record);
        assertEquals(Set.of("Main.ili","RemoteBase.ili","RemoteTypes.ili"),files.keySet());
        try(var c=service.runtime.connect("edit")) { ModelSnapshot.verifyDatabase(ModelSnapshot.database(c,name),files); }
        int requests=repository.requests.get(); repository.change(200);
        assertEquals("MATCHING",service.call("inspect",theme,"edit").get("status"));
        assertEquals(requests,repository.requests.get());
        repository.close();
        var plan=service.plan(theme,"edit","recreate");
        assertEquals(record.get("resolvedModelsHash"),models(plan).get("resolvedModelsHash"));
        assertEquals("RECREATED",service.recreate(theme,"edit",token(plan)).get("status"));
    }
    @Test void refreshFreezesNewBytesAndFailedResolutionPreservesSchema() throws Exception {
        create(); var before=service.state(service.config.get(theme,"edit")); repository.change(200);
        var plan=service.plan(theme,"edit","recreate",true);
        assertNotEquals(before.get("resolvedModelsHash"),models(plan).get("resolvedModelsHash"));
        repository.change(300); repository.close();
        assertEquals("RECREATED",service.recreate(theme,"edit",token(plan)).get("status"));
        assertEquals(models(plan).get("resolvedModelsHash"),service.state(service.config.get(theme,"edit")).get("resolvedModelsHash"));
        var failed=service.plan(theme,"edit","recreate",true);
        assertEquals("BLOCKED",failed.get("status"));
        assertEquals("MODEL_REPOSITORY_UNAVAILABLE",((Map<?,?>)failed.get("problem")).get("code"));
        assertEquals("MATCHING",service.call("inspect",theme,"edit").get("status"));
    }
    @Test void modelMetadataAndSnapshotChangesInvalidateEvidence() throws Exception {
        create();
        try(var c=service.runtime.connect("edit");var st=c.createStatement()) {
            st.execute("UPDATE "+name+".t_ili2db_model SET importdate=now()");
            assertEquals("MATCHING",service.call("inspect",theme,"edit").get("status"));
            st.execute("UPDATE "+name+".t_ili2db_model SET content=content||E'\\n!! changed' WHERE filename='RemoteBase.ili'");
        }
        assertEquals("DRIFTED",service.call("inspect",theme,"edit").get("status"));
        var plan=service.plan(theme,"edit","recreate"); String token=token(plan);
        Path snapshot=Path.of((String)models(plan).get("modelSnapshot"));
        Files.writeString(snapshot.resolve("models/RemoteBase.ili"),"tampered");
        assertEquals("MODEL_EVIDENCE_INVALID",service.recreate(theme,"edit",token).get("code"));
        try(var c=service.runtime.connect("edit");var st=c.createStatement()) {
            assertTrue(Database.exists(c,name));
            st.execute("DROP TABLE "+name+".t_ili2db_model");
        }
        assertEquals("DRIFTED",service.call("inspect",theme,"edit").get("status"));
    }
    @Test void localDependencyWinsAndMissingModelDiffersFromUnavailable() throws Exception {
        Files.writeString(directory.resolve("RemoteTypes.ili"),"INTERLIS 2.3; TYPE MODEL RemoteTypes (en) AT \"https://example.invalid\" VERSION \"2026-10-01\" = DOMAIN Value = 0 .. 9; END RemoteTypes.");
        entry.put("modelFiles",List.of("Main.ili","RemoteTypes.ili"));write();
        create();assertEquals(0,repository.requests.get());
        Files.delete(directory.resolve("RemoteTypes.ili"));entry.put("modelFiles",List.of("Main.ili"));write();
        var reused=service.plan(theme,"edit","recreate");
        assertEquals("RECREATED",service.recreate(theme,"edit",token(reused)).get("status"));
        assertEquals(0,repository.requests.get());
        repository.files.remove("/RemoteBase.ili");
        var plan=service.plan(theme,"edit","recreate",true);
        assertEquals("BLOCKED",plan.get("status"));
        assertEquals("MODEL_NOT_FOUND",((Map<?,?>)plan.get("problem")).get("code"));
        try(var c=service.runtime.connect("edit")) { assertTrue(Database.exists(c,name)); }
    }
    @Test void collisionsAndNonLocalRootAreRejectedBeforeSchemaCreation() throws Exception {
        String index=repository.files.get("/ilimodels.xml");
        repository.files.put("/Shared.ili",repository.files.get("/RemoteTypes.ili"));
        repository.files.put("/second/Shared.ili",repository.files.get("/RemoteBase.ili"));
        repository.files.put("/ilimodels.xml",index.replaceAll("(?s)<IliRepository20.RepositoryIndex.ModelMetadata TID=\"1\">.*?</IliRepository20.RepositoryIndex.ModelMetadata>","").replace("RemoteTypes.ili","Shared.ili"));
        repository.files.put("/second/ilimodels.xml",index.replaceAll("(?s)<IliRepository20.RepositoryIndex.ModelMetadata TID=\"2\">.*?</IliRepository20.RepositoryIndex.ModelMetadata>","").replace("RemoteBase.ili","Shared.ili"));
        entry.put("modelRepositories",List.of(repository.url(),repository.url()+"second/"));write();
        var failed=service.call("create",theme,"edit");
        assertEquals("MODEL_PREPARATION_FAILED",failed.get("code"),failed.toString());
        assertTrue(String.valueOf(failed.get("message")).contains("Duplicate model basename"));
        try(var c=service.runtime.connect("edit")) { assertFalse(Database.exists(c,name)); }
        Files.writeString(directory.resolve("Main.ili"),"INTERLIS 2.3; TYPE MODEL Other (en) AT \"https://example.invalid\" VERSION \"2026-10-01\" = DOMAIN Text = TEXT*10; END Other.");
        var plan=service.plan(theme,"edit","recreate",true);
        assertEquals("BLOCKED",plan.get("status"));
        assertTrue(String.valueOf(((Map<?,?>)plan.get("problem")).get("message")).contains("not defined locally"));
    }

}
