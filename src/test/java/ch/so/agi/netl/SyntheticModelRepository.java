package ch.so.agi.netl;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Controlled HTTP repository: never consults public model servers. */
final class SyntheticModelRepository implements AutoCloseable {
    final HttpServer server;
    final ConcurrentHashMap<String,String> files=new ConcurrentHashMap<>();
    final AtomicInteger requests=new AtomicInteger();
    SyntheticModelRepository() throws Exception {
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/",exchange -> {
            requests.incrementAndGet();
            String value=files.get(exchange.getRequestURI().getPath());
            byte[] bytes=(value==null?"missing":value).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(value==null?404:200,bytes.length);
            try(var stream=exchange.getResponseBody()) { stream.write(bytes); }
        });
        change(100);
        server.start();
    }
    String url() { return "http://"+System.getProperty("netl.modelTestHost","host.docker.internal")+":"+server.getAddress().getPort()+"/"; }
    void change(int maximum) {
        files.put("/RemoteBase.ili", "INTERLIS 2.3;\nTYPE MODEL RemoteBase (en) AT \"https://example.invalid\" VERSION \"2026-10-01\" =\nDOMAIN Number = 0 .. "+maximum+";\nEND RemoteBase.\n");
        files.put("/RemoteTypes.ili", "INTERLIS 2.3;\nTYPE MODEL RemoteTypes (en) AT \"https://example.invalid\" VERSION \"2026-10-01\" =\nIMPORTS RemoteBase;\nDOMAIN Value EXTENDS RemoteBase.Number = 0 .. 50;\nEND RemoteTypes.\nTYPE MODEL RemoteExtra (en) AT \"https://example.invalid\" VERSION \"2026-10-01\" =\nDOMAIN Extra = TEXT*10;\nEND RemoteExtra.\n");
        files.put("/ilimodels.xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <TRANSFER xmlns="http://www.interlis.ch/INTERLIS2.3">
            <HEADERSECTION SENDER="NETL-synthetic-test" VERSION="2.3"><MODELS><MODEL NAME="IliRepository20" VERSION="2020-01-15" URI="http://models.interlis.ch/core"/></MODELS></HEADERSECTION>
            <DATASECTION><IliRepository20.RepositoryIndex BID="b1">
            <IliRepository20.RepositoryIndex.ModelMetadata TID="1"><Name>RemoteBase</Name><SchemaLanguage>ili2_3</SchemaLanguage><File>RemoteBase.ili</File><Version>2026-10-01</Version><publishingDate>2026-10-01</publishingDate><Issuer>https://example.invalid</Issuer><browseOnly>false</browseOnly></IliRepository20.RepositoryIndex.ModelMetadata>
            <IliRepository20.RepositoryIndex.ModelMetadata TID="2"><Name>RemoteTypes</Name><SchemaLanguage>ili2_3</SchemaLanguage><File>RemoteTypes.ili</File><Version>2026-10-01</Version><publishingDate>2026-10-01</publishingDate><dependsOnModel><IliRepository20.ModelName_><value>RemoteBase</value></IliRepository20.ModelName_></dependsOnModel><Issuer>https://example.invalid</Issuer><browseOnly>false</browseOnly></IliRepository20.RepositoryIndex.ModelMetadata>
            </IliRepository20.RepositoryIndex></DATASECTION></TRANSFER>
            """);
    }
    public void close() { server.stop(0); }
}
