package bench;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslProvider;
import org.openjdk.jmh.annotations.*;

import javax.net.ssl.SSLException;
import java.util.concurrent.TimeUnit;

/** Full TLS 1.3 handshake (engine creation included), no session resumption. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 5)
@Fork(1)
public class HandshakeBench {
    @Param({"jdk", "tcnative"})
    String impl;

    @Param("100")
    long latencyMs;

    SslContext cc, sc;
    Link link;

    @Setup
    public void setup() throws SSLException {
        SslProvider p = Link.provider(impl);
        cc = Link.clientContext(p);
        sc = Link.serverContext(p);
        link = new Link(latencyMs);
        link.open(cc, sc);
        link.handshake();
        Verify.session(impl, link.client);
        link.close();
    }

    @Benchmark
    public Object handshake() throws SSLException {
        link.open(cc, sc);
        try {
            link.handshake();
            return link.client.getHandshakeStatus();
        } finally {
            link.close();
        }
    }
}
