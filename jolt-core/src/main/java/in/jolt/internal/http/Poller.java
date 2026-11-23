package in.jolt.internal.http;

import java.io.IOException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

/**
 * Waits for the next request on idle keep-alive connections with one selector, so an idle
 * connection holds a registration instead of a thread. A connection that becomes readable goes back
 * to the executor; one idle for {@code keepAliveTimeout} is closed.
 */
public final class Poller implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger("in.jolt");
    private static final long MIN_TICK_MS = 10;
    private static final long MAX_TICK_MS = 1000;

    private record Parked(ConnectionHandler handler, long since) {}

    private final Selector selector;
    private final Executor executor;
    private final long keepAliveNanos;
    private final long tickMillis;
    private final Queue<ConnectionHandler> arrivals = new ConcurrentLinkedQueue<>();
    private final Thread thread;
    private volatile boolean closed;

    public Poller(Executor executor, Duration keepAlive) throws IOException {
        this.selector = Selector.open();
        this.executor = executor;
        this.keepAliveNanos = keepAlive.toNanos();
        this.tickMillis = Math.max(MIN_TICK_MS, Math.min(MAX_TICK_MS, keepAlive.toMillis() / 2));
        this.thread = new Thread(this::run, "jolt-poller");
        thread.start();
    }

    /** Takes over an idle connection. The caller must not touch it afterwards. */
    void park(ConnectionHandler handler) {
        arrivals.add(handler);
        if (closed) {
            closeArrivals();
            return;
        }
        selector.wakeup();
    }

    /** Stops the poller and closes every connection it holds. */
    @Override
    public void close() {
        closed = true;
        selector.wakeup();
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        List<ConnectionHandler> woken = new ArrayList<>();
        try {
            while (!closed) {
                register();
                selector.select(tickMillis);
                collectReadable(woken);
                expire();
                if (!woken.isEmpty()) {
                    selector.selectNow();
                    resume(woken);
                    woken.clear();
                }
            }
        } catch (IOException | ClosedSelectorException e) {
            if (!closed) {
                LOG.log(System.Logger.Level.ERROR, "Keep-alive poller stopped", e);
            }
        } finally {
            closed = true;
            closeAll();
        }
    }

    private void register() {
        ConnectionHandler h;
        while ((h = arrivals.poll()) != null) {
            try {
                var channel = h.channel();
                channel.configureBlocking(false);
                channel.register(selector, SelectionKey.OP_READ, new Parked(h, System.nanoTime()));
            } catch (IOException | RuntimeException e) {
                h.closeIfIdle();
            }
        }
    }

    private void collectReadable(List<ConnectionHandler> woken) {
        Iterator<SelectionKey> it = selector.selectedKeys().iterator();
        while (it.hasNext()) {
            SelectionKey key = it.next();
            it.remove();
            key.cancel();
            woken.add(((Parked) key.attachment()).handler());
        }
    }

    private void expire() {
        long now = System.nanoTime();
        for (SelectionKey key : selector.keys()) {
            if (key.isValid() && now - ((Parked) key.attachment()).since() >= keepAliveNanos) {
                key.cancel();
                ((Parked) key.attachment()).handler().closeIfIdle();
            }
        }
    }

    private void resume(List<ConnectionHandler> woken) {
        for (ConnectionHandler h : woken) {
            try {
                h.channel().configureBlocking(true);
                executor.execute(h::resume);
            } catch (IOException | RuntimeException e) {
                h.closeIfIdle();
            }
        }
    }

    private void closeArrivals() {
        ConnectionHandler h;
        while ((h = arrivals.poll()) != null) {
            h.closeIfIdle();
        }
    }

    private void closeAll() {
        closeArrivals();
        try {
            for (SelectionKey key : selector.keys()) {
                ((Parked) key.attachment()).handler().closeIfIdle();
            }
            selector.close();
        } catch (IOException | ClosedSelectorException e) {
            LOG.log(System.Logger.Level.DEBUG, "Closing poller failed", e);
        }
    }
}
