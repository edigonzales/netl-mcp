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
    static boolean lock(Connection c, String name) throws SQLException {
        try (var p=c.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?,0))")) {
            p.setString(1,"netl:" + name);
            try (var r=p.executeQuery()) { r.next(); return r.getBoolean(1); }
        }
    }
}
