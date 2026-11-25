package in.jolt;
public class BenchApp {
    public static void main(String[] a) {
        Jolt.GET("/ping", (req, res) -> { JSON j = new JSON(); j.add("ok", "true"); res.body = j; });
        Jolt.listen(8080, () -> System.out.println("v0.1 listening"));
    }
}
