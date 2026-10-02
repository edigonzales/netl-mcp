package ch.so.agi.netl;

import java.nio.file.Path;
import java.util.HashMap;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/** NETL capabilities; transport and server identity belong to the host. */
@Configuration(proxyBeanMethods = false)
@Import({SchemaTools.class, JobTools.class, ConfigTools.class})
public class NetlMcpModuleConfiguration {
    @Bean public NetlRuntimeSettings netlRuntimeSettings(Environment environment) {
        var values = new HashMap<String,String>();
        for (String key : new String[]{"NETL_RUNTIME_MODE", "NETL_HOST_WORKSPACE", "NETL_DOCKER_PROJECT"}) {
            String property = key.toLowerCase().replace('_', '.');
            String value = environment.getProperty(property);
            if (value != null) values.put(key, value);
        }
        return NetlRuntimeSettings.from(Path.of(environment.getProperty("netl.workspace", ".")), values);
    }
    @Bean public SchemaService schemaService(NetlRuntimeSettings settings) throws Exception {
        return new SchemaService(settings);
    }
    @Bean public JobService jobService(SchemaService schemas) throws Exception {
        return new JobService(schemas, java.time.Duration.ofSeconds(120));
    }
}
