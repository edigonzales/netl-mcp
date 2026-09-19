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
        Map<String,Object> result;
        try {
            if (arguments.size() < 3) { usage(); return; }
            String group = arguments.get(0), operation = arguments.get(1), theme = arguments.get(2);
            if (group.equals("config")) {
                var service = new ConfigService(workspace);
                if (operation.equals("context") && arguments.size() == 3) result = service.context(theme);
                else if (operation.equals("validate") && arguments.size() == 4)
                    result = service.validate(theme, Json.object(Files.readAllBytes(Path.of(arguments.get(3)))));
                else if (operation.equals("save") && arguments.size() == 5)
                    result = service.save(theme, Json.object(Files.readAllBytes(Path.of(arguments.get(3)))), arguments.get(4));
                else { usage(); return; }
            } else if (group.equals("schema")) {
                var service = new SchemaService(workspace);
                if (operation.equals("list") && arguments.size() == 3) result = service.call(operation,theme,null);
                else if (operation.equals("plan") && (arguments.size() == 4 || arguments.size() == 5))
                    result = service.plan(theme, arguments.get(3), arguments.size() == 5 ? arguments.get(4) : "create");
                else if (operation.equals("recreate") && arguments.size() == 5)
                    result = service.recreate(theme, arguments.get(3), arguments.get(4));
                else if (operation.equals("drop-previous") && arguments.size() == 5)
                    result = service.dropPrevious(theme, arguments.get(3), arguments.get(4));
                else if (Set.of("create", "inspect").contains(operation) && arguments.size() == 4)
                    result = service.call(operation, theme, arguments.get(3));
                else { usage(); return; }
            } else { usage(); return; }
        } catch (Exception e) { result=SchemaService.error(e); }
        System.out.println(json ? Json.MAPPER.writeValueAsString(result) : Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        if (Set.of("ERROR","BLOCKED","INCOMPLETE","DRIFTED","UNMANAGED").contains(result.get("status"))) System.exit(1);
    }
    static void usage() {
        System.err.println("Usage: netl [--workspace PATH] mcp | schema list THEME [--json] | schema plan THEME SCHEMA [create|recreate|drop-previous] [--json] | schema create|inspect THEME SCHEMA [--json] | schema recreate|drop-previous THEME SCHEMA TOKEN [--json] | config context THEME [--json] | config validate THEME FILE [--json] | config save THEME FILE REVISION [--json]");
        System.exit(2);
    }
}
