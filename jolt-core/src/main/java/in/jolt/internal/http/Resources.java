package in.jolt.internal.http;

import in.jolt.HttpException;
import in.jolt.HttpStatus;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

/** A read-only tree of files for static serving and templates: a directory or a class-path root. */
public abstract sealed class Resources {

    /** Reads {@code length} bytes from {@code start}. */
    @FunctionalInterface
    public interface Body {
        byte[] read(long start, int length) throws IOException;
    }

    /** A regular file; {@code modified} is null when unknown. */
    public record Resource(String name, long size, Instant modified, String tag, Body body) {}

    private Resources() {}

    /** Files under {@code root}, which must stay the base after symlink resolution. */
    public static Resources dir(Path root) {
        return new Dir(root);
    }

    /**
     * Resources under {@code root} (for example {@code /static}) of {@code loader} (Jolt's own if
     * null), from exploded directories or {@code jar:file:} URLs.
     *
     * @throws IllegalArgumentException if the root is empty or has an empty, dot or illegal segment
     */
    public static Resources classpath(String root, ClassLoader loader) {
        return new Classpath(root, loader);
    }

    /**
     * Finds {@code relative} under this root.
     *
     * @throws HttpException 403 if the path tries to escape the root, 404 if it is missing, not a
     *     regular file or has a dotfile segment
     */
    public abstract Resource find(String relative);

    /**
     * The regular file at {@code file}.
     *
     * @throws HttpException 404 if it is not a regular file
     */
    public static Resource file(Path file) throws IOException {
        BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
        if (!a.isRegularFile()) {
            throw notFound();
        }
        Instant modified = a.lastModifiedTime().toInstant();
        long size = a.size();
        return new Resource(
                file.getFileName().toString(),
                size,
                modified,
                "W/\"" + Long.toHexString(size) + "-" + modified.toEpochMilli() + "\"",
                (start, length) ->
                        start == 0 && length == size
                                ? Files.readAllBytes(file)
                                : readRange(file, start, length));
    }

    private static byte[] readRange(Path file, long start, int length) throws IOException {
        try (var ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(length);
            ch.position(start);
            while (buf.hasRemaining() && ch.read(buf) >= 0) {
                // keep reading until the range is full or the file ends
            }
            return buf.array();
        }
    }

    private static HttpException notFound() {
        return new HttpException(HttpStatus.NOT_FOUND, "Not Found");
    }

    private static HttpException forbidden() {
        return new HttpException(HttpStatus.FORBIDDEN, "Forbidden");
    }

    private static final class Dir extends Resources {
        private final Path root;

        Dir(Path root) {
            this.root = root;
        }

        @Override
        public Resource find(String relative) {
            if (relative.indexOf('\0') >= 0) {
                throw forbidden();
            }
            try {
                Path base = root.toRealPath();
                String rel = relative;
                while (rel.startsWith("/") || rel.startsWith("\\")) {
                    rel = rel.substring(1);
                }
                Path target = base.resolve(rel).normalize();
                if (!target.startsWith(base)) {
                    throw forbidden();
                }
                for (Path segment : base.relativize(target)) {
                    if (segment.toString().startsWith(".")) {
                        throw notFound();
                    }
                }
                Path real = target.toRealPath();
                if (!real.startsWith(base)) {
                    throw forbidden();
                }
                return file(real);
            } catch (InvalidPathException | IOException e) {
                throw notFound();
            }
        }
    }

    private static final class Classpath extends Resources {
        private final String root;
        private final ClassLoader loader;

        Classpath(String root, ClassLoader loader) {
            try {
                this.root = clean(root);
            } catch (HttpException e) {
                throw new IllegalArgumentException("Illegal class-path root: " + root);
            }
            this.loader = loader != null ? loader : Resources.class.getClassLoader();
        }

        @Override
        public Resource find(String relative) {
            String rel = clean(relative);
            String name = root + "/" + rel;
            URL url = loader.getResource(name);
            if (url == null) {
                throw notFound();
            }
            try {
                if (url.getProtocol().equals("file")) {
                    Path base = Path.of(url.toURI());
                    for (int i = rel.split("/").length; i > 0; i--) {
                        base = base.getParent();
                    }
                    return new Dir(base).find(rel);
                }
                URLConnection c = url.getProtocol().equals("jar") ? url.openConnection() : null;
                if (c instanceof JarURLConnection j
                        && j.getJarFileURL().getProtocol().equals("file")
                        && name.equals(j.getEntryName())) {
                    return entry(Path.of(j.getJarFileURL().toURI()), name);
                }
            } catch (URISyntaxException | InvalidPathException | NoSuchFileException e) {
                throw notFound();
            } catch (IOException e) {
                throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot read file");
            }
            throw notFound();
        }

        private static Resource entry(Path jar, String name) throws IOException {
            JarEntry e;
            try (JarFile jf = open(jar)) {
                e = jf.getJarEntry(name);
            }
            if (e == null || e.isDirectory()) {
                throw notFound();
            }
            long size = e.getSize();
            long time = e.getTime();
            return new Resource(
                    name.substring(name.lastIndexOf('/') + 1),
                    size,
                    time < 0 ? null : Instant.ofEpochMilli(time),
                    "W/\"" + Long.toHexString(size) + "-" + Long.toHexString(e.getCrc()) + "\"",
                    (start, length) -> {
                        try (JarFile jf = open(jar);
                                InputStream in = jf.getInputStream(jf.getEntry(name))) {
                            in.skipNBytes(start);
                            return in.readNBytes(length);
                        }
                    });
        }

        private static JarFile open(Path jar) throws IOException {
            return new JarFile(jar.toFile(), false, ZipFile.OPEN_READ);
        }

        private static String clean(String path) {
            StringBuilder out = new StringBuilder();
            for (String segment : path.split("/")) {
                if (segment.isEmpty()) {
                    continue;
                }
                if (segment.equals("..")
                        || segment.indexOf('\\') >= 0
                        || segment.indexOf('\0') >= 0) {
                    throw forbidden();
                }
                if (segment.startsWith(".") || segment.indexOf(':') >= 0) {
                    throw notFound();
                }
                out.append(out.isEmpty() ? "" : "/").append(segment);
            }
            if (out.isEmpty()) {
                throw notFound();
            }
            return out.toString();
        }
    }
}
