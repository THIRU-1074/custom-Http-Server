package in.jolt;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Opt-in long-running work: {@link #start} runs a job on a virtual thread and answers {@code 202
 * Accepted} with {@code Location: <path>/<id>}. Register it with {@code app.module(tasks)} to serve
 * {@code GET <path>/:id} (status and result, {@code ?wait=s} long-polls up to 30 s) and {@code
 * DELETE <path>/:id} (cancel). Tasks live in memory, in this process only.
 */
public final class Tasks implements RouteModule {

    private static final System.Logger LOG = System.getLogger(Tasks.class.getName());
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_WAIT_SECONDS = 30;

    private enum Status {
        RUNNING,
        DONE,
        FAILED,
        CANCELLED
    }

    private static final class Task {
        final String id;
        final String owner;
        final CountDownLatch finished = new CountDownLatch(1);
        Status status = Status.RUNNING;
        Object result;
        long finishedAt;
        Thread thread;

        Task(String id, String owner) {
            this.id = id;
            this.owner = owner;
        }
    }

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final Semaphore running;
    private final int maxStored;
    private final long ttlNanos;
    private final LongSupplier nanos;
    private String path = "/tasks";
    private Function<Request, String> owner;

    private Tasks(int maxRunning, int maxStored, Duration ttl, LongSupplier nanos) {
        if (maxRunning < 1 || maxStored < maxRunning) {
            throw new IllegalArgumentException("need 1 <= maxRunning <= maxStored");
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        this.running = new Semaphore(maxRunning);
        this.maxStored = maxStored;
        this.ttlNanos = ttl.toNanos();
        this.nanos = nanos;
    }

    /** 64 running, 1000 stored, finished tasks kept 15 minutes. */
    public static Tasks inMemory() {
        return inMemory(64, 1000, Duration.ofMinutes(15));
    }

    /** At most {@code maxRunning} jobs at once and {@code maxStored} tasks kept for {@code ttl}. */
    public static Tasks inMemory(int maxRunning, int maxStored, Duration ttl) {
        return new Tasks(maxRunning, maxStored, ttl, System::nanoTime);
    }

    static Tasks inMemory(int maxRunning, int maxStored, Duration ttl, LongSupplier nanos) {
        return new Tasks(maxRunning, maxStored, ttl, nanos);
    }

    /** Sets the path the task routes are served at and {@code Location} points to (/tasks). */
    public Tasks path(String path) {
        if (path == null || !path.startsWith("/") || path.length() < 2 || path.endsWith("/")) {
            throw new IllegalArgumentException("path must start with / and not end with /");
        }
        this.path = path;
        return this;
    }

    /**
     * Binds each task to {@code fn(req)} (for example the user id); only the same owner can read or
     * cancel it, others get 404. A {@code null} owner at start answers 401.
     */
    public Tasks owner(Function<Request, String> fn) {
        this.owner = Objects.requireNonNull(fn, "owner function is required");
        return this;
    }

    /**
     * Runs {@code work} on a virtual thread and answers 202 with {@code {id, status, href}}. Its
     * return value becomes the JSON {@code result}; a throw marks the task failed. Full: 503.
     */
    public void start(Request req, Response res, Callable<?> work) {
        Objects.requireNonNull(work, "work is required");
        String who = null;
        if (owner != null) {
            who = owner.apply(req);
            if (who == null) {
                throw new HttpException(HttpStatus.UNAUTHORIZED, "Task owner required");
            }
        }
        if (!makeRoom() || !running.tryAcquire()) {
            res.header("Retry-After", "1");
            throw new HttpException(HttpStatus.SERVICE_UNAVAILABLE, "Too many tasks; retry later");
        }
        Task task = new Task(newId(), who);
        tasks.put(task.id, task);
        synchronized (task) {
            task.thread =
                    Thread.ofVirtual()
                            .name("jolt-task-" + task.id)
                            .unstarted(() -> run(task, work));
            task.thread.start();
        }
        String href = path + "/" + task.id;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", task.id);
        body.put("status", "running");
        body.put("href", href);
        res.status(HttpStatus.ACCEPTED).header("Location", href).json(body);
    }

    @Override
    public void register(Router router) {
        router.delete(path + "/:id", this::cancel);
        router.get(path + "/:id", this::read);
    }

    private void run(Task task, Callable<?> work) {
        try {
            Object out = work.call();
            finish(task, Status.DONE, out);
        } catch (Exception e) {
            synchronized (task) {
                if (task.status == Status.RUNNING) {
                    LOG.log(System.Logger.Level.WARNING, "Task " + task.id + " failed", e);
                }
            }
            finish(task, Status.FAILED, null);
        } finally {
            running.release();
        }
    }

    private void finish(Task task, Status status, Object result) {
        synchronized (task) {
            if (task.status == Status.RUNNING) {
                task.status = status;
                task.result = result;
                task.finishedAt = nanos.getAsLong();
            }
        }
        task.finished.countDown();
    }

    private void read(Request req, Response res) throws InterruptedException {
        Task task = find(req);
        int wait = waitSeconds(req.query("wait"));
        if (wait > 0) {
            task.finished.await(wait, TimeUnit.SECONDS);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        synchronized (task) {
            body.put("id", task.id);
            body.put("status", task.status.name().toLowerCase(Locale.ROOT));
            if (task.status == Status.DONE) {
                body.put("result", task.result);
            } else if (task.status == Status.FAILED) {
                body.put("error", "Task failed");
            }
        }
        res.noStore().json(body);
    }

    private void cancel(Request req, Response res) {
        Task task = find(req);
        Thread thread = null;
        synchronized (task) {
            if (task.status == Status.RUNNING) {
                task.status = Status.CANCELLED;
                task.finishedAt = nanos.getAsLong();
                thread = task.thread;
            } else {
                tasks.remove(task.id);
            }
        }
        task.finished.countDown();
        if (thread != null) {
            thread.interrupt();
        }
        res.status(HttpStatus.NO_CONTENT);
    }

    private Task find(Request req) {
        evictExpired();
        Task task = tasks.get(req.param("id"));
        if (task != null && owner != null && !Objects.equals(task.owner, owner.apply(req))) {
            task = null;
        }
        if (task == null) {
            throw new HttpException(HttpStatus.NOT_FOUND, "No such task");
        }
        return task;
    }

    private static int waitSeconds(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        try {
            int s = Integer.parseInt(raw);
            if (s < 0) {
                throw new NumberFormatException();
            }
            return Math.min(s, MAX_WAIT_SECONDS);
        } catch (NumberFormatException e) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "wait must be whole seconds, 0-30");
        }
    }

    private boolean makeRoom() {
        evictExpired();
        while (tasks.size() >= maxStored) {
            Task oldest = null;
            for (Task t : tasks.values()) {
                synchronized (t) {
                    if (t.status != Status.RUNNING
                            && (oldest == null || t.finishedAt - oldest.finishedAt < 0)) {
                        oldest = t;
                    }
                }
            }
            if (oldest == null) {
                return false;
            }
            tasks.remove(oldest.id);
        }
        return true;
    }

    private void evictExpired() {
        long now = nanos.getAsLong();
        for (Iterator<Task> it = tasks.values().iterator(); it.hasNext(); ) {
            Task t = it.next();
            synchronized (t) {
                if (t.status != Status.RUNNING && now - t.finishedAt >= ttlNanos) {
                    it.remove();
                }
            }
        }
    }

    private static String newId() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
