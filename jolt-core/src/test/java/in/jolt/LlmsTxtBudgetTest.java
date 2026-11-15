package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LlmsTxtBudgetTest {

    private static final int MAX_CHARS = 7000;

    private static String read(Path... candidates) throws IOException {
        for (Path p : candidates) {
            if (Files.exists(p)) {
                return Files.readString(p, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("not found: " + candidates[0]);
    }

    private static String root() throws IOException {
        return read(Path.of("..", "llms.txt"), Path.of("llms.txt"));
    }

    private static String bundled() throws IOException {
        return read(
                Path.of("src/main/resources/META-INF/llms.txt"),
                Path.of("jolt-core/src/main/resources/META-INF/llms.txt"));
    }

    @Test
    void rootFileStaysWithinBudget() throws IOException {
        int n = root().length();
        assertTrue(
                n <= MAX_CHARS,
                "llms.txt is "
                        + n
                        + " characters, over the "
                        + MAX_CHARS
                        + " character limit. Tighten the text (one line per feature);"
                        + " do not raise the limit. Token-small docs are a Jolt feature.");
    }

    @Test
    void bundledCopyMatchesRoot() throws IOException {
        assertEquals(
                root(),
                bundled(),
                "llms.txt and jolt-core/src/main/resources/META-INF/llms.txt differ."
                        + " Copy the root file over the bundled one.");
    }
}
