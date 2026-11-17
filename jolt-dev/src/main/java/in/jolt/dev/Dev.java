package in.jolt.dev;

import java.io.IOException;
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

    private Dev() {}

    /**
     * Runs {@code mainClass} in a child JVM and restarts it on changes under {@code watchDirs}
     * (recursively). Blocks until the calling thread is interrupted. The child sees {@code
     * JOLT_DEV=1}.
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
        return new Session(mainClass, jvmArgs, watchDirs);
    }

    /** A running dev loop. */
    public static final class Session implements AutoCloseable {

        private final String mainClass;
        private final List<String> jvmArgs;
        private final WatchService watcher;
        private final Thread loop;
        private final AtomicInteger restarts = new AtomicInteger();
        private volatile Process child;
        private volatile boolean closed;

        private Session(String mainClass, List<String> jvmArgs, Path[] dirs) {
            this.mainClass = mainClass;
            this.jvmArgs = List.copyOf(jvmArgs);
            try {
                this.watcher = java.nio.file.FileSystems.getDefault().newWatchService();
                for (Path dir : dirs) register(dir);
            } catch (IOException e) {
                throw new IllegalStateException("cannot watch directories: " + e.getMessage(), e);
            }
            spawn();
            this.loop = Thread.ofPlatform().name("jolt-dev-watch").daemon().start(this::watch);
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
            try {
                watcher.close();
            } catch (IOException ignored) {
                // watcher already gone
            }
            kill();
        }

        private void watch() {
            try {
                while (!closed) {
                    WatchKey key = watcher.take();
                    handle(key);
                    while ((key = watcher.poll(DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS)) != null) {
                        handle(key);
                    }
                    restart();
                }
            } catch (InterruptedException | java.nio.file.ClosedWatchServiceException e) {
                Thread.currentThread().interrupt();
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
            cmd.add(System.getProperty("java.class.path"));
            cmd.add(mainClass);
            ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO();
            pb.environment().put("JOLT_DEV", "1");
            try {
                child = pb.start();
            } catch (IOException e) {
                throw new IllegalStateException("cannot start " + mainClass, e);
            }
        }

        private void kill() {
            Process p = child;
            if (p == null) return;
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
