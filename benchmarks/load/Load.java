import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;

/** Closed-loop load generator. Usage: {@code java Load.java [url] [concurrency] [seconds]}. */
public class Load {

    public static void main(String[] args) throws Exception {
        URI uri = URI.create(args.length > 0 ? args[0] : "http://localhost:8080/");
        int concurrency = args.length > 1 ? Integer.parseInt(args[1]) : 50;
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 30;

        var client =
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build();
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).build();
        var ok = new LongAdder();
        var failed = new LongAdder();
        var nanos = new LongAdder();
        long end = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();

        long start = System.nanoTime();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < concurrency; i++) {
                pool.execute(
                        () -> {
                            while (System.nanoTime() < end) {
                                long t0 = System.nanoTime();
                                try {
                                    var r =
                                            client.send(
                                                    request, HttpResponse.BodyHandlers.discarding());
                                    if (r.statusCode() < 400) {
                                        ok.increment();
                                        nanos.add(System.nanoTime() - t0);
                                    } else {
                                        failed.increment();
                                    }
                                } catch (Exception e) {
                                    failed.increment();
                                }
                            }
                        });
            }
        }
        double elapsed = (System.nanoTime() - start) / 1e9;
        long n = ok.sum();
        System.out.printf("requests: %d ok, %d failed in %.1f s%n", n, failed.sum(), elapsed);
        System.out.printf("throughput: %.2f req/s%n", n / elapsed);
        System.out.printf("mean latency: %.1f ms%n", n == 0 ? 0 : nanos.sum() / 1e6 / n);
    }
}
