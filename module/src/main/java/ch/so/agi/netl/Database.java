package ch.so.agi.netl;

import java.sql.*;
import java.util.*;

final class Database {
    static List<Map<String,Object>> query(Connection c, String sql, String schema) throws SQLException {
        try (var p = c.prepareStatement(sql)) {
            p.setString(1,schema);
            try (var rs = p.executeQuery()) {
                var rows = new ArrayList<Map<String,Object>>();
                while (rs.next()) {
                    var row = new TreeMap<String,Object>();
                    for (int i=1; i<=rs.getMetaData().getColumnCount(); i++) row.put(rs.getMetaData().getColumnLabel(i),rs.getObject(i));
                    rows.add(row);
                }
                return rows;
            }
        }
    }
    static boolean exists(Connection c, String schema) throws SQLException {
        return !query(c,"SELECT nspname FROM pg_namespace WHERE nspname = ?",schema).isEmpty();
    }
    static Map<String,Object> snapshot(Connection c, String schema) throws SQLException {
        // Repeatable-read gives one coherent catalog view across these queries.
        c.setAutoCommit(false);
        c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        try {
            var result = new TreeMap<String,Object>();
            result.put("tables",query(c,"SELECT tablename AS name FROM pg_tables WHERE schemaname=? ORDER BY tablename",schema));
            result.put("columns",query(c,"""
                SELECT table_name, column_name, ordinal_position, data_type, udt_name,
                       is_nullable, column_default, character_maximum_length, numeric_precision, numeric_scale
                FROM information_schema.columns WHERE table_schema=? ORDER BY table_name, ordinal_position
                """,schema));
            result.put("constraints",query(c,"""
                SELECT r.relname AS table_name, con.conname AS name, con.contype::text AS type,
                       pg_get_constraintdef(con.oid) AS definition
                FROM pg_constraint con JOIN pg_class r ON r.oid=con.conrelid
                JOIN pg_namespace n ON n.oid=r.relnamespace WHERE n.nspname=? ORDER BY r.relname,con.conname
                """,schema));
            result.put("indexes",query(c,"SELECT tablename AS table_name,indexname AS name,indexdef AS definition FROM pg_indexes WHERE schemaname=? ORDER BY tablename,indexname",schema));
            result.put("geometries",query(c,"SELECT f_table_name AS table_name,f_geometry_column AS column_name,coord_dimension,srid,type FROM geometry_columns WHERE f_table_schema=? ORDER BY f_table_name,f_geometry_column",schema));
            c.commit();
            return result;
        } catch (SQLException e) { c.rollback(); throw e; }
        finally { c.setAutoCommit(true); }
    }
    // PostgreSQL itself reports the complete cascade, including views, constraints,
    // functions and indirect dependencies. Raising inside sql_drop rolls the DDL back.
    static void guardedDrop(Connection c, String name, boolean commit) throws Exception {
        guardedDrop(c, name, Map.of(), commit);
    }
    static void guardedDrop(Connection c, String name, Map<String,Object> roles, boolean commit) throws Exception {
        Configuration.require(name.matches("[a-z][a-z0-9_]{0,62}") &&
            !name.startsWith("pg_") && !Set.of("public", "information_schema").contains(name), "Invalid drop target");
        String guard = "netl_guard_" + UUID.randomUUID().toString().replace("-", "");
        c.setAutoCommit(false);
        try (var st = c.createStatement()) {
            st.execute("SET LOCAL lock_timeout = '5s'");
            st.execute("CREATE FUNCTION public." + guard + "() RETURNS event_trigger LANGUAGE plpgsql AS $guard$ " +
                "BEGIN IF EXISTS (SELECT 1 FROM pg_event_trigger_dropped_objects() " +
                "WHERE schema_name IS DISTINCT FROM '" + name + "' " +
                "AND NOT COALESCE((schema_name = 'pg_toast' AND NOT original AND NOT normal AND object_type IN ('toast table','index')), false) " +
                "AND NOT (object_type = 'schema' AND object_name = '" + name + "')) " +
                "THEN RAISE EXCEPTION 'NETL_EXTERNAL_DEPENDENCY: drop affects objects outside target schema'; END IF; END $guard$");
            st.execute("CREATE EVENT TRIGGER " + guard + " ON sql_drop EXECUTE FUNCTION public." + guard + "()");
            st.execute("DROP SCHEMA IF EXISTS \"" + name + "\" CASCADE");
            st.execute("DROP EVENT TRIGGER " + guard);
            st.execute("DROP FUNCTION public." + guard + "()");
            for (var role : roles.entrySet()) {
                Configuration.require(role.getKey().matches("[a-z][a-z0-9_]{0,62}"), "Invalid role name");
                var found = query(c, "SELECT oid::bigint AS id FROM pg_roles WHERE rolname=?", role.getKey());
                if (found.isEmpty()) continue;
                if (!found.getFirst().get("id").toString().equals(role.getValue().toString()))
                    throw new Failure("UNMANAGED_ROLE", "Role was replaced: " + role.getKey());
                if (!query(c, "SELECT m.roleid FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.member WHERE r.rolname=?", role.getKey()).isEmpty())
                    throw new Failure("EXTERNAL_DEPENDENCY", "Schema role is a member of another role");
                // PostgreSQL rejects roles with remaining ownership or ACL dependencies,
                // including dependencies in another database. Never use DROP OWNED.
                st.execute("DROP ROLE \"" + role.getKey() + "\"");
            }
            if (commit) c.commit(); else c.rollback();
        } catch (Exception e) {
            c.rollback();
            if (e.getMessage().contains("NETL_EXTERNAL_DEPENDENCY") || (e instanceof SQLException sql && "2BP01".equals(sql.getSQLState())))
                throw new Failure("EXTERNAL_DEPENDENCY", "Schema deletion would affect objects outside the target schema");
            throw e;
        } finally { c.setAutoCommit(true); }
    }
    static Map<String,Object> roleIds(Connection c, List<String> names) throws Exception {
        var result = new TreeMap<String,Object>();
        for (String name : names) {
            var rows = query(c, "SELECT oid::bigint AS id FROM pg_roles WHERE rolname=?", name);
            if (!rows.isEmpty()) result.put(name, rows.getFirst().get("id"));
        }
        return result;
    }
    static Map<String,Object> permissions(Connection c, String schema, List<String> names) throws Exception {
        var result = new TreeMap<String,Object>();
        result.put("schema", query(c, "SELECT nspowner::regrole::text AS owner, nspacl::text AS acl, obj_description(oid,'pg_namespace') AS comment FROM pg_namespace WHERE nspname=?", schema));
        result.put("objects", query(c, "SELECT r.relname AS name,r.relkind::text AS kind,r.relowner::regrole::text AS owner,r.relacl::text AS acl FROM pg_class r JOIN pg_namespace n ON n.oid=r.relnamespace WHERE n.nspname=? ORDER BY r.relname", schema));
        result.put("columns", query(c, "SELECT r.relname AS name,a.attname AS column,a.attacl::text AS acl FROM pg_attribute a JOIN pg_class r ON r.oid=a.attrelid JOIN pg_namespace n ON n.oid=r.relnamespace WHERE n.nspname=? AND a.attacl IS NOT NULL ORDER BY r.relname,a.attnum", schema));
        result.put("functions", query(c, "SELECT p.oid::regprocedure::text AS name,p.proowner::regrole::text AS owner,p.proacl::text AS acl FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname=? ORDER BY name", schema));
        result.put("defaults", query(c, "SELECT d.defaclrole::regrole::text AS owner,d.defaclobjtype::text AS kind,d.defaclacl::text AS acl FROM pg_default_acl d JOIN pg_namespace n ON n.oid=d.defaclnamespace WHERE n.nspname=? ORDER BY owner,kind", schema));
        var roles = new TreeMap<String,Object>();
        for (String name : names) {
            roles.put(name, Map.of("attributes", query(c,"SELECT oid::bigint AS id,rolsuper,rolinherit,rolcreaterole,rolcreatedb,rolcanlogin,rolreplication,rolbypassrls,rolconnlimit,rolvaliduntil::text,rolconfig::text FROM pg_roles WHERE rolname=?",name),
                "memberships", query(c,"SELECT m.roleid::regrole::text AS role,m.member::regrole::text AS member,m.grantor::regrole::text AS grantor,m.admin_option,m.inherit_option,m.set_option FROM pg_auth_members m JOIN pg_roles r ON (r.oid=m.roleid OR r.oid=m.member) WHERE r.rolname=? ORDER BY role,member,grantor",name)));
        }
        result.put("roles", roles);
        return result;
    }
    static boolean lock(Connection c, String name) throws SQLException {
        try (var p=c.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?,0))")) {
            p.setString(1,"netl:" + name);
            try (var r=p.executeQuery()) { r.next(); return r.getBoolean(1); }
        }
    }
}
