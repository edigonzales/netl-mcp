package ch.so.agi.netl;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class JsonCompatibilityTest {
    @Test void canonicalBytesAndFingerprintsRemainStable() throws Exception {
        var value = Map.of("z", List.of(1, 2.5, "ä"), "a", Map.of("version", 2, "enabled", true));
        String expected = "{\"a\":{\"enabled\":true,\"version\":2},\"z\":[1,2.5,\"ä\"]}";
        assertEquals(expected, new String(Json.bytes(value), StandardCharsets.UTF_8));
        assertEquals("9b0cbe107e382794524b1ecce916e2e29abf34635a6b3b65e0b00472c7445e69", Json.hash(value));
        assertEquals(Json.hash(value), Json.hash(Json.object(Json.bytes(value))));
    }
    @Test void persistedIntegerAndBooleanTypesAndDuplicateChecksRemainStrict() throws Exception {
        var value = Json.object("{\"formatVersion\":2,\"enabled\":false}".getBytes(StandardCharsets.UTF_8));
        assertInstanceOf(Integer.class, value.get("formatVersion"));
        assertInstanceOf(Boolean.class, value.get("enabled"));
        assertThrows(java.io.IOException.class, () -> Json.object("{\"version\":1,\"version\":2}".getBytes(StandardCharsets.UTF_8)));
    }
}
