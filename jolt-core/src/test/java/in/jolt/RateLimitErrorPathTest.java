package in.jolt;

import in.jolt.test.JoltTest;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RateLimitErrorPathTest {

    private static final String PROBLEM = "application/problem+json; charset=utf-8";

    private static App limited(boolean problem) {
        App app = Jolt.create(JoltConfig.defaults().problemJson(problem));
        app.use(RateLimit.perIp(1, Duration.ofSeconds(60)));
        app.get("/", (req, res) -> res.text("ok"));
        return app;
    }

    @Test
    void rateLimitAnswersProblemJsonAndKeepsRetryAfter() {
        JoltTest t = JoltTest.of(limited(true));
        t.get("/").expect(200);
        t.get("/")
                .expect(429)
                .header("Content-Type", PROBLEM)
                .header("Retry-After", "60")
                .jsonEquals("type", "about:blank")
                .jsonEquals("title", "Too Many Requests")
                .jsonEquals("status", 429);
    }

    @Test
    void rateLimitStaysPlainTextWhenProblemJsonIsOff() {
        JoltTest t = JoltTest.of(limited(false));
        t.get("/").expect(200);
        t.get("/")
                .expect(429)
                .header("Content-Type", "text/plain; charset=utf-8")
                .header("Retry-After", "60")
                .body("Too Many Requests");
    }

    @Test
    void rateLimitRejectionReachesOnError() {
        App app = limited(false);
        app.onError(HttpException.class, e -> Reply.status(e.status(), Map.of("code", "slow")));
        JoltTest t = JoltTest.of(app);
        t.get("/").expect(200);
        t.get("/").expect(429).header("Retry-After", "60").jsonEquals("code", "slow");
    }
}
