package bench;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.ReferenceCountUtil;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;

/**
 * In-memory TLS 1.2 client/server pair. Every hop of network bytes between peers
 * sleeps {@code latencyMs} to simulate wire latency.
 */
final class Link {
    static final String PROTOCOL = "TLSv1.2";
    static final List<String> CIPHERS = Collections.singletonList("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256");
    private static final int BUF = 1 << 17;
    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

    final long latencyMs;
    // net buffers are kept in write mode: position == bytes pending
    private final ByteBuffer cOut = ByteBuffer.allocateDirect(BUF), sOut = ByteBuffer.allocateDirect(BUF);
    private final ByteBuffer cIn = ByteBuffer.allocateDirect(BUF), sIn = ByteBuffer.allocateDirect(BUF);
    private final ByteBuffer cApp = ByteBuffer.allocateDirect(BUF), sApp = ByteBuffer.allocateDirect(BUF);
    SSLEngine client, server;

    Link(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    static SslProvider provider(String impl) {
        switch (impl) {
            case "jdk": return SslProvider.JDK;
            case "tcnative":
                OpenSsl.ensureAvailability();
                return SslProvider.OPENSSL_REFCNT;
            default: throw new IllegalArgumentException(impl);
        }
    }

    static SslContext serverContext(SslProvider p) throws SSLException {
        return SslContextBuilder.forServer(res("cert.pem"), res("key.pem"))
                .sslProvider(p).protocols(PROTOCOL).ciphers(CIPHERS).build();
    }

    static SslContext clientContext(SslProvider p) throws SSLException {
        return SslContextBuilder.forClient().trustManager(res("cert.pem"))
                .sslProvider(p).protocols(PROTOCOL).ciphers(CIPHERS).build();
    }

    private static InputStream res(String name) {
        return Link.class.getClassLoader().getResourceAsStream(name);
    }

    /** New engines without peer host/port, so every handshake is full (no resumption). */
    void open(SslContext cc, SslContext sc) {
        client = cc.newEngine(ByteBufAllocator.DEFAULT);
        server = sc.newEngine(ByteBufAllocator.DEFAULT);
        cOut.clear(); sOut.clear(); cIn.clear(); sIn.clear(); cApp.clear(); sApp.clear();
    }

    void close() {
        ReferenceCountUtil.release(client);
        ReferenceCountUtil.release(server);
        client = server = null;
    }

    void handshake() throws SSLException {
        client.beginHandshake();
        server.beginHandshake();
        for (int i = 0; i < 100; i++) {
            step(client, cIn, cOut, cApp);
            deliver(cOut, sIn);
            step(server, sIn, sOut, sApp);
            deliver(sOut, cIn);
            if (idle(client) && idle(server) && cIn.position() == 0 && sIn.position() == 0) return;
        }
        throw new IllegalStateException("handshake stuck: c=" + client.getHandshakeStatus() + " s=" + server.getHandshakeStatus());
    }

    private static boolean idle(SSLEngine e) {
        HandshakeStatus hs = e.getHandshakeStatus();
        return hs == HandshakeStatus.NOT_HANDSHAKING || hs == HandshakeStatus.FINISHED;
    }

    private static void step(SSLEngine e, ByteBuffer netIn, ByteBuffer netOut, ByteBuffer app) throws SSLException {
        for (;;) {
            HandshakeStatus hs = e.getHandshakeStatus();
            SSLEngineResult r;
            switch (hs) {
                case NOT_HANDSHAKING:
                case FINISHED:
                    return;
                case NEED_TASK:
                    Runnable t;
                    while ((t = e.getDelegatedTask()) != null) t.run();
                    continue;
                case NEED_WRAP:
                    r = e.wrap(EMPTY, netOut);
                    break;
                default: // NEED_UNWRAP, NEED_UNWRAP_AGAIN (9+)
                    netIn.flip();
                    try {
                        r = e.unwrap(netIn, app);
                    } finally {
                        netIn.compact();
                    }
                    if (r.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) return;
            }
            check(r);
            if (r.bytesConsumed() == 0 && r.bytesProduced() == 0 && e.getHandshakeStatus() == hs) return;
        }
    }

    private static void check(SSLEngineResult r) {
        if (r.getStatus() != SSLEngineResult.Status.OK) throw new IllegalStateException(r.toString());
    }

    private void deliver(ByteBuffer from, ByteBuffer to) {
        if (from.position() == 0) return;
        sleep(latencyMs);
        from.flip();
        to.put(from);
        from.clear();
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
