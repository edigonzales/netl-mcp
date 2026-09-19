package ch.so.agi.netl;

import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class SchemaTools {
    private final SchemaService service;
    public SchemaTools(SchemaService service) { this.service=service; }
    @McpTool(name="schema_list",description="List configured schemas for a theme without connecting to the database. Use schemas[].ident (edit/pub) as the schema argument of other tools, never schemas[].name.",annotations=@McpTool.McpAnnotations(readOnlyHint=true,destructiveHint=false,openWorldHint=false))
    public Map<String,Object> list(@McpToolParam(description="Theme identifier, e.g. demo/standorte",required=true) String theme) { return service.call("list",theme,null); }
    @McpTool(name="schema_plan",description="Resolve configuration and prerequisites. Default create is read-only. recreate and drop-previous roll back a guarded drop probe and save an operation-specific one-use plan token. BLOCKED means do not execute.",annotations=@McpTool.McpAnnotations(readOnlyHint=false,destructiveHint=false,openWorldHint=false))
    public Map<String,Object> plan(@McpToolParam(description="Theme identifier",required=true) String theme,
        @McpToolParam(description="Configured ident, e.g. edit or pub. Never the physical schema name or baseName.",required=true) String schema,
        @McpToolParam(description="create (default), recreate or drop-previous",required=false) String operation) { return service.plan(theme,schema,operation); }
    @McpTool(name="schema_inspect",description="Inspect the actual local schema. MATCHING means matching a recorded successful run, not independent semantic proof.",annotations=@McpTool.McpAnnotations(readOnlyHint=true,destructiveHint=false,openWorldHint=false))
    public Map<String,Object> inspect(@McpToolParam(description="Theme identifier",required=true) String theme,@McpToolParam(description="Configured ident, e.g. edit or pub. Never the physical schema name or baseName.",required=true) String schema) { return service.call("inspect",theme,schema); }
    @McpTool(name="schema_create",description="Create a missing schema in the dedicated local lab. Call strictly sequentially: await this result before creating another schema. Concurrent calls return BUSY; stop on errors, never automatically retry. Never replaces, deletes or repairs an existing schema. Use only after an explicit creation request.",annotations=@McpTool.McpAnnotations(readOnlyHint=false,destructiveHint=false,idempotentHint=true,openWorldHint=false))
    public Map<String,Object> create(@McpToolParam(description="Theme identifier",required=true) String theme,@McpToolParam(description="Configured ident, e.g. edit or pub. Never the physical schema name or baseName.",required=true) String schema) { return service.call("create",theme,schema); }
    @McpTool(name="schema_recreate",description="Delete all target schema data and recreate a managed local schema. Requires explicit user rebuild request and a fresh recreate plan token. Never retry automatically.",
        annotations=@McpTool.McpAnnotations(readOnlyHint=false,destructiveHint=true,idempotentHint=false,openWorldHint=false))
    public Map<String,Object> recreate(@McpToolParam(description="Theme identifier",required=true) String theme,
        @McpToolParam(description="Configured ident, e.g. edit or pub. Never the physical schema name or baseName.",required=true) String schema,
        @McpToolParam(description="One-use token from schema_plan operation recreate",required=true) String planToken) {
        return service.recreate(theme,schema,planToken);
    }
    @McpTool(name="schema_drop_previous",description="Delete exactly version n-1 and its managed roles. Requires explicit deletion request and one-use schema_plan operation drop-previous token. No migration or automatic retry.",
        annotations=@McpTool.McpAnnotations(readOnlyHint=false,destructiveHint=true,idempotentHint=false,openWorldHint=false))
    public Map<String,Object> dropPrevious(@McpToolParam(description="Theme identifier",required=true) String theme,
        @McpToolParam(description="Configured ident, e.g. edit or pub. Never the physical schema name or baseName.",required=true) String schema,
        @McpToolParam(description="One-use drop-previous plan token",required=true) String planToken) {
        return service.dropPrevious(theme,schema,planToken);
    }
}
