package in.jolt;

import in.jolt.internal.http.ConnectionHandler;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * A running server. One virtual thread serves each connection, bounded by {@code maxConnections}.
 */
public final class Server implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger("in.jolt");
    private static final long GRACE_SECONDS = 5;
    private static final long ACCEPT_BACKOFF_MS = 50;

    private final JoltConfig config;
    private final ConnectionHandler.Dispatcher dispatcher;
    private final ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits;
    private final Set<ConnectionHandler> live = ConcurrentHashMap.newKeySet();
    private final Thread acceptor;
    private volatile boolean closing;

    Server(JoltConfig config, int port, ConnectionHandler.Dispatcher dispatcher)
            throws IOException {
        this(config, new ServerSocket(port, 1024), dispatcher);
    }

    Server(JoltConfig config, ServerSocket serverSocket, ConnectionHandler.Dispatcher dispatcher) {
        this.config = config;
        this.dispatcher = dispatcher;
        this.permits = new Semaphore(config.maxConnections());
        this.serverSocket = serverSocket;
        this.acceptor = new Thread(this::acceptLoop, "jolt-acceptor");
        acceptor.start();
    }

    /** The bound port, useful after {@code listen(0)}. */
    public int port() {
        return serverSocket.getLocalPort();
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
                ConnectionHandler.reject(socket);
                continue;
            }
            var handler = new ConnectionHandler(socket, config, dispatcher, () -> closing);
            live.add(handler);
            try {
                executor.execute(
                        () -> {
                            try {
                                handler.run();
                            } finally {
                                live.remove(handler);
                                permits.release();
                            }
                        });
            } catch (RuntimeException e) {
                live.remove(handler);
                permits.release();
                handler.closeIfIdle();
            }
        }
    }

    /** Stops accepting, lets in-flight requests finish (up to 5 s), then drops what is left. */
    @Override
    public void close() {
        closing = true;
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
        }
    }
}
