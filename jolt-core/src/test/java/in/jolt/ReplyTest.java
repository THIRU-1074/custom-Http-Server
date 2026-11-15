package in.jolt;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import in.jolt.test.JoltTest;
import org.junit.jupiter.api.Test;

class ReplyTest {

    @Test
    void statusMustBeBetween100And599() {
        assertThrows(IllegalArgumentException.class, () -> new Reply<>(99, null));
        assertThrows(IllegalArgumentException.class, () -> Reply.status(600, "x"));
        assertThrows(IllegalArgumentException.class, () -> Reply.status(0, null));
        assertDoesNotThrow(() -> Reply.status(100, null));
        assertDoesNotThrow(() -> Reply.status(599, "x"));
    }

    @Test
    void bodilessStatusesRejectABody() {
        for (int s : new int[] {100, 101, 199, 204, 304}) {
            IllegalArgumentException e =
                    assertThrows(IllegalArgumentException.class, () -> Reply.status(s, "x"));
            assertFalse(e.getMessage().contains("\n"));
            assertDoesNotThrow(() -> Reply.status(s, null));
        }
    }

    @Test
    void typedRoutesAnsweringBodilessStatusesSendNoBody() {
        App app = Jolt.create();
        app.get("/nc", p -> Reply.status(204, null));
        app.get("/nm", p -> Reply.status(304, null));
        JoltTest t = JoltTest.of(app);
        t.get("/nc").expect(204).body("");
        t.get("/nm").expect(304).body("");
    }
}
