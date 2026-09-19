package ch.so.agi.netl;

import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class JobChecks {
    static String q(String identifier) { return "\"" + JobConfiguration.identifier(identifier) + "\""; }
    static String sql(byte[] bytes, String source, String target) {
        return new String(bytes,StandardCharsets.UTF_8).replace("${sourceSchema}",q(source)).replace("${targetSchema}",q(target));
    }
    static Map<String,Object> check(Connection c, String id, String description, String sql) {
        var result = new TreeMap<String,Object>(); result.put("id",id); result.put("description",description);
        try {
            c.setReadOnly(true); c.setAutoCommit(false);
            try (var st = c.createStatement()) {
                st.setQueryTimeout(10); st.setMaxRows(11);
                try (var rs = st.executeQuery(sql)) {
                    var rows = new ArrayList<Map<String,Object>>();
                    while (rs.next()) {
                        var row = new TreeMap<String,Object>();
                        for (int i=1;i<=rs.getMetaData().getColumnCount();i++) row.put(rs.getMetaData().getColumnLabel(i),rs.getString(i));
                        rows.add(row);
                    }
                    result.put("status",rows.isEmpty() ? "PASSED" : "FAILED");
                    result.put("violations",rows.stream().limit(10).toList()); result.put("truncated",rows.size()>10);
                }
            }
        } catch (Exception e) { result.put("status","ERROR"); result.put("message",e.getMessage()); }
        finally { try { c.rollback(); c.setAutoCommit(true); } catch (SQLException ignored) {} }
        return result;
    }
    static List<Map<String,Object>> run(Connection c, JobConfiguration.Spec job, String source, String target, boolean fixture) throws Exception {
        var result = new ArrayList<Map<String,Object>>();
        for (var table : job.tables()) {
            String name=(String)table.get("name"), qualified=q(target)+"."+q(name);
            // Referencing a missing relation is a failed check, not an empty successful result.
            result.add(check(c,name+"_present","Table exists", "SELECT 1 FROM " + qualified + " WHERE false"));
            if (!Boolean.TRUE.equals(table.get("allowEmpty")))
                result.add(check(c,name+"_nonempty","Table is not empty","SELECT 'empty' AS violation WHERE NOT EXISTS (SELECT 1 FROM " + qualified + ")"));
            String keys=Configuration.strings(table,"key").stream().map(JobChecks::q).collect(java.util.stream.Collectors.joining(","));
            result.add(check(c,name+"_unique","Business key is unique","SELECT " + keys + ",count(*) FROM " + qualified + " GROUP BY " + keys + " HAVING count(*)>1"));
            String nullKeys=Configuration.strings(table,"key").stream().map(k->q(k)+" IS NULL").collect(java.util.stream.Collectors.joining(" OR "));
            result.add(check(c,name+"_key_required","Business key is present","SELECT "+keys+" FROM "+qualified+" WHERE "+nullKeys));
            var columns=Database.query(c,"SELECT column_name FROM information_schema.columns WHERE table_schema=? AND table_name='"+name+"' AND is_nullable='NO'",target);
            for(var column:columns) {
                String col=(String)column.get("column_name");
                result.add(check(c,name+"_required_"+col,"Required column " + col,"SELECT " + keys + " FROM " + qualified + " WHERE " + q(col) + " IS NULL"));
            }
            var geometries=Database.query(c,"SELECT f_geometry_column AS column_name,srid FROM geometry_columns WHERE f_table_schema=? AND f_table_name='"+name+"'",target);
            for(var geometry:geometries) {
                String col=q((String)geometry.get("column_name"));
                result.add(check(c,name+"_geometry_"+geometry.get("column_name"),"Valid geometry and configured SRID","SELECT " + keys + " FROM " + qualified + " WHERE " + col + " IS NOT NULL AND (NOT ST_IsValid("+col+") OR ST_SRID("+col+")<>"+((Map<?,?>)job.target().effective().get("options")).get("defaultSrsCode")+")"));
            }
        }
        for (var a:job.assertions()) if (fixture || "local".equals(a.get("scope"))) {
            var check = new TreeMap<>(check(c,(String)a.get("id"),(String)a.get("description"),sql(job.files().get(a.get("sql")),source,target)));
            check.put("origin",a.get("origin")); check.put("scope",a.get("scope")); result.add(check);
        }
        return result;
    }
    static boolean passed(List<Map<String,Object>> results) { return results.stream().allMatch(r -> "PASSED".equals(r.get("status"))); }
    static Map<String,String> contents(Connection c, JobConfiguration.Spec job, String schema) throws Exception {
        var result = new TreeMap<String,String>();
        for (var table : job.tables()) {
            String columns=Configuration.strings(table,"columns").stream().map(JobChecks::q).collect(java.util.stream.Collectors.joining(","));
            var rows=new ArrayList<String>();
            try(var st=c.createStatement()) {
                st.setQueryTimeout(10);
                try(var rs=st.executeQuery("SELECT row_to_json(t)::text FROM (SELECT "+columns+" FROM "+q(schema)+"."+q((String)table.get("name"))+") t")) {
                    while(rs.next()) { Configuration.require(rows.size()<100000,"Test result too large"); rows.add(rs.getString(1)); }
                }
            }
            Collections.sort(rows); result.put((String)table.get("name"),Json.hash(rows));
        }
        return result;
    }
}
