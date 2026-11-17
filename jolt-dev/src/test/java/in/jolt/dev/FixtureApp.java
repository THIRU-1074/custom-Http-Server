package in.jolt.dev;

import java.nio.file.Files;
import java.nio.file.Path;

public final class FixtureApp {

    private FixtureApp() {}

    public static void main(String[] args) throws Exception {
        String record =
                ProcessHandle.current().pid() + " " + System.getenv().getOrDefault("JOLT_DEV", "");
        Files.writeString(Path.of(System.getProperty("fixture.marker")), record);
        Thread.sleep(Long.MAX_VALUE);
    }
}
