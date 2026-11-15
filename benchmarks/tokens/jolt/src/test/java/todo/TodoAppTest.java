package todo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.jolt.Server;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import org.junit.jupiter.api.Test;

class TodoAppTest {

    private int send(Server s, String method, String path, String body, String auth)
            throws Exception {
        var b =
                HttpRequest.newBuilder(URI.create("http://localhost:" + s.port() + path))
                        .method(method, BodyPublishers.ofString(body));
        if (auth != null) {
            b.header("Authorization", auth);
        }
        return HttpClient.newHttpClient().send(b.build(), BodyHandlers.ofString()).statusCode();
    }

    @Test
    void crudAuthAndErrors() throws Exception {
        try (Server s = TodoApp.create().listen(0)) {
            assertEquals(401, send(s, "GET", "/todos", "", null));
            assertEquals(400, send(s, "POST", "/todos", "{}", "Bearer secret"));
            assertEquals(201, send(s, "POST", "/todos", "{\"title\":\"a\"}", "Bearer secret"));
            assertEquals(200, send(s, "GET", "/todos/1", "", "Bearer secret"));
            assertEquals(404, send(s, "GET", "/todos/9", "", "Bearer secret"));
            assertEquals(204, send(s, "DELETE", "/todos/1", "", "Bearer secret"));
        }
    }
}
