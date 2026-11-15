package in.jolt.examples.attendance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.auth.JwtAuth;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class AttendanceSmokeTest {

    @Test
    void servesClassInfoOnAFreePort() throws Exception {
        try (var server =
                AttendanceApp.create(JwtAuth.hs256("0123456789abcdef0123456789abcdef")).listen(0)) {
            var req =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            "http://127.0.0.1:"
                                                    + server.port()
                                                    + "/getClassesInfo"))
                            .build();
            var res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, res.statusCode());
            assertTrue(res.body().contains("22BEC1473"));
        }
    }
}
