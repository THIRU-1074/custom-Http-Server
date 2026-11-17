package in.jolt.internal.http;

import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Closes a long-lived connection and interrupts its thread after too long without {@link #touch} or
 * past a total limit, so a stalled client cannot hold a thread forever.
 */
public final class StreamGuard {

    private static final System.Logger LOG = System.getLogger("in.jolt");

    private static final class Timer {
        static final ScheduledThreadPoolExecutor INSTANCE = create();

        private static ScheduledThreadPoolExecutor create() {
            var t =
                    new ScheduledThreadPoolExecutor(
                            1,
                            r -> {
                                Thread th = new Thread(r, "jolt-stream-timer");
                                th.setDaemon(true);
                                return th;
                            });
            t.setKeepAliveTime(2, TimeUnit.SECONDS);
            t.allowCoreThreadTimeOut(true);
            t.setRemoveOnCancelPolicy(true);
            return t;
        }
    }

    private final Socket socket;
    private final Thread owner = Thread.currentThread();
    private final long idleNanos;
    private final long maxNanos;
    private final long start = System.nanoTime();
    private volatile long last = start;
    private volatile boolean done;
    private volatile ScheduledFuture<?> task;

    /** Starts watching {@code socket} on behalf of the calling thread. */
    public StreamGuard(Socket socket, Duration idle, Duration max) {
        this.socket = socket;
        this.idleNanos = idle.toNanos();
        this.maxNanos = max.isZero() || max.isNegative() ? 0 : max.toNanos();
        schedule(maxNanos > 0 ? Math.min(idleNanos, maxNanos) : idleNanos);
    }

    /** Records activity. */
    public void touch() {
        last = System.nanoTime();
    }

    /** Stops watching. */
    public void close() {
        done = true;
        ScheduledFuture<?> t = task;
        if (t != null) {
            t.cancel(false);
        }
    }

    private void schedule(long nanos) {
        task = Timer.INSTANCE.schedule(this::check, Math.max(nanos, 1), TimeUnit.NANOSECONDS);
    }

    private void check() {
        if (done) {
            return;
        }
        long now = System.nanoTime();
        long idleLeft = idleNanos - (now - last);
        long maxLeft = maxNanos > 0 ? maxNanos - (now - start) : Long.MAX_VALUE;
        if (idleLeft > 0 && maxLeft > 0) {
            schedule(Math.min(idleLeft, maxLeft));
            return;
        }
        LOG.log(System.Logger.Level.DEBUG, "Closing stalled or expired stream");
        try {
            socket.close();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "Close failed", e);
        }
        owner.interrupt();
    }
}
