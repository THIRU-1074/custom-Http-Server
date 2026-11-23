package in.jolt;

import in.jolt.internal.http.ConnectionHandler;
import in.jolt.internal.http.Metrics;
import in.jolt.internal.http.Tls;
import in.jolt.internal.http.TrustedProxies;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLServerSocket;

/**
 * A running server. One virtual thread serves each connection, bounded by {@code maxConnections}.
 * At the limit, the longest-idle keep-alive connection is closed to admit a new one; a new
 * connection is refused with 503 only when every connection has a request in flight.
 */
public final class Server implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger("in.jolt");
    private static final long GRACE_SECONDS = 5;
    private static final long ACCEPT_BACKOFF_MS = 50;
    private static final long RECLAIM_WAIT_MS = 100;

    private final JoltConfig config;
    private final ConnectionHandler.Dispatcher dispatcher;
    private final TrustedProxies trusted;
    private final ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits;
    private final Set<ConnectionHandler> live = ConcurrentHashMap.newKeySet();
    private final Thread acceptor;
    private final Object closeLock = new Object();
    private volatile boolean closing;
    private boolean closed;
    private final List<Hook> hooks;
    private Thread shutdownHook;

    private static ServerSocket bind(JoltConfig config, int port) throws IOException {
        InetAddress address = InetAddress.getByName(config.bindAddress());
        if (config.tls() == null) {
            return new ServerSocket(port, 1024, address);
        }
        var socket =
                (SSLServerSocket)
                        config.tls()
                                .getServerSocketFactory()
                                .createServerSocket(port, 1024, address);
        Tls.harden(socket);
        return socket;
    }

    /** A lifecycle entry; either side may be {@code null}. */
    record Hook(Runnable start, Runnable stop) {}

    static Server start(
            JoltConfig config, int port, ConnectionHandler.Dispatcher dispatcher, List<Hook> hooks)
            throws IOException {
        int done = 0;
        try {
            for (; done < hooks.size(); done++) {
                Runnable start = hooks.get(done).start();
                if (start != null) {
                    start.run();
                }
            }
        } catch (Throwable t) {
            abort(null, hooks, done);
            if (t instanceof Error e) {
                throw e;
            }
            throw new IllegalStateException("onStart hook failed, listen aborted", t);
        }
        ServerSocket socket = null;
        try {
            socket = bind(config, port);
            return new Server(config, socket, dispatcher, hooks);
        } catch (Throwable t) {
            abort(socket, hooks, done);
            throw t;
        }
    }

    private static void abort(ServerSocket socket, List<Hook> hooks, int started) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing after a failed start; the original failure is the one to report
            }
        }
        List<Runnable> stops = new ArrayList<>();
        for (int i = 0; i < hooks.size(); i++) {
            Hook h = hooks.get(i);
            if (h.stop() != null && (h.start() == null || i < started)) {
                stops.add(h.stop());
            }
        }
        runStopHooks(stops);
    }

    Server(JoltConfig config, ServerSocket serverSocket, ConnectionHandler.Dispatcher dispatcher) {
        this(config, serverSocket, dispatcher, List.<Hook>of());
    }

    private Server(
            JoltConfig config,
            ServerSocket serverSocket,
            ConnectionHandler.Dispatcher dispatcher,
            List<Hook> hooks) {
        this.hooks = hooks;
        this.config = config;
        this.dispatcher = dispatcher;
        this.trusted = TrustedProxies.from(config);
        this.permits = new Semaphore(config.maxConnections());
        this.serverSocket = serverSocket;
        this.acceptor = new Thread(this::acceptLoop, "jolt-acceptor");
        acceptor.start();
    }

    /** The bound port, useful after {@code listen(0)}. */
    public int port() {
        return serverSocket.getLocalPort();
    }

    /** Closes this server from a JVM shutdown hook. Calling it again has no effect. */
    public synchronized Server closeOnShutdown() {
        if (shutdownHook == null && !closing) {
            shutdownHook = new Thread(this::shutdown, "jolt-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }
        return this;
    }

    synchronized Thread shutdownHook() {
        return shutdownHook;
    }

    private synchronized void unregisterHook() {
        if (shutdownHook != null && Thread.currentThread() != shutdownHook) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM is already shutting down
            }
            shutdownHook = null;
        }
    }

    private void acceptLoop() {
        while (!closing) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (closing || serverSocket.isClosed()) {
                    return;
                }
                LOG.log(System.Logger.Level.WARNING, "Accept failed, retrying", e);
                try {
                    Thread.sleep(ACCEPT_BACKOFF_MS);
                } catch (InterruptedException ie) {
                    return;
                }
                continue;
            }
            if (!permits.tryAcquire() && !reclaimIdle()) {
                ConnectionHandler.reject(socket, config);
                continue;
            }
            var handler = new ConnectionHandler(socket, config, dispatcher, () -> closing, trusted);
            live.add(handler);
            Metrics opened = dispatcher.metrics();
            if (opened != null) {
                opened.connectionOpened();
            }
            try {
                executor.execute(
                        () -> {
                            try {
                                handler.run();
                            } finally {
                                release(handler);
                            }
                        });
            } catch (RuntimeException e) {
                release(handler);
                handler.closeIfIdle();
            }
        }
    }

    /**
     * At the connection limit, closes the connection that has waited longest for its next request
     * and takes its permit. False when every connection has a request in flight.
     */
    private boolean reclaimIdle() {
        ConnectionHandler oldest = null;
        long since = 0;
        for (ConnectionHandler h : live) {
            OptionalLong s = h.idleSince();
            if (s.isPresent() && (oldest == null || s.getAsLong() - since < 0)) {
                oldest = h;
                since = s.getAsLong();
            }
        }
        if (oldest == null) {
            return false;
        }
        oldest.closeIfIdle();
        try {
            return permits.tryAcquire(RECLAIM_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void release(ConnectionHandler handler) {
        live.remove(handler);
        permits.release();
        Metrics m = dispatcher.metrics();
        if (m != null) {
            m.connectionClosed();
        }
    }

    /**
     * Stops accepting, lets in-flight requests finish (up to 5 s), then drops what is left. Safe to
     * call more than once and from several threads: the first caller drains, the others wait for it
     * to finish.
     */
    @Override
    public void close() {
        if (shutdown() instanceof Error e) {
            throw e;
        }
    }

    private Error shutdown() {
        synchronized (closeLock) {
            if (closed) {
                return null;
            }
            closing = true;
            unregisterHook();
            try {
                serverSocket.close();
                acceptor.join();
                live.forEach(ConnectionHandler::closeIfIdle);
                executor.shutdown();
                if (!executor.awaitTermination(GRACE_SECONDS, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Closing server socket failed", e);
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            } finally {
                closed = true;
            }
            List<Runnable> stops = new ArrayList<>();
            for (Hook h : hooks) {
                if (h.stop() != null) {
                    stops.add(h.stop());
                }
            }
            return runStopHooks(stops);
        }
    }

    private static Error runStopHooks(List<Runnable> stops) {
        Error first = null;
        for (int i = stops.size() - 1; i >= 0; i--) {
            try {
                stops.get(i).run();
            } catch (Throwable t) {
                LOG.log(System.Logger.Level.WARNING, "onStop hook failed", t);
                if (first == null && t instanceof Error e) {
                    first = e;
                }
            }
        }
        return first;
    }
}
