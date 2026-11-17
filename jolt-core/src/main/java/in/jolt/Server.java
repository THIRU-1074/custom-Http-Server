package in.jolt;

import in.jolt.internal.http.ConnectionHandler;
import in.jolt.internal.http.Metrics;
import in.jolt.internal.http.Tls;
import in.jolt.internal.http.TrustedProxies;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLServerSocket;

/**
 * A running server. One virtual thread serves each connection, bounded by {@code maxConnections}.
 */
public final class Server implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger("in.jolt");
    private static final long GRACE_SECONDS = 5;
    private static final long ACCEPT_BACKOFF_MS = 50;

    private final JoltConfig config;
    private final ConnectionHandler.Dispatcher dispatcher;
    private final TrustedProxies trusted;
    private final Metrics metrics;
    private final ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits;
    private final Set<ConnectionHandler> live = ConcurrentHashMap.newKeySet();
    private final Thread acceptor;
    private final Object closeLock = new Object();
    private volatile boolean closing;
    private boolean closed;
    private Thread shutdownHook;

    Server(JoltConfig config, int port, ConnectionHandler.Dispatcher dispatcher)
            throws IOException {
        this(config, bind(config, port), dispatcher);
    }

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

    Server(JoltConfig config, ServerSocket serverSocket, ConnectionHandler.Dispatcher dispatcher) {
        this.config = config;
        this.dispatcher = dispatcher;
        this.trusted = TrustedProxies.from(config);
        this.metrics = dispatcher.metrics();
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
            shutdownHook = new Thread(this::close, "jolt-shutdown");
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
            if (!permits.tryAcquire()) {
                ConnectionHandler.reject(socket, config);
                continue;
            }
            var handler = new ConnectionHandler(socket, config, dispatcher, () -> closing, trusted);
            live.add(handler);
            if (metrics != null) {
                metrics.connectionOpened();
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

    private void release(ConnectionHandler handler) {
        live.remove(handler);
        permits.release();
        if (metrics != null) {
            metrics.connectionClosed();
        }
    }

    /**
     * Stops accepting, lets in-flight requests finish (up to 5 s), then drops what is left. Safe to
     * call more than once and from several threads: the first caller drains, the others wait for it
     * to finish.
     */
    @Override
    public void close() {
        synchronized (closeLock) {
            if (closed) {
                return;
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
        }
    }
}
