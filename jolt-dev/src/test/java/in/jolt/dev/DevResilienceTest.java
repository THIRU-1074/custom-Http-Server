package in.jolt.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DevResilienceTest {

    @TempDir Path dir;

    private Path marker() {
        return dir.resolve("marker.txt");
    }

    private String awaitMarker() throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                String s = Files.readString(marker());
                if (s.contains(" ")) return s;
            } catch (IOException notYet) {
                Thread.sleep(10);
            }
        }
        throw new AssertionError("child never wrote its marker");
    }

    private List<String> args() {
        return List.of("-Dfixture.marker=" + marker());
    }

    @Test
    void failedSpawnIsRetriedOnTheNextChange() throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        AtomicInteger launches = new AtomicInteger();
        Dev.Launcher flaky =
                pb -> {
                    if (launches.incrementAndGet() == 2) throw new IOException("no java");
                    return pb.start();
                };
        try (Dev.Session session = Dev.start(FixtureApp.class.getName(), args(), flaky, src)) {
            awaitMarker();
            Files.delete(marker());
            Files.writeString(src.resolve("A.java"), "a");
            Thread.sleep(700);
            assertEquals(0, session.restarts());
            assertFalse(Files.exists(marker()));

            Files.writeString(src.resolve("B.java"), "b");
            awaitMarker();
            assertEquals(1, session.restarts());
        }
    }

    @Test
    void initialSpawnFailureThrowsAndLeavesNothingRunning() {
        Dev.Launcher broken =
                pb -> {
                    throw new IOException("no java");
                };
        assertThrows(
                IllegalStateException.class,
                () -> Dev.start(FixtureApp.class.getName(), args(), broken, dir));
        assertFalse(
                Thread.getAllStackTraces().keySet().stream()
                        .anyMatch(t -> t.getName().equals("jolt-dev-watch") && t.isAlive()));
    }

    @Test
    void missingDirectoriesAreSkipped() throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        try (Dev.Session session =
                Dev.start(
                        FixtureApp.class.getName(),
                        args(),
                        ProcessBuilder::start,
                        dir.resolve("not-there"),
                        src)) {
            awaitMarker();
            Files.delete(marker());
            Files.writeString(src.resolve("A.java"), "a");
            awaitMarker();
            assertEquals(1, session.restarts());
        }
    }

    @Test
    void classpathOverrideWins() {
        assertEquals("a:b", Dev.classpath("x:y", "a:b"));
        assertEquals("x:y", Dev.classpath("x:y", null));
    }

    @Test
    void execJavaClasspathIsRejectedWithGuidance() {
        var e =
                assertThrows(
                        IllegalStateException.class,
                        () -> Dev.classpath("/m2/plexus-classworlds-2.7.0.jar", null));
        assertTrue(e.getMessage().contains("exec:exec"));
        assertFalse(e.getMessage().contains("\n"));
        assertEquals(
                "/m2/plexus-classworlds-2.7.0.jar",
                Dev.classpath("x", "/m2/plexus-classworlds-2.7.0.jar"));
    }
}
