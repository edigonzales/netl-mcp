package ch.so.agi.netl;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigServiceTest {
    @TempDir Path root;
    ConfigService service;
    Path directory;
    Map<String,Object> entry, manifest;
    @BeforeEach void setup() throws Exception {
        directory = Files.createDirectories(root.resolve("themes/demo/test"));
        Files.writeString(directory.resolve("Test.ili"), "local synthetic model");
        entry = new LinkedHashMap<>(Map.of("ident", "edit", "name", "test_edit", "database", "edit",
            "models", List.of("Test"), "modelFiles", List.of("Test.ili"), "profile", "lab-edit-v1"));
        manifest = Map.of("formatVersion", 1, "schemas", List.of(entry));
        service = new ConfigService(root);
    }
    @Test void offlineContextValidateSaveAndAudit() throws Exception {
        assertEquals("ABSENT", service.context("demo/test").get("revision"));
        assertEquals(List.of("Test.ili"), service.context("demo/test").get("modelFiles"));
        var validation = service.validate("demo/test", manifest);
        assertEquals("VALID", validation.get("status"));
        assertFalse(Files.exists(directory.resolve("schemas.json")));
        var saved = service.save("demo/test", manifest, "ABSENT");
        assertEquals("SAVED", saved.get("status"));
        assertEquals(service.config.resolve("demo/test", manifest).getFirst().fingerprint(),
            service.config.list("demo/test").getFirst().fingerprint());
        String revision = (String) saved.get("revision");
        entry.put("overrides", Map.of("createFk", false));
        var updated = service.save("demo/test", manifest, revision);
        assertNotEquals(revision, updated.get("revision"));
        assertTrue(Files.exists(Path.of((String) updated.get("auditPath")).resolve("before.json")));
        Failure conflict = assertThrows(Failure.class, () -> service.save("demo/test", manifest, revision));
        assertEquals("CONFIG_CONFLICT", conflict.code);
    }
    @Test void versionChangesAndExplicitFormatConversion() throws Exception {
        entry.put("name","test_edit_v1");
        service.save("demo/test",manifest,"ABSENT");
        entry.remove("name"); entry.put("baseName","test_edit"); entry.put("schemaVersion",1);
        manifest = Map.of("formatVersion",2,"schemas",List.of(entry));
        String revision = (String)service.context("demo/test").get("revision");
        service.save("demo/test",manifest,revision);
        entry.put("schemaVersion",2);
        assertEquals("VALID",service.validate("demo/test",manifest).get("status"));
        entry.put("baseName","other"); assertThrows(Failure.class,()->service.validate("demo/test",manifest));
    }
    @Test void rejectsIdentityChangesAndRemoval() throws Exception {
        service.save("demo/test", manifest, "ABSENT");
        for (String key : List.of("ident", "database", "name")) {
            Object original = entry.get(key);
            entry.put(key, key.equals("database") ? "pub" : "other");
            assertThrows(Failure.class, () -> service.validate("demo/test", manifest));
            entry.put(key, original);
        }
        var extra = new LinkedHashMap<>(entry);
        extra.put("ident", "pub"); extra.put("database", "pub");
        assertEquals("VALID", service.validate("demo/test", Map.of("formatVersion", 1, "schemas", List.of(entry, extra))).get("status"));
    }
    @Test void rejectsInvalidFilesOptionsAndPaths(@TempDir Path external) throws Exception {
        entry.put("overrides", Map.of("unknown", true));
        assertThrows(Failure.class, () -> service.save("demo/test", manifest, "ABSENT"));
        assertFalse(Files.exists(directory.resolve("schemas.json")));
        entry.remove("overrides");
        for (String path : List.of("absent.ili", "../Test.ili", "sub/../Test.ili")) {
            entry.put("modelFiles", List.of(path));
            assertThrows(Exception.class, () -> service.validate("demo/test", manifest));
        }
        Files.writeString(external.resolve("Test.ili"), "external");
        Files.createSymbolicLink(directory.resolve("Escape.ili"), external.resolve("Test.ili"));
        entry.put("modelFiles", List.of("Escape.ili"));
        assertThrows(Failure.class, () -> service.validate("demo/test", manifest));
        Files.delete(directory.resolve("Escape.ili"));
        Files.createSymbolicLink(directory.resolve("schemas.json"), external.resolve("schemas.json"));
        assertThrows(Failure.class, () -> service.save("demo/test", manifest, "ABSENT"));
    }
    @Test void rejectsConcurrentWriter() throws Exception {
        Path locks = Files.createDirectories(root.resolve(".netl/config"));
        try (var channel = FileChannel.open(locks.resolve("demo-test.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            assertEquals("BUSY", assertThrows(Failure.class, () -> service.save("demo/test", manifest, "ABSENT")).code);
        }
    }
    @Test void profileKeysAndTypesMatchPublishedSchema() throws Exception {
        Map<?,?> schema = Json.object(Json.resource("schemas-v1.schema.json"));
        Map<?,?> properties = (Map<?,?>) schema.get("properties");
        Map<?,?> schemas = (Map<?,?>) properties.get("schemas");
        Map<?,?> fields = (Map<?,?>) ((Map<?,?>) schemas.get("items")).get("properties");
        assertEquals(service.config.profiles.keySet(), new HashSet<>((List<?>) ((Map<?,?>) fields.get("profile")).get("enum")));
        Map<?,?> options = (Map<?,?>) ((Map<?,?>) fields.get("overrides")).get("properties");
        for (Object profile : service.config.profiles.values()) {
            Map<?,?> values = (Map<?,?>) profile;
            assertEquals(options.keySet(), values.keySet());
            for (Object key : values.keySet()) {
                String type = (String) ((Map<?,?>) options.get(key)).get("type");
                assertEquals(type.equals("boolean") ? Boolean.class : String.class, values.get(key).getClass());
            }
        }
    }
}
