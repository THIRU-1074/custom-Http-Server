package in.jolt.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DevTest {

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

    private Dev.Session start(Path watch) {
        return Dev.start(
                FixtureApp.class.getName(), List.of("-Dfixture.marker=" + marker()), watch);
    }

    @Test
    void restartsChildOnChangeAndKillsTheOldOne() throws Exception {
        Path watched = Files.createDirectories(dir.resolve("src/nested"));
        long oldPid;
        long newPid;
        long millis;
        try (Dev.Session session = start(dir.resolve("src"))) {
            String first = awaitMarker();
            oldPid = Long.parseLong(first.split(" ")[0]);
            assertEquals("1", first.split(" ", 2)[1]);
            Files.delete(marker());

            long t0 = System.nanoTime();
            Files.writeString(watched.resolve("A.java"), "class A {}");
            newPid = Long.parseLong(awaitMarker().split(" ")[0]);
            millis = (System.nanoTime() - t0) / 1_000_000;

            assertNotEquals(oldPid, newPid);
            assertEquals(1, session.restarts());
            assertFalse(ProcessHandle.of(oldPid).map(ProcessHandle::isAlive).orElse(false));
        }
        System.out.println("jolt-dev restart latency ms: " + millis);
        assertFalse(ProcessHandle.of(newPid).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    void burstOfChangesCausesOneRestart() throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        try (Dev.Session session = start(src)) {
            awaitMarker();
            Files.delete(marker());
            for (int i = 0; i < 5; i++) Files.writeString(src.resolve("F" + i + ".java"), "x");
            awaitMarker();
            Thread.sleep(600);
            assertEquals(1, session.restarts());
        }
    }

    @Test
    void closeLeavesNoProcess() throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        long pid;
        try (Dev.Session session = start(src)) {
            pid = Long.parseLong(awaitMarker().split(" ")[0]);
            assertTrue(ProcessHandle.of(pid).isPresent());
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }
}
