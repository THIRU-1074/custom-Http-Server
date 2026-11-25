import in.jolt.*;
import java.time.Duration;
import java.util.Map;
public class PingApp {
    public static void main(String[] a) {
        JoltConfig cfg = JoltConfig.defaults();
        if (a.length > 0 && a[0].equals("hold")) cfg = cfg.keepAliveTimeout(Duration.ofSeconds(120));
        App app = Jolt.create(cfg);
        app.get("/ping", (req, res) -> res.json(Map.of("ok", true)));
        app.listen(8080);
        System.out.println("0.4.2 listening");
    }
}
