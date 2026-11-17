package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.test.JoltTest;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class JoltTestStreamingTest {

    @Test
    void sseEventsAreParsed() {
        App app = Jolt.create();
        app.get(
                "/feed",
                (req, res) -> {
                    EventStream s = res.sse();
                    s.comment("hello");
                    s.event("seat", "A1");
                    s.event("plain");
                    s.close();
                });
        var result = JoltTest.of(app).get("/feed").expect(200);
        result.header("Content-Type", "text/event-stream");
        assertEquals(
                List.of(
                        new JoltTest.Event("seat", "A1", null),
                        new JoltTest.Event(null, "plain", null)),
                result.events());
        assertTrue(result.text().startsWith(": hello\n\n"));
        assertEquals(result.text().length(), result.bytes().length);
    }

    @Test
    void multiLineDataAndIdsAreParsed() {
        App app = Jolt.create();
        app.get(
                "/feed",
                (req, res) -> {
                    res.sse().event("note", "one\ntwo\r\nthree");
                    res.stream().write("id: 7\nevent: raw\ndata: x\n\n").close();
                });
        var events = JoltTest.of(app).get("/feed").send().events();
        assertEquals("one\ntwo\nthree", events.get(0).data());
        assertEquals(new JoltTest.Event("raw", "x", "7"), events.get(1));
    }

    @Test
    void streamedTextAndBytesAreCaptured() {
        App app = Jolt.create();
        app.get(
                "/log",
                (req, res) -> {
                    res.status(202).header("X-Part", "1");
                    ResponseStream s = res.stream();
                    s.write("alpha ");
                    s.write(new byte[] {'b', 'e', 't', 'a'});
                    s.write("");
                    s.close();
                });
        var result = JoltTest.of(app).get("/log").expect(202);
        result.header("X-Part", "1").body("alpha beta");
        assertEquals("alpha beta", new String(result.bytes()));
        assertEquals(List.of(), result.events());
        assertNull(result.streamError());
    }

    @Test
    void maxEventsDisconnectsAnEndlessHandler() {
        AtomicInteger sent = new AtomicInteger();
        App app = Jolt.create();
        app.get(
                "/ticks",
                (req, res) -> {
                    EventStream s = res.sse();
                    try {
                        for (int i = 0; ; i++) {
                            s.event("tick", String.valueOf(i));
                            sent.incrementAndGet();
                        }
                    } catch (IOException gone) {
                        return;
                    }
                });
        var result = JoltTest.of(app).get("/ticks").maxEvents(3).expect(200);
        assertEquals(3, result.events().size());
        assertEquals("2", result.events().get(2).data());
        assertEquals(3, sent.get());
        assertNull(result.streamError());
    }

    @Test
    void timeoutEndsAHandlerBlockedBetweenEvents() {
        App app = Jolt.create();
        app.get(
                "/slow",
                (req, res) -> {
                    EventStream s = res.sse();
                    s.event("first");
                    Thread.sleep(60_000);
                    s.event("never");
                });
        long start = System.nanoTime();
        var result = JoltTest.of(app).get("/slow").timeout(Duration.ofMillis(200)).send();
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 10);
        assertEquals(List.of(new JoltTest.Event(null, "first", null)), result.events());
    }

    @Test
    void handlerFailureAfterTheStreamStartedKeepsTheOutput() {
        App app = Jolt.create();
        app.get(
                "/bad",
                (req, res) -> {
                    res.sse().event("ok", "1");
                    throw new IllegalStateException("boom");
                });
        var result = JoltTest.of(app).get("/bad").send();
        assertEquals(200, result.status());
        assertEquals(List.of(new JoltTest.Event("ok", "1", null)), result.events());
        assertInstanceOf(IllegalStateException.class, result.streamError());
    }

    @Test
    void handlerFailureBeforeTheStreamIsAnOrdinaryError() {
        App app = Jolt.create();
        app.get(
                "/bad",
                (req, res) -> {
                    throw new IllegalStateException("boom");
                });
        var result = JoltTest.of(app).get("/bad").expect(500);
        assertNull(result.streamError());
        assertEquals(List.of(), result.events());
    }

    @Test
    void nonStreamingResponsesAreUnchanged() {
        App app = Jolt.create();
        app.get("/x", (req, res) -> res.text("hi"));
        var result = JoltTest.of(app).get("/x").expect(200).body("hi");
        assertEquals(List.of(), result.events());
    }

    @Test
    void streamingStillRejectsABodylessStatus() {
        App app = Jolt.create();
        app.get(
                "/no",
                (req, res) -> {
                    res.status(204).sse();
                });
        assertEquals(500, JoltTest.of(app).get("/no").send().status());
    }

    @Test
    void limitsMustBePositive() {
        var call = JoltTest.of(Jolt.create()).get("/");
        assertThrows(IllegalArgumentException.class, () -> call.maxEvents(0));
        assertThrows(IllegalArgumentException.class, () -> call.timeout(Duration.ZERO));
    }
}
