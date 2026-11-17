package in.jolt.internal.limit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class FixedWindowTest {

    private final AtomicLong now = new AtomicLong();

    @Test
    void allowsUpToMaxThenReportsTimeLeft() {
        FixedWindow w = new FixedWindow(2, 1_000, 10, now::get);
        assertEquals(-1, w.acquire("a"));
        now.set(100);
        assertEquals(-1, w.acquire("a"));
        now.set(400);
        assertEquals(600, w.acquire("a"));
    }

    @Test
    void resetsWhenTheWindowEnds() {
        FixedWindow w = new FixedWindow(1, 1_000, 10, now::get);
        w.acquire("a");
        now.set(1_000);
        assertEquals(-1, w.acquire("a"));
    }

    @Test
    void keysAreIndependent() {
        FixedWindow w = new FixedWindow(1, 1_000, 10, now::get);
        assertEquals(-1, w.acquire("a"));
        assertEquals(-1, w.acquire("b"));
    }

    @Test
    void evictsTheLeastRecentlyUsedKeyWhenFull() {
        FixedWindow w = new FixedWindow(1, 1_000, 1, now::get);
        w.acquire("a");
        w.acquire("b");
        assertEquals(-1, w.acquire("a"));
    }

    @Test
    void secondsRoundUpAndAreAtLeastOne() {
        assertEquals(1, FixedWindow.seconds(1));
        assertEquals(2, FixedWindow.seconds(1_000_000_001L));
    }
}
