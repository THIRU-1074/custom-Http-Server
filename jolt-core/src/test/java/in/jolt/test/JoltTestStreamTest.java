package in.jolt.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.App;
import in.jolt.EventStream;
import in.jolt.Jolt;
import in.jolt.ResponseStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class JoltTestStreamTest {

    @Test
    void capturesSseEventsWithIdsAndMultiLineData() {
        App app = Jolt.create();
        app.get(
                "/events",
                (req, res) -> {
                    EventStream s = res.sse();
                    s.retry(Duration.ofSeconds(1));
                    s.comment("hello");
                    s.event("1", "seat", "A1");
                    s.event("two\nlines");
                    s.event("3", null, "");
                });
        var r = JoltTest.of(app).get("/events").expect(200);
        assertEquals("text/event-stream", r.header("Content-Type"));
        assertEquals(
                List.of(
                        new JoltTest.Event("1", "seat", "A1"),
                        new JoltTest.Event(null, null, "two\nlines"),
                        new JoltTest.Event("3", null, "")),
                r.events());
        assertTrue(r.text().startsWith("retry: 1000\n\n: hello\n\nid: 1\n"), r.text());
    }

    @Test
    void capturesChunkedStreamText() {
        App app = Jolt.create();
        app.get(
                "/s",
                (req, res) -> {
                    ResponseStream s = res.stream();
                    s.write("hel");
                    s.write("lo".getBytes());
                    s.close();
                });
        var r = JoltTest.of(app).get("/s").expect(200).body("hello");
        assertEquals("hello", new String(r.bytes()));
        assertEquals(List.of(), r.events());
    }

    @Test
    void maxEventsEndsAnInfiniteLoopLikeADisconnect() {
        AtomicBoolean ioFailure = new AtomicBoolean();
        App app = Jolt.create();
        app.get(
                "/ticks",
                (req, res) -> {
                    EventStream s = res.sse();
                    try {
                        for (int i = 0; ; i++) {
                            s.comment("keep-alive");
                            s.event(String.valueOf(i), "tick", "n" + i);
                        }
                    } catch (IOException gone) {
                        ioFailure.set(true);
                    }
                });
        var events = JoltTest.of(app).get("/ticks").maxEvents(3).send().events();
        assertEquals(3, events.size());
        assertEquals(new JoltTest.Event("2", "tick", "n2"), events.get(2));
        assertTrue(ioFailure.get());
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void streamTimeoutEndsAHandlerThatWaits() {
        AtomicInteger writes = new AtomicInteger();
        App app = Jolt.create();
        app.get(
                "/wait",
                (req, res) -> {
                    EventStream s = res.sse();
                    s.event("ready");
                    writes.incrementAndGet();
                    try {
                        Thread.sleep(30_000);
                    } catch (InterruptedException e) {
                        s.event("late");
                        writes.incrementAndGet();
                    }
                });
        long start = System.nanoTime();
        var r = JoltTest.of(app).get("/wait").streamTimeout(Duration.ofMillis(100)).send();
        assertTrue(System.nanoTime() - start < Duration.ofSeconds(10).toNanos());
        assertEquals(List.of(new JoltTest.Event(null, null, "ready")), r.events());
        assertEquals(1, writes.get());
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void aHandlerThatThrowsAfterStartingKeepsWhatWasSent() {
        App app = Jolt.create();
        app.get(
                "/boom",
                (req, res) -> {
                    res.sse().event("partial", "x");
                    throw new IllegalStateException("boom");
                });
        var r = JoltTest.of(app).get("/boom").expect(200);
        assertEquals(List.of(new JoltTest.Event(null, "partial", "x")), r.events());
    }

    @Test
    void ordinaryResponsesAreUnchanged() {
        App app = Jolt.create();
        app.get("/t", (req, res) -> res.text("plain"));
        var r = JoltTest.of(app).get("/t").maxEvents(1).expect(200).body("plain");
        assertEquals(List.of(), r.events());
    }
}
