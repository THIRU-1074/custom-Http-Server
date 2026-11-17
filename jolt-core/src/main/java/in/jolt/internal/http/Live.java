package in.jolt.internal.http;

import in.jolt.Request;
import in.jolt.Response;
import in.jolt.WebSocketHandler;
import java.io.IOException;

/** What a response may ask of its connection: stream a body or switch to WebSocket. Not API. */
public interface Live {

    /** Commits {@code res}'s status and headers and takes the connection over. */
    StreamSink open(Response res) throws IOException;

    /** Answers the handshake with {@code accept} and runs the session until it ends. */
    void upgrade(Request req, String accept, WebSocketHandler handler) throws IOException;
}
