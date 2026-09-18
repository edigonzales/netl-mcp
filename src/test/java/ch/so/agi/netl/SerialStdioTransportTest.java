package ch.so.agi.netl;

import io.modelcontextprotocol.spec.*;
import io.modelcontextprotocol.json.TypeRef;
import reactor.core.publisher.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SerialStdioTransportTest {
    @Test void asynchronousSendsCannotOverlap() {
        var active=new AtomicInteger(); var maximum=new AtomicInteger(); var sent=new AtomicInteger();
        McpServerTransport delegate=new McpServerTransport() {
            public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
                return Mono.defer(() -> {
                    maximum.accumulateAndGet(active.incrementAndGet(),Math::max);
                    return Mono.delay(Duration.ofMillis(2)).doOnNext(n -> { active.decrementAndGet(); sent.incrementAndGet(); }).then();
                });
            }
            public <T> T unmarshalFrom(Object o,TypeRef<T> t) { throw new UnsupportedOperationException(); }
            public Mono<Void> closeGracefully() { return Mono.empty(); }
        };
        var sender=new SerialStdioTransport.SerializedSender(delegate);
        try {
            Flux.range(0,100).flatMap(i -> sender.sendMessage(new McpSchema.JSONRPCNotification("2.0","test",Map.of("i",i))),100)
                .blockLast(Duration.ofSeconds(10));
            assertEquals(100,sent.get()); assertEquals(1,maximum.get());
        } finally { sender.close(); }
    }
}
