package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestConfigTest {

    private static final String TYPE = "multipart/form-data; boundary=b";
    private static final byte[] TWO_PARTS =
            ("--b\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n1\r\n"
                            + "--b\r\nContent-Disposition: form-data; name=\"c\"\r\n\r\n2\r\n--b--\r\n")
                    .getBytes(StandardCharsets.UTF_8);

    private static Request request(JoltConfig config) {
        return new Request(
                "POST",
                "/",
                "",
                Map.of("Content-Type", TYPE),
                TWO_PARTS,
                config.jsonMapper(),
                "1.1.1.1",
                null,
                null,
                config);
    }

    @Test
    void configComesThroughTheConstructor() {
        assertEquals(2, request(JoltConfig.defaults()).parts().size());
        var e =
                assertThrows(
                        HttpException.class,
                        () -> request(JoltConfig.defaults().maxParts(1)).parts());
        assertEquals(413, e.status());
    }

    @Test
    void olderConstructorsUseTheDefaultLimits() {
        var mapper = JoltConfig.defaults().jsonMapper();
        Request r = new Request("POST", "/", "", Map.of("Content-Type", TYPE), TWO_PARTS, mapper);
        assertEquals(2, r.parts().size());
        Request r2 =
                new Request(
                        "POST",
                        "/",
                        "",
                        Map.of("Content-Type", TYPE),
                        TWO_PARTS,
                        mapper,
                        "9.9.9.9");
        assertEquals(2, r2.parts().size());
    }

    @Test
    void requestHasNoConfigSetter() {
        for (Method m : Request.class.getDeclaredMethods()) {
            assertEquals(false, m.getName().equals("config"), m.toString());
        }
    }
}
