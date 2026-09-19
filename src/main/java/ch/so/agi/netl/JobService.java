package ch.so.agi.netl;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import static ch.so.agi.netl.Configuration.*;

/** Local-only job execution. A successful process is not a successful acceptance test. */
public final class JobService {
    final SchemaService schemas;
    final JobConfiguration jobs;
    final LocalRuntime runtime;
    final Duration timeout;
    public JobService(Path workspace) throws Exception { this(workspace,Duration.ofSeconds(120)); }
    JobService(Path workspace, Duration timeout) throws Exception {
        schemas=new SchemaService(workspace); jobs=new JobConfiguration(schemas.config); runtime=schemas.runtime; this.timeout=timeout;
    }
    Path state(JobConfiguration.Spec job) throws Exception {
        Path p=schemas.config.workspace.resolve(".netl/jobs/"+job.theme()+"/"+job.job()); jobs.safe(p); return p;
    }
    Map<String,Object> read(Path file) throws Exception { jobs.safe(file); return Files.exists(file) ? Json.object(Files.readAllBytes(file)) : Map.of(); }
    public Map<String,Object> call(String operation,String theme,String job,String token) {
        try {
            if (operation.equals("context")) return context(theme);
            var spec=jobs.load(theme,job);
            return switch(operation) {
                case "validate" -> Map.of("status","VALID","fingerprint",spec.fingerprint(),"expectationsRevision",spec.contract(),"requirements",spec.tests(),"manifest",spec.manifest(),"warning","Validation does not execute or sandbox Gradle code");
                case "confirm" -> confirm(spec,token);
                case "test" -> test(spec);
                case "plan" -> plan(spec);
                case "run" -> run(spec,token);
                case "status" -> status(spec);
                default -> throw new Failure("INVALID_OPERATION","Unknown job operation");
            };
        } catch(Exception e) { return SchemaService.error(e); }
    }
    Map<String,Object> context(String theme) throws Exception {
        var items=new ArrayList<Map<String,Object>>();
        for(var s:schemas.config.list(theme)) {
            var item=new TreeMap<String,Object>(); item.put("identity",s.identity());
            item.put("version",s.version()==null?"UNVERSIONED":s.version());
            item.put("profile",s.effective().get("profile")); item.put("options",s.effective().get("options"));
            var models=new TreeMap<String,String>(); s.files().forEach((k,v)->models.put(k,new String(v,StandardCharsets.UTF_8))); item.put("models",models);
            var inspection=schemas.call("inspect",theme,s.ident()); item.put("status",inspection.get("status"));
            if(inspection.get("structure") instanceof Map<?,?> structure) {
                var catalog=new TreeMap<String,Object>();
                for(String kind:List.of("tables","columns","constraints","geometries")) {
                    var rows=new ArrayList<Map<String,Object>>();
                    for(Object obj:(List<?>)structure.get(kind)) {
                        var row=(Map<?,?>)obj;
                        String table=String.valueOf(row.get(kind.equals("tables")?"name":"table_name"));
                        if(table.startsWith("t_ili2db_"))continue;
                        var compact=new TreeMap<String,Object>();
                        for(String key:List.of("name","table_name","column_name","data_type","udt_name","is_nullable","column_default","character_maximum_length","type","definition","srid","coord_dimension"))
                            if(row.get(key)!=null)compact.put(key,row.get(key));
                        rows.add(compact);
                    }
                    catalog.put(kind,rows);
                }
                item.put("catalog",catalog);
            } else if(inspection.containsKey("message")) item.put("problem",inspection);
            items.add(item);
        }
        return Map.of("status","OK","schemas",items,"conventions",Map.of(
            "properties",List.of("dbUriEdit","dbUserEdit","dbPwdEdit","dbUriPub","dbUserPub","dbPwdPub","sourceSchema","targetSchema"),
            "sqlParameters","Db2Db: sqlParameters = [sourceSchema: sourceSchema]; SQL uses ${sourceSchema}",
            "initScript","/home/gradle/init.gradle (always supplied by runner)",
            "assertions","SQL returns violations; zero rows passes. ${sourceSchema}/${targetSchema} are quoted by NETL. Assertions query pub only.",
            "trust","Local synthetic lab only. Gradle is executable code, not sandboxed."));
    }
    Map<String,Object> confirm(JobConfiguration.Spec s,String revision) throws Exception {
        require(s.contract().equals(revision),"Confirm the exact expectationsRevision returned by job_validate after explicit user approval");
        require(!s.assertions().isEmpty(),"At least one domain assertion required for acceptance");
        try(var lock=runtime.lock()) {
            Files.createDirectories(state(s));
            Json.write(state(s).resolve("confirmation.json"),Map.of("revision",revision,"confirmedAt",Instant.now().toString()));
        }
        return Map.of("status","CONFIRMED","expectationsRevision",revision);
    }
    boolean confirmed(JobConfiguration.Spec s) throws Exception { return s.contract().equals(read(state(s).resolve("confirmation.json")).get("revision")); }
    Map<String,Object> status(JobConfiguration.Spec s) throws Exception {
        var last=read(state(s).resolve("last.json"));
        return Map.of("status","OK","fingerprint",s.fingerprint(),"expectationsRevision",s.contract(),"confirmed",confirmed(s),"lastRun",last,"current",s.fingerprint().equals(last.get("fingerprint")));
    }
    // Separate tool capabilities prevent the author from rewriting the test contract.
    public Map<String,Object> write(String theme,String job,String file,String content,boolean test) {
        try(var lock=runtime.lock()) {
            Path dir=jobs.directory(theme,job); JobConfiguration.relative(file);
            boolean allowed=test ? file.equals("tests.json") || file.startsWith("fixtures/") && file.endsWith(".sql") || file.startsWith("assertions/") && file.endsWith(".sql")
                : file.equals("job.json") || file.equals("build.gradle") || file.startsWith("sql/") && file.endsWith(".sql");
            require(allowed,"Artifact belongs to the other agent capability");
            require(content!=null && content.getBytes(StandardCharsets.UTF_8).length<=1_000_000,"Artifact too large");
            if(file.equals("job.json")) {
                var m=Json.object(content.getBytes(StandardCharsets.UTF_8));
                require(Integer.valueOf(1).equals(m.get("formatVersion")),"job.json requires numeric formatVersion:1");
                for(String key:List.of("source","target","task")) string(m,key);
                strings(m,"sqlFiles"); require(m.get("tables") instanceof List<?> list && !list.isEmpty(),"job.json requires tables:[{name,key:[...],columns:[...]}]");
                for(Object obj:(List<?>)m.get("tables")) {
                    require(obj instanceof Map<?,?>,"Each table must be an object: {name:table_name,key:[business_key],columns:[comparison_columns]}");
                    var table=(Map<?,?>)obj; JobConfiguration.identifier(string(table,"name")); strings(table,"key"); strings(table,"columns");
                }
            }
            if(file.equals("tests.json")) {
                var m=Json.object(content.getBytes(StandardCharsets.UTF_8));
                require(Integer.valueOf(1).equals(m.get("formatVersion")),"tests.json requires numeric formatVersion:1");
                string(m,"requirements"); strings(m,"fixtures");
                require(m.get("assertions") instanceof List<?>,"tests.json requires assertions array of objects");
                for(Object assertion:(List<?>)m.get("assertions")) {
                    require(assertion instanceof Map<?,?>,"Assertion must be object with id,description,origin,scope,sql,expected");
                    for(String key:List.of("id","description","origin","scope","sql","expected")) string((Map<?,?>)assertion,key);
                }
            }
            if(test) {
                Path confirmation=schemas.config.workspace.resolve(".netl/jobs/"+theme+"/"+job+"/confirmation.json");
                require(!Files.exists(confirmation),"Confirmed test contract is frozen; use a new job revision for changed requirements");
            }
            Path path=dir.resolve(file); jobs.safe(path); Files.createDirectories(path.getParent());
            Files.writeString(path,content);
            return Map.of("status","SAVED","path",path.toString());
        } catch(Exception e) { return SchemaService.error(e); }
    }
    Configuration.Spec isolated(Configuration.Spec s,String name) throws Exception {
        var effective=new TreeMap<>(s.effective()); effective.put("name",name); effective.put("baseName",name); effective.remove("schemaVersion"); effective.put("roleSuffix","");
        // Structural hooks are retained; production-style recipient grants are not applied to disposable fixtures.
        var sql=new TreeMap<>(s.sqlFiles()); sql.remove("grants");
        effective.put("sqlFiles",Map.of());
        return new Configuration.Spec(s.theme(),s.ident(),name,s.database(),effective,s.files(),sql,Json.hash(effective));
    }
    Path snapshot(JobConfiguration.Spec s,String mode) throws Exception {
        Path root=schemas.config.workspace.resolve(".netl/job-runs"); jobs.safe(root); Files.createDirectories(root);
        Path run=Files.createTempDirectory(root,mode+"-");
        for(var file:s.files().entrySet()) { Path p=run.resolve(file.getKey()); Files.createDirectories(p.getParent()); Files.write(p,file.getValue()); }
        Files.writeString(run.resolve("settings.gradle"),"rootProject.name = 'netl-job'\n");
        Json.write(run.resolve("inputs.json"),Map.of("fingerprint",s.fingerprint(),"hashes",s.hashes(),"expectationsRevision",s.contract(),"source",s.source().effective(),"target",s.target().effective()));
        return run;
    }
    Map<String,Object> record(JobConfiguration.Spec s,Path run,String mode) {
        var result=new TreeMap<String,Object>(); result.put("status","RUNNING"); result.put("mode",mode); result.put("runDirectory",run.toString());
        result.put("fingerprint",s.fingerprint()); result.put("expectationsRevision",s.contract()); result.put("transformHash",s.transformHash());
        result.put("startedAt",Instant.now().toString()); result.put("targetMayHaveChanged",false); return result;
    }
    void persist(JobConfiguration.Spec s,Path run,Map<String,Object> result) throws Exception {
        Files.createDirectories(state(s));
        Json.write(run.resolve("result.json"),result); Json.write(state(s).resolve("last.json"),result);
    }
    Map<String,Object> test(JobConfiguration.Spec s) throws Exception {
        runtime.runnerReady();
        try(var held=runtime.lock()) {
            var previous=read(state(s).resolve("test.json"));
            int attempt=1;
            if(s.contract().equals(previous.get("expectationsRevision")) && !Set.of("PASSED","GENERIC_ONLY").contains(previous.get("status"))) {
                if(Set.of("RUNNER_TIMEOUT","BUSY","RUNNER_RECOVERY_REQUIRED").contains(String.valueOf(previous.get("code"))))
                    throw new Failure("RETRY_BLOCKED","Previous timeout/BUSY requires operator investigation, not automatic retry");
                if(s.transformHash().equals(previous.get("transformHash"))) throw new Failure("UNCHANGED_JOB","Change the transform before another failed test attempt");
                attempt=((Number)previous.getOrDefault("attempt",0)).intValue()+1;
                if(attempt>3) throw new Failure("ATTEMPTS_EXHAUSTED","Three test attempts exhausted; escalate to user");
            }
            Path run=snapshot(s,"test"); var result=record(s,run,"test"); result.put("attempt",attempt);
            if(!previous.isEmpty()) {
                result.put("previousRun",previous.get("runDirectory"));
                var changes=new TreeMap<String,Object>();
                Path before=Path.of((String)previous.get("runDirectory")); jobs.safe(before);
                for(var f:s.files().entrySet()) if(f.getKey().equals("build.gradle") || f.getKey().startsWith("sql/")) {
                    Path p=before.resolve(f.getKey()); String old=Files.exists(p)?Files.readString(p):"";
                    String next=new String(f.getValue(),StandardCharsets.UTF_8);
                    if(!old.equals(next)) changes.put(f.getKey(),Map.of("before",old,"after",next));
                }
                Json.write(run.resolve("changes.json"),changes);
            }
            persist(s,run,result);
            String id=UUID.randomUUID().toString().replace("-","").substring(0,16);
            var source=isolated(s.source(),"netl_jt_"+id+"_edit"); var target=isolated(s.target(),"netl_jt_"+id+"_pub");
            result.put("testSchemas",List.of(source.identity(),target.identity()));
            var cleanup=new ArrayList<Map<String,Object>>();
            try {
                for(var spec:List.of(source,target)) {
                    var created=schemas.executeLocked(spec,null,false);
                    if(!"CREATED".equals(created.get("status"))) throw new Failure(String.valueOf(created.getOrDefault("code","TEST_SCHEMA_FAILED")),created.toString());
                }
                try(var a=runtime.connect("edit");var b=runtime.connect("pub")) {
                    lockSchemas(a,b,source,target);
                    try(var credentials=new Credentials(a,b,source.name(),target.name(),true)) {
                        try(var fixture=credentials.connect("edit")) {
                            fixture.setAutoCommit(false);
                            try(var st=fixture.createStatement()) {
                                st.setQueryTimeout(10);
                                for(String file:strings(s.tests(),"fixtures")) st.execute(JobChecks.sql(s.files().get(file),source.name(),target.name()));
                                fixture.commit();
                            }
                        }
                        credentials.readOnlySource();
                        execute(s,run,source.name(),target.name(),credentials,"first.log");
                        result.put("gradleSucceeded",true);
                        try(var check=credentials.checkConnection()) {
                            var checks=JobChecks.run(check,s,source.name(),target.name(),true); result.put("checks",checks);
                            if(!JobChecks.passed(checks)) throw new Failure("ASSERTION_FAILED","Result assertions failed");
                            var first=JobChecks.contents(check,s,target.name());
                            execute(s,run,source.name(),target.name(),credentials,"repeat.log");
                            var repeated=JobChecks.run(check,s,source.name(),target.name(),true); result.put("repeatChecks",repeated);
                            require(first.equals(JobChecks.contents(check,s,target.name())),"Repeated job changed business results");
                            if(!JobChecks.passed(repeated)) throw new Failure("ASSERTION_FAILED","Repeat assertions failed");
                        }
                    }
                }
                result.put("status",confirmed(s)?"PASSED":"GENERIC_ONLY");
                result.put("domainAccepted",confirmed(s));
            } catch(Exception e) { result.put("status","FAILED"); result.put("error",SchemaService.error(e)); result.put("code",e instanceof Failure f?f.code:"JOB_ERROR"); }
            finally {
                // If termination is uncertain, leave evidence and schemas untouched for manual recovery.
                if(Files.exists(schemas.config.workspace.resolve(".netl/runner-recovery.json"))) cleanup.add(Map.of("status","DEFERRED","reason","Runner termination unconfirmed"));
                else for(var spec:List.of(target,source)) {
                    try(var c=runtime.connect(spec.database())) {
                        var evidence=schemas.state(spec);
                        if(!evidence.isEmpty()) {
                            Database.guardedDrop(c,spec.name(),schemas.managedRoles(spec,c),true);
                            var deleted=new TreeMap<>(evidence); deleted.put("status","DELETED");
                            Json.write(Path.of((String)evidence.get("logPath")).getParent().resolve("result.json"),deleted);
                            Files.deleteIfExists(schemas.statePath(spec)); // disposable schemas are not configured managed versions
                        }
                        cleanup.add(Map.of("schema",spec.name(),"status","CLEANED"));
                    } catch(Exception e) { cleanup.add(Map.of("schema",spec.name(),"status","FAILED","error",SchemaService.error(e))); }
                }
                result.put("cleanup",cleanup);
                if(cleanup.stream().anyMatch(x->!"CLEANED".equals(x.get("status")))) result.put("status","FAILED");
                result.put("finishedAt",Instant.now().toString()); persist(s,run,result); Json.write(state(s).resolve("test.json"),result);
            }
            return result;
        }
    }
    void lockSchemas(Connection a,Connection b,Configuration.Spec source,Configuration.Spec target) throws Exception {
        if(!Database.lock(a,source.name()) || !Database.lock(b,target.name())) throw new Failure("BUSY","Source or target schema is in use");
    }
    Map<String,Object> readiness(JobConfiguration.Spec s,Connection a,Connection b) throws Exception {
        require(confirmed(s),"Expectations are not confirmed at the current revision");
        var test=read(state(s).resolve("test.json"));
        if(!"PASSED".equals(test.get("status")) || !s.fingerprint().equals(test.get("fingerprint"))) throw new Failure("TEST_REQUIRED","A passing test of the exact current job and runner is required");
        schemas.requireManaged(s.source()); schemas.requireManaged(s.target());
        var source=schemas.inspect(s.source(),a); var target=schemas.inspect(s.target(),b);
        if(!"MATCHING".equals(source.get("status")) || !"MATCHING".equals(target.get("status"))) throw new Failure("SCHEMA_NOT_MATCHING","Configured schemas must match their managed records; no automatic rebuild");
        return Map.of("source",source,"target",target);
    }
    Map<String,Object> plan(JobConfiguration.Spec s) throws Exception {
        runtime.runnerReady();
        try(var held=runtime.lock();var a=runtime.connect("edit");var b=runtime.connect("pub")) {
            lockSchemas(a,b,s.source(),s.target()); var inspection=readiness(s,a,b);
            String token=UUID.randomUUID().toString();
            Json.write(state(s).resolve(token+".json"),Map.of("fingerprint",s.fingerprint(),"inspectionHash",Json.hash(inspection),"createdAt",Instant.now().toString()));
            return Map.of("status","READY","planToken",token,"source",s.source().effective(),"target",s.target().effective(),"tables",s.tables(),"warning","Job may replace target table contents. Failed post-commit assertions do not roll back data. Explicit local run request required.");
        }
    }
    Map<String,Object> run(JobConfiguration.Spec s,String token) throws Exception {
        runtime.runnerReady();
        require(token!=null && token.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"),"Invalid plan token");
        try(var held=runtime.lock();var a=runtime.connect("edit");var b=runtime.connect("pub")) {
            lockSchemas(a,b,s.source(),s.target());
            Path path=state(s).resolve(token+".json"); var planned=read(path);
            if(planned.isEmpty()) throw new Failure("INVALID_PLAN","Unknown or consumed token");
            if(!s.fingerprint().equals(planned.get("fingerprint")) || !Json.hash(readiness(s,a,b)).equals(planned.get("inspectionHash"))) throw new Failure("STALE_PLAN","Job, tests or schema evidence changed");
            if(Instant.parse((String)planned.get("createdAt")).plusSeconds(900).isBefore(Instant.now())) throw new Failure("STALE_PLAN","Plan expired");
            Files.delete(path);
            Path run=snapshot(s,"local"); var result=record(s,run,"local"); persist(s,run,result);
            try(var credentials=new Credentials(a,b,s.source().name(),s.target().name(),false)) {
                result.put("targetMayHaveChanged",true); persist(s,run,result);
                execute(s,run,s.source().name(),s.target().name(),credentials,"runner.log"); result.put("gradleSucceeded",true);
                try(var check=credentials.checkConnection()) {
                    var checks=JobChecks.run(check,s,s.source().name(),s.target().name(),false); result.put("checks",checks);
                    if(!JobChecks.passed(checks)) throw new Failure("ASSERTION_FAILED","Post-commit assertions failed; target may already be changed. No rollback or retry.");
                }
                result.put("status","PASSED");
            } catch(Exception e) { result.put("status","FAILED"); result.put("error",SchemaService.error(e)); }
            result.put("finishedAt",Instant.now().toString()); persist(s,run,result); return result;
        }
    }
    void execute(JobConfiguration.Spec s,Path run,String source,String target,Credentials credentials,String log) throws Exception {
        Properties props=new Properties(); props.setProperty("netlJobRun","true"); props.setProperty("sourceSchema",source); props.setProperty("targetSchema",target);
        for(String db:List.of("edit","pub")) {
            String suffix=db.equals("edit")?"Edit":"Pub";
            props.setProperty("dbUri"+suffix,"jdbc:postgresql://"+db+"-db:5432/"+db+"?ApplicationName=netl-job-"+credentials.name);
            props.setProperty("dbUser"+suffix,credentials.name); props.setProperty("dbPwd"+suffix,credentials.password);
        }
        Path file=run.resolve("gradle.properties");
        try {
            try(var out=Files.newOutputStream(file)) { props.store(out,"Ephemeral local DML credentials"); }
            long start=System.nanoTime(); runtime.executeProject(run,run.resolve(log),timeout,List.of(s.task()),true);
            var measurement=new TreeMap<String,Object>(); measurement.put("elapsedMillis",(System.nanoTime()-start)/1_000_000);
            measurement.put("containerId",LocalRuntime.command(List.of("docker","inspect","--format","{{.Id}}","themenintegration-lab-gretl-1")).strip());
            measurement.put("runtime",read(run.resolve("runtime.json"))); Json.write(run.resolve(log+".runtime.json"),measurement);
        } finally { Files.deleteIfExists(file); }
    }
    final class Credentials implements AutoCloseable {
        final Connection a,b; final String source,target;
        final String name="netl_jr_"+UUID.randomUUID().toString().replace("-","");
        final String password=UUID.randomUUID().toString();
        boolean createdA,createdB,createdCheck;
        Credentials(Connection a,Connection b,String source,String target,boolean fixture) throws Exception {
            this.a=a;this.b=b;this.source=source;this.target=target;
            try {
                create(a); createdA=true; create(b); createdB=true;
                grant(a,source,fixture); grant(b,target,true);
                try(var st=b.createStatement()) {
                    st.execute("CREATE ROLE "+name+"_check LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '"+password+"'"); createdCheck=true;
                    st.execute("GRANT USAGE ON SCHEMA "+JobChecks.q(target)+" TO "+name+"_check");
                    st.execute("GRANT SELECT ON ALL TABLES IN SCHEMA "+JobChecks.q(target)+" TO "+name+"_check");
                }
            } catch(Exception e) { try { close(); } catch(Exception cleanup) { e.addSuppressed(cleanup); } throw e; }
        }
        void create(Connection c) throws Exception { try(var st=c.createStatement()) { st.execute("CREATE ROLE "+name+" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '"+password+"'"); } }
        void grant(Connection c,String schema,boolean write) throws Exception {
            try(var st=c.createStatement()) {
                st.execute("GRANT USAGE ON SCHEMA "+JobChecks.q(schema)+" TO "+name);
                st.execute("GRANT SELECT"+(write?",INSERT,UPDATE,DELETE":"")+" ON ALL TABLES IN SCHEMA "+JobChecks.q(schema)+" TO "+name);
                if(write) st.execute("GRANT USAGE ON ALL SEQUENCES IN SCHEMA "+JobChecks.q(schema)+" TO "+name);
            }
        }
        void readOnlySource() throws Exception {
            try(var st=a.createStatement()) {
                st.execute("REVOKE INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA "+JobChecks.q(source)+" FROM "+name);
                st.execute("REVOKE USAGE ON ALL SEQUENCES IN SCHEMA "+JobChecks.q(source)+" FROM "+name);
            }
        }
        Connection connect(String db) throws Exception {
            return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:"+(db.equals("edit")?55431:55432)+"/"+db+"?ApplicationName=netl-job-"+name+"&connectTimeout=3&socketTimeout=15",name,password);
        }
        Connection checkConnection() throws Exception {
            return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:55432/pub?ApplicationName=netl-job-"+name+"&connectTimeout=3&socketTimeout=15",name+"_check",password);
        }
        void revoke(Connection c,String schema) throws Exception {
            try(var st=c.createStatement()) {
                st.execute("REVOKE ALL ON ALL TABLES IN SCHEMA "+JobChecks.q(schema)+" FROM "+name);
                st.execute("REVOKE ALL ON ALL SEQUENCES IN SCHEMA "+JobChecks.q(schema)+" FROM "+name);
                st.execute("REVOKE ALL ON SCHEMA "+JobChecks.q(schema)+" FROM "+name);
                st.execute("DROP ROLE "+name);
            }
        }
        public void close() throws Exception {
            Exception error=null;
            if(createdCheck) try(var st=b.createStatement()) {
                st.execute("REVOKE ALL ON ALL TABLES IN SCHEMA "+JobChecks.q(target)+" FROM "+name+"_check");
                st.execute("REVOKE ALL ON SCHEMA "+JobChecks.q(target)+" FROM "+name+"_check");
                st.execute("DROP ROLE "+name+"_check");
            } catch(Exception e) { error=e; }
            if(createdA) try { revoke(a,source); } catch(Exception e) { error=e; }
            if(createdB) try { revoke(b,target); } catch(Exception e) { if(error==null)error=e;else error.addSuppressed(e); }
            if(error!=null)throw error;
        }
    }
}
