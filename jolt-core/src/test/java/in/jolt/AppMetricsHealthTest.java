package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.test.JoltTest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AppMetricsHealthTest {

    private static String scrape(App app) {
        return JoltTest.of(app).get("/_jolt/metrics").expect(200).text();
    }

    @Test
    void counterAppearsNextToJoltSeries() {
        App app = Jolt.create().exposeMetrics();
        AppMetrics.Counter booked = app.metrics().counter("bookings_confirmed_total", "Bookings.");
        booked.increment();
        booked.add(4);
        assertEquals(5, booked.value());
        String m = scrape(app);
        assertTrue(m.contains("# HELP bookings_confirmed_total Bookings.\n"), m);
        assertTrue(m.contains("# TYPE bookings_confirmed_total counter\n"), m);
        assertTrue(m.contains("\nbookings_confirmed_total 5\n"), m);
        assertTrue(m.contains("jolt_http_requests_total"), m);
    }

    @Test
    void counterRejectsNegativeIncrements() {
        var c = Jolt.create().metrics().counter("c_total", "C.");
        assertThrows(IllegalArgumentException.class, () -> c.add(-1));
    }

    @Test
    void gaugeIsReadOnEveryScrape() {
        App app = Jolt.create().exposeMetrics();
        AtomicLong holds = new AtomicLong(2);
        app.metrics().gauge("seat_holds_active", "Active holds.", holds::get);
        assertTrue(scrape(app).contains("# TYPE seat_holds_active gauge\nseat_holds_active 2\n"));
        holds.set(9);
        assertTrue(scrape(app).contains("\nseat_holds_active 9\n"));
    }

    @Test
    void aFailingGaugeIsOmittedWithoutLeakingTheError() {
        App app = Jolt.create().exposeMetrics();
        app.metrics()
                .gauge(
                        "broken",
                        "Broken.",
                        () -> {
                            throw new IllegalStateException("db password is hunter2");
                        });
        app.metrics().gauge("fine", "Fine.", () -> 1);
        String m = scrape(app);
        assertFalse(m.contains("hunter2"));
        assertFalse(m.contains("\nbroken "));
        assertTrue(m.contains("\nfine 1\n"));
    }

    @Test
    void registeringBeforeExposeMetricsWorks() {
        App app = Jolt.create();
        app.metrics().counter("early_total", "Early.").increment();
        app.exposeMetrics();
        assertTrue(scrape(app).contains("\nearly_total 1\n"));
    }

    @Test
    void registeringAfterListenWorks() {
        App app = Jolt.create().exposeMetrics();
        try (Server server = app.listen(0)) {
            app.metrics().counter("late_total", "Late.").increment();
            assertTrue(scrape(app).contains("\nlate_total 1\n"));
        }
    }

    @Test
    void labelsAreFixedSortedAndEscaped() {
        App app = Jolt.create().exposeMetrics();
        app.metrics()
                .counter("tickets_total", "Tickets.", Map.of("show", "a\"b\\c\nd", "city", "Pune"))
                .increment();
        app.metrics().counter("tickets_total", "Tickets.", Map.of("city", "Goa")).add(2);
        String m = scrape(app);
        assertTrue(m.contains("tickets_total{city=\"Pune\",show=\"a\\\"b\\\\c\\nd\"} 1\n"), m);
        assertTrue(m.contains("tickets_total{city=\"Goa\"} 2\n"), m);
        assertEquals(1, m.split("# TYPE tickets_total", -1).length - 1);
    }

    @Test
    void gaugesTakeLabelsToo() {
        App app = Jolt.create().exposeMetrics();
        app.metrics().gauge("subscribers", "Subscribers.", Map.of("topic", "seats"), () -> 3);
        assertTrue(scrape(app).contains("\nsubscribers{topic=\"seats\"} 3\n"));
    }

    @Test
    void duplicateRegistrationFailsClearly() {
        AppMetrics metrics = Jolt.create().metrics();
        metrics.counter("dup_total", "D.");
        var same =
                assertThrows(IllegalStateException.class, () -> metrics.counter("dup_total", "D."));
        assertTrue(same.getMessage().contains("dup_total"));
        assertThrows(IllegalStateException.class, () -> metrics.gauge("dup_total", "D.", () -> 1));
        metrics.counter("lab_total", "L.", Map.of("a", "1"));
        assertThrows(
                IllegalStateException.class,
                () -> metrics.counter("lab_total", "L.", Map.of("a", "1")));
    }

    @Test
    void namesAreValidatedAgainstThePrometheusGrammar() {
        AppMetrics metrics = Jolt.create().metrics();
        for (String bad :
                new String[] {"", "1abc", "has space", "dash-name", "dot.name", "jolt_mine"}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> metrics.counter(bad, "H."),
                    "name " + bad);
            assertThrows(
                    IllegalArgumentException.class, () -> metrics.gauge(bad, "H.", () -> 0), bad);
        }
        metrics.counter("ok:name_1", "Fine.");
        assertThrows(IllegalArgumentException.class, () -> metrics.counter(null, "H."));
        assertThrows(IllegalArgumentException.class, () -> metrics.counter("x_total", null));
        for (String bad : new String[] {"", "1a", "a-b", "__reserved"}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> metrics.counter("l_total", "H.", Map.of(bad, "v")),
                    "label " + bad);
        }
    }

    @Test
    void healthWithoutChecksIsUnchanged() {
        JoltTest.of(Jolt.create().exposeMetrics())
                .get("/_jolt/health")
                .expect(200)
                .body("{\"status\":\"UP\"}");
    }

    @Test
    void healthReportsEveryCheckWhenAllPass() {
        App app = Jolt.create();
        app.health("database", () -> true).health("cache", () -> true);
        app.exposeMetrics();
        JoltTest.of(app)
                .get("/_jolt/health")
                .expect(200)
                .body("{\"status\":\"UP\",\"checks\":{\"database\":\"UP\",\"cache\":\"UP\"}}");
    }

    @Test
    void aFailingCheckAnswers503AndIsMarkedDown() {
        App app = Jolt.create().exposeMetrics();
        app.health("database", () -> false).health("cache", () -> true);
        var r = JoltTest.of(app).get("/_jolt/health").expect(503);
        r.jsonEquals("status", "DOWN").jsonEquals("checks.database", "DOWN");
        r.jsonEquals("checks.cache", "UP");
    }

    @Test
    void aThrowingCheckIsDownAndNeverLeaksTheError() {
        App app = Jolt.create().exposeMetrics();
        app.health(
                "database",
                () -> {
                    throw new IllegalStateException("jdbc:postgres://user:hunter2@host");
                });
        var r = JoltTest.of(app).get("/_jolt/health").expect(503);
        r.jsonEquals("checks.database", "DOWN");
        assertFalse(r.text().contains("hunter2"));
        assertFalse(r.text().contains("IllegalState"));
    }

    @Test
    void aNullResultCountsAsDown() {
        App app = Jolt.create().exposeMetrics();
        app.health("odd", () -> null);
        JoltTest.of(app).get("/_jolt/health").expect(503).jsonEquals("checks.odd", "DOWN");
    }

    @Test
    void aSlowCheckTimesOutAsDown() {
        App app = Jolt.create().exposeMetrics();
        app.health(
                "slow",
                () -> {
                    try {
                        Thread.sleep(30_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return true;
                },
                Duration.ofMillis(100));
        app.health("quick", () -> true);
        long start = System.nanoTime();
        var r = JoltTest.of(app).get("/_jolt/health").expect(503);
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 10);
        r.jsonEquals("checks.slow", "DOWN").jsonEquals("checks.quick", "UP");
    }

    @Test
    void checksRunOnEachRequest() {
        AtomicInteger calls = new AtomicInteger();
        App app = Jolt.create().exposeMetrics();
        app.health("n", () -> calls.incrementAndGet() < 2);
        JoltTest t = JoltTest.of(app);
        t.get("/_jolt/health").expect(200);
        t.get("/_jolt/health").expect(503);
        assertEquals(2, calls.get());
    }

    @Test
    void healthChecksRegisteredAfterExposeAndAfterListenCount() {
        App app = Jolt.create().exposeMetrics();
        try (Server server = app.listen(0)) {
            app.health("late", () -> false);
            JoltTest.of(app).get("/_jolt/health").expect(503);
        }
    }

    @Test
    void duplicateAndInvalidCheckNamesFail() {
        App app = Jolt.create();
        app.health("db", () -> true);
        var e = assertThrows(IllegalStateException.class, () -> app.health("db", () -> true));
        assertTrue(e.getMessage().contains("db"));
        assertThrows(IllegalArgumentException.class, () -> app.health("", () -> true));
        assertThrows(IllegalArgumentException.class, () -> app.health("a\"b", () -> true));
        assertThrows(NullPointerException.class, () -> app.health("x", null));
        assertThrows(
                IllegalArgumentException.class, () -> app.health("y", () -> true, Duration.ZERO));
    }

    @Test
    void routesAreAbsentWithoutExposeMetrics() {
        App app = Jolt.create();
        app.health("db", () -> true);
        JoltTest.of(app).get("/_jolt/health").expect(404);
    }
}
