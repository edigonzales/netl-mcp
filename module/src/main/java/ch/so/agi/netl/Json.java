package ch.so.agi.netl;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

final class Json {
    static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    static Map<String,Object> object(byte[] bytes) throws IOException {
        return MAPPER.readValue(bytes, new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
    }
    static byte[] bytes(Object value) throws IOException { return MAPPER.writeValueAsBytes(value); }
    static String hash(Object value) throws Exception { return hashBytes(bytes(value)); }
    static String hashBytes(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    static byte[] resource(String name) throws IOException {
        try (var stream = Json.class.getResourceAsStream("/" + name)) {
            if (stream == null) throw new IOException("Missing resource " + name);
            return stream.readAllBytes();
        }
    }
    static void write(Path path, Object value) throws IOException {
        Path temp = Files.createTempFile(path.getParent(), "record-", ".tmp");
        try {
            Files.write(temp, bytes(value));
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
}
