package in.jolt.dev;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Development runner: starts an app in a child JVM that shares this JVM's classpath and restarts it
 * when files under the watched directories change. Dev only; production never loads it.
 */
public final class Dev {

    static final long DEBOUNCE_MILLIS = 150;
    private static final String PREFIX = "[jolt-dev] ";
    private static final System.Logger LOG = System.getLogger(Dev.class.getName());

    @FunctionalInterface
    interface Launcher {
        Process launch(ProcessBuilder pb) throws IOException;
    }

    private Dev() {}

    /**
     * Runs {@code mainClass} in a child JVM and restarts it on changes under {@code watchDirs}
     * (recursively); directories that do not exist yet are skipped with a warning. Blocks until the
     * calling thread is interrupted. The child sees {@code JOLT_DEV=1} and this JVM's classpath, or
     * the one in system property {@code jolt.dev.classpath}. Start it with a plain {@code java -cp}
     * or {@code mvn exec:exec}; {@code mvn exec:java} is rejected.
     */
    public static void run(String mainClass, Path... watchDirs) {
        try (Session session = start(mainClass, List.of(), watchDirs)) {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Starts the child and the watcher without blocking. Close the session to stop both. */
    public static Session start(String mainClass, List<String> jvmArgs, Path... watchDirs) {
        return start(mainClass, jvmArgs, ProcessBuilder::start, watchDirs);
    }

    static Session start(
            String mainClass, List<String> jvmArgs, Launcher launcher, Path... watchDirs) {
        return new Session(mainClass, jvmArgs, launcher, watchDirs);
    }

    static String classpath(String javaClassPath, String override) {
        if (override != null && !override.isBlank()) return override;
        if (javaClassPath.contains("plexus-classworlds")) {
            throw new IllegalStateException(
                    "mvn exec:java has no usable classpath; run: mvn -q compile exec:exec"
                            + " -Dexec.executable=java \"-Dexec.args=-cp %classpath YourDevMain\""
                            + " or set -Djolt.dev.classpath");
        }
        return javaClassPath;
    }

    /** A running dev loop. */
    public static final class Session implements AutoCloseable {

        private final String mainClass;
        private final List<String> jvmArgs;
        private final Launcher launcher;
        private final WatchService watcher;
        private final Thread loop;
        private final AtomicInteger restarts = new AtomicInteger();
        private volatile Process child;
        private volatile boolean closed;

        private Session(String mainClass, List<String> jvmArgs, Launcher launcher, Path[] dirs) {
            this.mainClass = mainClass;
            this.jvmArgs = List.copyOf(jvmArgs);
            this.launcher = launcher;
            try {
                this.watcher = FileSystems.getDefault().newWatchService();
            } catch (IOException e) {
                throw new IllegalStateException("cannot watch directories: " + e.getMessage(), e);
            }
            try {
                for (Path dir : dirs) {
                    if (Files.isDirectory(dir)) {
                        register(dir);
                    } else {
                        LOG.log(
                                System.Logger.Level.WARNING,
                                "not watching missing directory {0}",
                                dir);
                    }
                }
                spawn();
                this.loop = Thread.ofPlatform().name("jolt-dev-watch").daemon().start(this::watch);
            } catch (IOException | RuntimeException e) {
                abort();
                if (e instanceof RuntimeException re) throw re;
                throw new IllegalStateException("cannot watch directories: " + e.getMessage(), e);
            }
        }

        /** Number of restarts since start. */
        public int restarts() {
            return restarts.get();
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            loop.interrupt();
            abort();
        }

        private void abort() {
            try {
                watcher.close();
            } catch (IOException ignored) {
                // watcher already gone
            }
            kill();
        }

        private void watch() {
            while (!closed) {
                try {
                    WatchKey key = watcher.take();
                    handle(key);
                    while ((key = watcher.poll(DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS)) != null) {
                        handle(key);
                    }
                    restart();
                } catch (InterruptedException | ClosedWatchServiceException e) {
                    return;
                } catch (RuntimeException e) {
                    LOG.log(
                            System.Logger.Level.ERROR,
                            "restart failed, waiting for next change",
                            e);
                }
            }
        }

        private void handle(WatchKey key) {
            Path dir = (Path) key.watchable();
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE
                        && event.context() instanceof Path name) {
                    Path created = dir.resolve(name);
                    if (Files.isDirectory(created)) {
                        try {
                            register(created);
                        } catch (IOException ignored) {
                            // directory vanished before it could be watched
                        }
                    }
                }
            }
            key.reset();
        }

        private void register(Path root) throws IOException {
            Files.walkFileTree(
                    root,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a)
                                throws IOException {
                            d.register(
                                    watcher,
                                    StandardWatchEventKinds.ENTRY_CREATE,
                                    StandardWatchEventKinds.ENTRY_MODIFY,
                                    StandardWatchEventKinds.ENTRY_DELETE);
                            return FileVisitResult.CONTINUE;
                        }
                    });
        }

        private synchronized void restart() {
            if (closed) return;
            System.out.println(PREFIX + "change detected, restarting " + mainClass);
            kill();
            spawn();
            restarts.incrementAndGet();
        }

        private void spawn() {
            String java =
                    Path.of(System.getProperty("java.home"), "bin", "java")
                            .toAbsolutePath()
                            .toString();
            List<String> cmd = new ArrayList<>();
            cmd.add(java);
            cmd.addAll(jvmArgs);
            cmd.add("-cp");
            cmd.add(
                    classpath(
                            System.getProperty("java.class.path"),
                            System.getProperty("jolt.dev.classpath")));
            cmd.add(mainClass);
            ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO();
            pb.environment().put("JOLT_DEV", "1");
            try {
                child = launcher.launch(pb);
            } catch (IOException e) {
                throw new IllegalStateException("cannot start " + mainClass, e);
            }
        }

        private void kill() {
            Process p = child;
            if (p == null) return;
            child = null;
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            try {
                p.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
