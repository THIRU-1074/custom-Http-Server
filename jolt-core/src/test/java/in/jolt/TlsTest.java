package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TlsTest {

    private static final char[] PASSWORD = "changeit".toCharArray();

    @TempDir static Path dir;
    static Path keystore;

    @BeforeAll
    static void generateKeystore() throws Exception {
        keystore = dir.resolve("test.p12");
        String keytool =
                Path.of(System.getProperty("java.home"), "bin", "keytool")
                        .toAbsolutePath()
                        .toString();
        Process p =
                new ProcessBuilder(
                                keytool,
                                "-genkeypair",
                                "-alias",
                                "jolt",
                                "-keyalg",
                                "RSA",
                                "-keysize",
                                "2048",
                                "-dname",
                                "CN=localhost",
                                "-ext",
                                "san=dns:localhost,ip:127.0.0.1",
                                "-validity",
                                "2",
                                "-storetype",
                                "PKCS12",
                                "-keystore",
                                keystore.toString(),
                                "-storepass",
                                new String(PASSWORD))
                        .redirectErrorStream(true)
                        .start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor());
    }

    private static SSLContext trustAll() throws Exception {
        TrustManager all =
                new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] c, String a) {}

                    public void checkServerTrusted(X509Certificate[] c, String a) {}

                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[] {all}, null);
        return ctx;
    }

    private static App app() {
        App app = Jolt.create(JoltConfig.defaults().tls(keystore, PASSWORD));
        app.get("/scheme", (req, res) -> res.text(req.scheme()));
        return app;
    }

    @Test
    void servesHttpsAndReportsTheScheme() throws Exception {
        HttpClient client = HttpClient.newBuilder().sslContext(trustAll()).build();
        try (Server server = app().listen(0)) {
            HttpResponse<String> r =
                    client.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    "https://127.0.0.1:"
                                                            + server.port()
                                                            + "/scheme"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode());
            assertEquals("https", r.body());
            String protocol = r.sslSession().orElseThrow().getProtocol();
            assertTrue(protocol.equals("TLSv1.3") || protocol.equals("TLSv1.2"), protocol);
            String suite = r.sslSession().orElseThrow().getCipherSuite();
            assertTrue(suite.contains("GCM") || suite.contains("CHACHA20"), suite);
        }
    }

    @Test
    void oldProtocolsAreRefused() throws Exception {
        try (Server server = app().listen(0);
                SSLSocket s =
                        (SSLSocket)
                                trustAll()
                                        .getSocketFactory()
                                        .createSocket("127.0.0.1", server.port())) {
            s.setSoTimeout(3000);
            s.setEnabledProtocols(new String[] {"TLSv1.1", "TLSv1"});
            assertThrows(IOException.class, s::startHandshake);
        }
    }

    @Test
    void plainHttpToATlsPortFailsFast() throws Exception {
        try (Server server = app().listen(0);
                java.net.Socket c = new java.net.Socket("127.0.0.1", server.port())) {
            c.setSoTimeout(3000);
            long start = System.nanoTime();
            c.getOutputStream().write("GET /scheme HTTP/1.1\r\nHost: t\r\n\r\n".getBytes());
            try {
                c.getInputStream().readAllBytes();
            } catch (IOException closed) {
                // reset by the server is as good as a clean close
            }
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2000);
        }
    }

    @Test
    void plainHttpStaysTheDefault() throws Exception {
        App app = Jolt.create();
        app.get("/scheme", (req, res) -> res.text(req.scheme()));
        try (Server server = app.listen(0);
                RawClient c = new RawClient(server.port())) {
            assertEquals("http", c.get("/scheme").text());
        }
    }

    @Test
    void badKeystorePasswordIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> JoltConfig.defaults().tls(keystore, "wrong".toCharArray()));
    }
}
