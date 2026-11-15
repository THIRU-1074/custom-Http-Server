package in.jolt.internal.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.jolt.JoltConfig;
import org.junit.jupiter.api.Test;

class LimitsTest {

    @Test
    void defaultsComeFromJoltConfig() {
        JoltConfig c = JoltConfig.defaults();
        assertEquals(
                new Limits(
                        c.maxHeaderBytes(),
                        c.maxBodyBytes(),
                        c.maxRequestLineBytes(),
                        c.maxHeaderCount()),
                Limits.from(c));
    }

    @Test
    void overridesAreCarriedOver() {
        Limits l =
                Limits.from(
                        JoltConfig.defaults()
                                .maxHeaderBytes(1)
                                .maxBodyBytes(2)
                                .maxRequestLineBytes(3)
                                .maxHeaderCount(4));
        assertEquals(new Limits(1, 2, 3, 4), l);
    }
}
