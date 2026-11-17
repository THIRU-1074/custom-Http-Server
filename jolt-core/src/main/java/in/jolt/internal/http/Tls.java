package in.jolt.internal.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;

/** TLS setup: PKCS12 loading and the hardened protocol and cipher selection. */
public final class Tls {

    private Tls() {}

    /**
     * @throws IllegalArgumentException if the keystore cannot be read or opened with the password
     */
    public static SSLContext context(Path keystore, char[] password) {
        try (InputStream in = Files.newInputStream(keystore)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(in, password);
            KeyManagerFactory kmf =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, password);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            return ctx;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalArgumentException("Cannot load keystore " + keystore, e);
        }
    }

    /** Limits a listening socket to TLS 1.2 and 1.3 and to the suites {@link #isAllowed}. */
    public static void harden(SSLServerSocket socket) {
        socket.setEnabledProtocols(
                Arrays.stream(socket.getSupportedProtocols())
                        .filter(p -> p.equals("TLSv1.3") || p.equals("TLSv1.2"))
                        .toArray(String[]::new));
        socket.setEnabledCipherSuites(
                Arrays.stream(socket.getEnabledCipherSuites())
                        .filter(Tls::isAllowed)
                        .toArray(String[]::new));
    }

    /** TLS 1.3 suites, and TLS 1.2 suites with forward secrecy and an AEAD cipher. */
    public static boolean isAllowed(String suite) {
        if (suite.startsWith("TLS_AES_") || suite.startsWith("TLS_CHACHA20_")) {
            return true;
        }
        boolean forwardSecret = suite.startsWith("TLS_ECDHE_") || suite.startsWith("TLS_DHE_");
        return forwardSecret && (suite.contains("_GCM_") || suite.contains("CHACHA20"));
    }
}
