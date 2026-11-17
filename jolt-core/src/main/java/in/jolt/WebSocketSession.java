package in.jolt;

import java.io.IOException;

/**
 * One WebSocket connection (RFC 6455), served on the connection's thread. Sending is thread-safe; a
 * failed send means the client has gone and throws {@link IOException}.
 */
public interface WebSocketSession {

    /** The request that opened this connection, for its path, params, headers and attributes. */
    Request request();

    /** False once a close frame has been sent. */
    boolean isOpen();

    /** Sends a text message. */
    void send(String text) throws IOException;

    /** Sends a binary message. */
    void send(byte[] data) throws IOException;

    /** Sends a ping; the client's pong is handled by the connection. */
    void ping() throws IOException;

    /** Closes with code 1000. */
    void close() throws IOException;

    /**
     * Starts the close handshake; the connection ends when the client answers or times out.
     *
     * @throws IllegalArgumentException for a code that may not be sent or a reason over 123 bytes
     */
    void close(int code, String reason) throws IOException;
}
