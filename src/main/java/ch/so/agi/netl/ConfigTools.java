package ch.so.agi.netl;

import java.util.*;
import org.springframework.context.annotation.Bean;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;

// Use the versioned manifest schema directly: generic Map parameter schemas do not
// describe nested numbers/booleans reliably to agent clients.
@org.springframework.context.annotation.Configuration
public class ConfigTools {
    @Bean
    List<SyncToolSpecification> configurationTools(SchemaService schemaService) throws Exception {
        var service = new ConfigService(schemaService.config.workspace);
        var tools = new ArrayList<SyncToolSpecification>();
        for (String operation : List.of("context", "validate", "save")) {
            var properties = new LinkedHashMap<String,Object>();
            properties.put("theme", Map.of("type", "string", "description", "Theme identifier, e.g. demo/standorte"));
            var required = new ArrayList<String>(List.of("theme"));
            if (!operation.equals("context")) {
                // Advertise a typed superset. Some model providers lose numeric types
                // inside oneOf and repeatedly send strings. Configuration.resolve still
                // strictly enforces each version's exact fields and required names.
                var manifestSchema = new LinkedHashMap<>(Json.object(Json.resource("schemas-v2.schema.json")));
                manifestSchema.remove("$schema"); manifestSchema.remove("$id");
                @SuppressWarnings("unchecked") var fields = (Map<String,Object>)manifestSchema.get("properties");
                fields.put("formatVersion",Map.of("type","integer","enum",List.of(1,2),"description","JSON integer: 2 for baseName/schemaVersion, 1 for legacy name"));
                @SuppressWarnings("unchecked") var items = (Map<String,Object>)((Map<?,?>)fields.get("schemas")).get("items");
                @SuppressWarnings("unchecked") var entryFields = (Map<String,Object>)items.get("properties");
                entryFields.put("name",Map.of("type","string","description","Complete physical name; formatVersion 1 only"));
                items.put("required",List.of("ident","database","models","modelFiles","profile"));
                properties.put("manifest", manifestSchema);
                required.add("manifest");
            }
            if (operation.equals("save")) {
                properties.put("expectedRevision", Map.of("type", "string", "description", "Revision from config_context; ABSENT for new manifest"));
                required.add("expectedRevision");
            }
            String description = switch (operation) {
                case "context" -> "Read manifest, revision, local model paths, profiles and validation rules without database access.";
                case "validate" -> "Validate complete manifest offline. Does not compile INTERLIS or prove dependency completeness.";
                default -> "Validate and atomically save schemas.json on explicit configuration creation/edit request. Never changes a database.";
            };
            var tool = McpSchema.Tool.builder().name("config_" + operation).description(description)
                .inputSchema(Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false))
                .annotations(new McpSchema.ToolAnnotations(null, !operation.equals("save"), false, false, false, false)).build();
            tools.add(new SyncToolSpecification(tool, (exchange, request) -> {
                Map<String,Object> result;
                try {
                    var arguments = request.arguments();
                    Configuration.keys(arguments, new HashSet<>(required));
                    String theme = Configuration.string(arguments, "theme");
                    if (operation.equals("context")) result = service.context(theme);
                    else {
                        Configuration.require(arguments.get("manifest") instanceof Map<?,?>, "manifest must be an object");
                        // Normalize transport number representations through the same JSON parser as the CLI.
                        var manifest = Json.object(Json.bytes(arguments.get("manifest")));
                        result = operation.equals("validate") ? service.validate(theme, manifest) :
                            service.save(theme, manifest, Configuration.string(arguments, "expectedRevision"));
                    }
                } catch (Exception e) { result = SchemaService.error(e); }
                try {
                    return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(Json.MAPPER.writeValueAsString(result))), false, result, null);
                } catch (Exception e) { throw new IllegalStateException(e); }
            }));
        }
        return tools;
    }
}
