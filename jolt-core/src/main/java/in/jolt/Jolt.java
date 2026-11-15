package in.jolt;

/** Entry point: {@code Jolt.create().get("/", (req, res) -> res.text("hi")).listen(8080)}. */
public final class Jolt {

    private Jolt() {}

    public static App create() {
        return create(JoltConfig.defaults());
    }

    public static App create(JoltConfig config) {
        return new App(config);
    }
}
