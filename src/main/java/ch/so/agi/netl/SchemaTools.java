package ch.so.agi.netl;

import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class SchemaTools {
    private final SchemaService service;
    public SchemaTools(SchemaService service) { this.service=service; }
    @McpTool(name="schema_list",description="List configured schemas for a theme without connecting to the database.",annotations=@McpTool.McpAnnotations(readOnlyHint=true,destructiveHint=false,openWorldHint=false))
    public Map<String,Object> list(@McpToolParam(description="Theme identifier, e.g. demo/standorte",required=true) String theme) { return service.call("list",theme,null); }
    @McpTool(name="schema_plan",description="Resolve local configuration and prerequisites without changes. BLOCKED means do not create.",annotations=@McpTool.McpAnnotations(readOnlyHint=true,destructiveHint=false,openWorldHint=false))
    public Map<String,Object> plan(@McpToolParam(description="Theme identifier",required=true) String theme,@McpToolParam(description="Configured schema identifier",required=true) String schema) { return service.call("plan",theme,schema); }
    @McpTool(name="schema_inspect",description="Inspect the actual local schema. MATCHING means matching a recorded successful run, not independent semantic proof.",annotations=@McpTool.McpAnnotations(readOnlyHint=true,destructiveHint=false,openWorldHint=false))
    public Map<String,Object> inspect(@McpToolParam(description="Theme identifier",required=true) String theme,@McpToolParam(description="Configured schema identifier",required=true) String schema) { return service.call("inspect",theme,schema); }
    @McpTool(name="schema_create",description="Create a missing schema in the dedicated local lab. Never replaces, deletes or repairs an existing schema. Use only after an explicit creation request.",annotations=@McpTool.McpAnnotations(readOnlyHint=false,destructiveHint=false,idempotentHint=true,openWorldHint=false))
    public Map<String,Object> create(@McpToolParam(description="Theme identifier",required=true) String theme,@McpToolParam(description="Configured schema identifier",required=true) String schema) { return service.call("create",theme,schema); }
}
