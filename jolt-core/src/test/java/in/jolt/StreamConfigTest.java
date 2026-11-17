package in.jolt;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class StreamConfigTest {

    private static final JoltConfig BASE = JoltConfig.defaults();

    @Test
    void streamTimeoutsValidatesArguments() {
        NullPointerException idle =
                assertThrows(
                        NullPointerException.class, () -> BASE.streamTimeouts(null, Duration.ZERO));
        assertEquals("idle", idle.getMessage());
        NullPointerException max =
                assertThrows(
                        NullPointerException.class,
                        () -> BASE.streamTimeouts(Duration.ofSeconds(1), null));
        assertEquals("max", max.getMessage());
        assertThrows(
                IllegalArgumentException.class,
                () -> BASE.streamTimeouts(Duration.ZERO, Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> BASE.streamTimeouts(Duration.ofSeconds(-1), Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> BASE.streamTimeouts(Duration.ofSeconds(1), Duration.ofSeconds(-1)));
    }

    @Test
    void zeroMaxMeansNoCap() {
        JoltConfig c =
                assertDoesNotThrow(() -> BASE.streamTimeouts(Duration.ofSeconds(1), Duration.ZERO));
        assertEquals(Duration.ZERO, c.streamMaxDuration());
    }

    @Test
    void wsLimitsValidatesArguments() {
        assertThrows(IllegalArgumentException.class, () -> BASE.wsLimits(0, 1));
        assertThrows(IllegalArgumentException.class, () -> BASE.wsLimits(1, -1));
        assertEquals(10, BASE.wsLimits(10, 20).wsMaxFrameBytes());
    }
}
