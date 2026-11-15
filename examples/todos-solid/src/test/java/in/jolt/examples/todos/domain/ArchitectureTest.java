package in.jolt.examples.todos.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    private static final Path DOMAIN = Path.of("src/main/java/in/jolt/examples/todos/domain");

    private static List<String> imports() throws IOException {
        try (Stream<Path> files = Files.list(DOMAIN)) {
            return files.filter(f -> f.toString().endsWith(".java"))
                    .flatMap(ArchitectureTest::lines)
                    .filter(l -> l.startsWith("import "))
                    .toList();
        }
    }

    private static Stream<String> lines(Path file) {
        try {
            return Files.readAllLines(file).stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void domainSourcesAreFound() throws IOException {
        try (Stream<Path> files = Files.list(DOMAIN)) {
            assertTrue(files.count() >= 4);
        }
    }

    @Test
    void domainNeverImportsTheFramework() throws IOException {
        assertFalse(imports().stream().anyMatch(l -> l.startsWith("import in.jolt.")));
    }

    @Test
    void domainNeverImportsAdaptersOrWeb() throws IOException {
        assertFalse(
                imports().stream().anyMatch(l -> l.contains(".adapters.") || l.contains(".web.")));
    }
}
