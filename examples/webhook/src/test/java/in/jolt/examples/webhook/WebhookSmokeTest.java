package in.jolt.examples.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class WebhookSmokeTest {

    @Test
    void sayHelloOnAFreePort() throws Exception {
        try (var server = NotificationServer.create((u, p) -> false).listen(0)) {
            var req =
                    HttpRequest.newBuilder(
                                    URI.create("http://127.0.0.1:" + server.port() + "/sayHello"))
                            .build();
            var res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, res.statusCode());
            assertEquals("Hello World", res.body());
        }
    }
}
