package todo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;

class TodoAppTest {

    @Test
    void crudAuthAndErrors() {
        JavalinTest.test(
                TodoApp.create(),
                (server, client) -> {
                    String auth = "Bearer secret";
                    assertEquals(401, client.get("/todos").code());
                    assertEquals(
                            400,
                            client.post("/todos", "{}", r -> r.header("Authorization", auth))
                                    .code());
                    assertEquals(
                            201,
                            client.post(
                                            "/todos",
                                            "{\"title\":\"a\"}",
                                            r -> r.header("Authorization", auth))
                                    .code());
                    assertEquals(
                            200, client.get("/todos/1", r -> r.header("Authorization", auth)).code());
                    assertEquals(
                            404, client.get("/todos/9", r -> r.header("Authorization", auth)).code());
                    assertEquals(
                            204,
                            client.delete("/todos/1", null, r -> r.header("Authorization", auth))
                                    .code());
                });
    }
}
