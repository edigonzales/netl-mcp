package ch.so.agi.netl;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.*;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.concurrent.*;

/**
 * SDK 2.0.1's STDIO sink uses tryEmitNext and loses concurrent replies with
 * "Failed to enqueue message". Serialize transport sends (not tool execution).
 * Remove this adapter only after the concurrent STDIO regression passes upstream.
 */
final class SerialStdioTransport extends StdioServerTransportProvider {
    SerialStdioTransport(McpJsonMapper mapper) { super(mapper); }
    @Override public void setSessionFactory(McpServerSession.Factory factory) {
        super.setSessionFactory(transport -> factory.create(new SerializedSender(transport)));
    }
    static final class SerializedSender implements McpServerTransport {
        private final McpServerTransport delegate;
        private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task,"netl-stdio-writer"); thread.setDaemon(true); return thread;
        });
        SerializedSender(McpServerTransport delegate) { this.delegate=delegate; }
        @Override public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return Mono.create(sink -> {
                try {
                    writer.execute(() -> {
                        try {
                            // This is a dedicated blocking thread, never a Reactor event-loop.
                            delegate.sendMessage(message).block(Duration.ofSeconds(10));
                            sink.success();
                        } catch (Exception e) { sink.error(e); }
                    });
                } catch (RejectedExecutionException e) { sink.error(e); }
            });
        }
        @Override public <T> T unmarshalFrom(Object data, TypeRef<T> type) { return delegate.unmarshalFrom(data,type); }
        @Override public Mono<Void> closeGracefully() {
            return delegate.closeGracefully().doFinally(signal -> writer.shutdown());
        }
        @Override public void close() { writer.shutdownNow(); delegate.close(); }
    }
}
