package bench;

import io.netty.handler.ssl.OpenSsl;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;

final class Verify {
    private Verify() {
    }

    static void session(String impl, SSLEngine client) {
        SSLSession s = client.getSession();
        if (!Link.PROTOCOL.equals(s.getProtocol()) || !Link.CIPHERS.contains(s.getCipherSuite())) {
            throw new IllegalStateException("unexpected session: " + s.getProtocol() + " " + s.getCipherSuite());
        }
        String lib = "tcnative".equals(impl) ? OpenSsl.versionString() : "SunJSSE";
        System.out.println("# " + impl + " (" + lib + ", engine " + client.getClass().getSimpleName() + ") on Java "
                + System.getProperty("java.version") + ": " + s.getProtocol() + " " + s.getCipherSuite());
    }
}
