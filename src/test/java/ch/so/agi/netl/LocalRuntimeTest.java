package ch.so.agi.netl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LocalRuntimeTest {
    @TempDir Path root;
    @Test void workspaceLockIsExclusiveAndReleased() throws Exception {
        var first = new LocalRuntime(root);
        var second = new LocalRuntime(root);
        try (var lock = first.lock()) {
            assertTrue(Files.exists(root.resolve(".netl/runner-active.json")));
            assertEquals("BUSY", assertThrows(Failure.class,second::lock).code);
        }
        assertFalse(Files.exists(root.resolve(".netl/runner-active.json")));
        try(var lock = second.lock()) { assertNotNull(lock); }
    }
    @Test void interruptedHostOrUnconfirmedTerminationBlocksNewJobs() throws Exception {
        Files.createDirectories(root.resolve(".netl"));
        for (String file : new String[]{"runner-active.json", "runner-recovery.json"}) {
            Path marker = root.resolve(".netl/" + file);
            Json.write(marker, Map.of("reason","synthetic interrupted process"));
            assertEquals("RUNNER_RECOVERY_REQUIRED",assertThrows(Failure.class,()->new LocalRuntime(root).lock()).code);
            assertTrue(Files.exists(marker));
            Files.delete(marker);
            try(var lock = new LocalRuntime(root).lock()) { assertNotNull(lock); }
        }
    }
}
