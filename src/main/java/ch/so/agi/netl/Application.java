package ch.so.agi.netl;

import java.nio.file.*;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class Application {
    static Path workspace;
    @Bean io.modelcontextprotocol.server.transport.StdioServerTransportProvider stdioTransport() {
        return new SerialStdioTransport(io.modelcontextprotocol.json.McpJsonDefaults.getMapper());
    }
    @Bean SchemaService schemaService() throws Exception { return new SchemaService(workspace); }
    public static void main(String[] args) throws Exception {
        var arguments=new ArrayList<>(List.of(args));
        String location=System.getenv().getOrDefault("NETL_WORKSPACE", ".");
        int w=arguments.indexOf("--workspace");
        if (w>=0) {
            if (w+1>=arguments.size()) { usage(); return; }
            location=arguments.remove(w+1); arguments.remove(w);
        }
        workspace=Path.of(location);
        if (arguments.equals(List.of("mcp"))) { SpringApplication.run(Application.class); return; }
        boolean json=arguments.remove("--json");
        if (arguments.size()<3 || !arguments.getFirst().equals("schema") ||
            !(arguments.get(1).equals("list") ? arguments.size()==3 : arguments.size()==4)) { usage(); return; }
        Map<String,Object> result;
        try { result=new SchemaService(workspace).call(arguments.get(1),arguments.get(2),arguments.size()==4?arguments.get(3):null); }
        catch (Exception e) { result=SchemaService.error(e); }
        System.out.println(json ? Json.MAPPER.writeValueAsString(result) : Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        if (Set.of("ERROR","BLOCKED","INCOMPLETE","DRIFTED","UNMANAGED").contains(result.get("status"))) System.exit(1);
    }
    static void usage() {
        System.err.println("Usage: netl [--workspace PATH] mcp | schema list THEME [--json] | schema plan|create|inspect THEME SCHEMA [--json]");
        System.exit(2);
    }
}
