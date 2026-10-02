package ch.so.agi.netl;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class NetlRuntimeSettingsTest {
    @Test void hostDefaultsPreserveDedicatedLab() {
        var settings = NetlRuntimeSettings.from(Path.of("."), Map.of());
        assertEquals("themenintegration-lab-gretl-1", settings.containerName("gretl"));
        assertTrue(settings.jdbcUrl("edit", "probe").startsWith("jdbc:postgresql://127.0.0.1:55431/edit?"));
        assertTrue(settings.jdbcUrl("pub", "probe").startsWith("jdbc:postgresql://127.0.0.1:55432/pub?"));
    }
    @Test void containerPathsAndConnectionsUseOneConfiguration() {
        var settings = NetlRuntimeSettings.from(Path.of("/workspace"), Map.of(
            "NETL_RUNTIME_MODE", "container", "NETL_HOST_WORKSPACE", "/host/lab", "NETL_DOCKER_PROJECT", "netl-test"));
        assertEquals(Path.of("/host/lab/.netl"), settings.runnerHostMount());
        assertEquals("netl-test-gretl-1", settings.containerName("gretl"));
        assertTrue(settings.jdbcUrl("pub", "netl-job-user").startsWith("jdbc:postgresql://pub-db:5432/pub?"));
        assertTrue(settings.jdbcUrl("pub", "netl-job-user").contains("ApplicationName=netl-job-user"));
        assertThrows(IllegalArgumentException.class, () -> settings.jdbcUrl("production", "probe"));
    }
    @Test void rejectsAmbiguousHostMountAndProject() {
        assertThrows(IllegalArgumentException.class, () -> NetlRuntimeSettings.from(Path.of("."), Map.of("NETL_RUNTIME_MODE", "container")));
        assertThrows(IllegalArgumentException.class, () -> NetlRuntimeSettings.from(Path.of("."), Map.of("NETL_RUNTIME_MODE", "container", "NETL_HOST_WORKSPACE", "relative")));
        assertThrows(IllegalArgumentException.class, () -> NetlRuntimeSettings.from(Path.of("."), Map.of("NETL_DOCKER_PROJECT", "../another")));
    }
}
