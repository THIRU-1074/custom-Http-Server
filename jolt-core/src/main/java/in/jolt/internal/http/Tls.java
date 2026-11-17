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

    /** Limits a listening socket to TLS 1.2 and 1.3 and to AEAD (GCM or ChaCha20) suites. */
    public static void harden(SSLServerSocket socket) {
        socket.setEnabledProtocols(
                Arrays.stream(socket.getSupportedProtocols())
                        .filter(p -> p.equals("TLSv1.3") || p.equals("TLSv1.2"))
                        .toArray(String[]::new));
        socket.setEnabledCipherSuites(
                Arrays.stream(socket.getEnabledCipherSuites())
                        .filter(c -> c.contains("_GCM_") || c.contains("CHACHA20"))
                        .toArray(String[]::new));
    }
}
