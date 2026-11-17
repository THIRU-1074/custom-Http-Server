package in.jolt;

/**
 * Callbacks for one WebSocket connection, run on the connection's thread one at a time. Override
 * what you need; an exception from a callback closes the connection with code 1011.
 */
public interface WebSocketHandler {

    default void onOpen(WebSocketSession session) throws Exception {}

    default void onText(WebSocketSession session, String text) throws Exception {}

    default void onBinary(WebSocketSession session, byte[] data) throws Exception {}

    /** Called once when the connection ends; {@code code} is 1006 if it ended abnormally. */
    default void onClose(WebSocketSession session, int code, String reason) {}

    @FunctionalInterface
    interface Text {
        void on(WebSocketSession session, String text) throws Exception;
    }

    /** A handler that only reacts to text messages. */
    static WebSocketHandler text(Text onText) {
        return new WebSocketHandler() {
            @Override
            public void onText(WebSocketSession session, String text) throws Exception {
                onText.on(session, text);
            }
        };
    }
}
