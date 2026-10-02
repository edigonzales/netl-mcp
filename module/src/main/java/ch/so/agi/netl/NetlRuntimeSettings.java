package ch.so.agi.netl;

import java.nio.file.Path;
import java.util.Map;

/** One fixed workspace and dedicated Docker project per server process. */
public record NetlRuntimeSettings(Path workspace, Path hostWorkspace, String dockerProject, boolean container) {
    public NetlRuntimeSettings {
        workspace = workspace.toAbsolutePath().normalize();
        hostWorkspace = hostWorkspace.toAbsolutePath().normalize();
        if (!dockerProject.matches("[a-z0-9][a-z0-9_-]*"))
            throw new IllegalArgumentException("Invalid NETL Docker project");
    }
    public static NetlRuntimeSettings fromEnvironment(Path workspace) {
        return from(workspace, System.getenv());
    }
    static NetlRuntimeSettings from(Path workspace, Map<String,String> environment) {
        String mode = environment.getOrDefault("NETL_RUNTIME_MODE", "host");
        if (!mode.equals("host") && !mode.equals("container"))
            throw new IllegalArgumentException("NETL_RUNTIME_MODE must be host or container");
        String host = environment.get("NETL_HOST_WORKSPACE");
        if (mode.equals("container") && (host == null || host.isBlank()))
            throw new IllegalArgumentException("NETL_HOST_WORKSPACE must name the absolute host-side workspace in container mode");
        if (mode.equals("container") && !Path.of(host).isAbsolute())
            throw new IllegalArgumentException("NETL_HOST_WORKSPACE must be absolute");
        return new NetlRuntimeSettings(workspace, host == null ? workspace : Path.of(host),
            environment.getOrDefault("NETL_DOCKER_PROJECT", "themenintegration-lab"), mode.equals("container"));
    }
    public String containerName(String service) { return dockerProject + "-" + service + "-1"; }
    public String jdbcUrl(String database, String applicationName) {
        if (!database.equals("edit") && !database.equals("pub")) throw new IllegalArgumentException("Only edit/pub supported");
        String host = container ? database + "-db" : "127.0.0.1";
        int port = container ? 5432 : database.equals("edit") ? 55431 : 55432;
        return "jdbc:postgresql://" + host + ":" + port + "/" + database
            + "?connectTimeout=3&socketTimeout=15&ApplicationName=" + applicationName;
    }
    public Path runnerHostMount() { return hostWorkspace.resolve(".netl").normalize(); }
}
