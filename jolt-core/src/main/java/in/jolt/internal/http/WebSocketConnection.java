package in.jolt.internal.http;

import in.jolt.JoltConfig;
import in.jolt.Request;
import in.jolt.WebSocketHandler;
import in.jolt.WebSocketSession;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** One WebSocket connection (RFC 6455), served on the connection's thread. */
final class WebSocketConnection implements WebSocketSession {

    private static final System.Logger LOG = System.getLogger("in.jolt");
    private static final int TEXT = 0x1;
    private static final int BINARY = 0x2;
    private static final int CLOSE = 0x8;
    private static final int PING = 0x9;
    private static final int PONG = 0xA;

    private static final class Fail extends Exception {
        private final int code;

        Fail(int code, String reason) {
            super(reason);
            this.code = code;
        }
    }

    private record Frame(boolean fin, int op, byte[] payload) {}

    private final Request request;
    private final Socket socket;
    private final JoltConfig config;
    private final WebSocketHandler handler;
    private final InputStream in;
    private final OutputStream out;
    private final int maxFrame;
    private final int maxMessage;
    private final Object lock = new Object();
    private volatile boolean closeSent;
    private StreamGuard guard;
    private int closeCode = 1006;
    private String closeReason = "";

    WebSocketConnection(
            Request request,
            Socket socket,
            InputStream in,
            OutputStream out,
            JoltConfig config,
            WebSocketHandler handler) {
        this.request = request;
        this.socket = socket;
        this.config = config;
        this.handler = handler;
        this.in = in;
        this.out = out;
        this.maxFrame = config.wsMaxFrameBytes();
        this.maxMessage = config.wsMaxMessageBytes();
    }

    @Override
    public Request request() {
        return request;
    }

    @Override
    public boolean isOpen() {
        return !closeSent;
    }

    @Override
    public void send(String text) throws IOException {
        frame(TEXT, text.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void send(byte[] data) throws IOException {
        frame(BINARY, data);
    }

    @Override
    public void ping() throws IOException {
        frame(PING, new byte[0]);
    }

    @Override
    public void close() throws IOException {
        close(1000, "");
    }

    @Override
    public void close(int code, String reason) throws IOException {
        byte[] text = reason.getBytes(StandardCharsets.UTF_8);
        if (!validCode(code) || text.length > 123) {
            throw new IllegalArgumentException("Invalid close code or reason");
        }
        sendClose(code, text);
    }

    void run() {
        Duration idle = config.streamIdleTimeout();
        guard = new StreamGuard(socket, idle.multipliedBy(2), config.streamMaxDuration());
        try {
            socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, idle.toMillis()));
            handler.onOpen(this);
            loop();
        } catch (Fail f) {
            closeCode = f.code;
            closeReason = f.getMessage();
            quietClose(f.code, f.getMessage());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "WebSocket ended", e);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "WebSocket handler failed", e);
            closeCode = 1011;
            closeReason = "Internal error";
            quietClose(1011, "Internal error");
        } finally {
            guard.close();
            try {
                handler.onClose(this, closeCode, closeReason);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "WebSocket onClose failed", e);
            }
        }
    }

    private void loop() throws Exception {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        int messageOp = -1;
        boolean closing = false;
        while (true) {
            if (closeSent && !closing) {
                closing = true;
                socket.setSoTimeout((int) config.readTimeout().toMillis());
            }
            Frame f;
            try {
                f = readFrame();
            } catch (SocketTimeoutException e) {
                if (closeSent) {
                    return;
                }
                ping();
                continue;
            } catch (EOFException e) {
                return;
            }
            guard.touch();
            switch (f.op) {
                case PING -> frame(PONG, f.payload);
                case PONG -> {}
                case CLOSE -> {
                    receivedClose(f.payload);
                    return;
                }
                default -> {
                    if (f.op == 0 ? messageOp < 0 : messageOp >= 0) {
                        throw new Fail(1002, "Unexpected frame sequence");
                    }
                    if (f.op != 0) {
                        messageOp = f.op;
                    }
                    if (message.size() + (long) f.payload.length > maxMessage) {
                        throw new Fail(1009, "Message too big");
                    }
                    message.writeBytes(f.payload);
                    if (f.fin) {
                        byte[] data = message.toByteArray();
                        int op = messageOp;
                        message.reset();
                        messageOp = -1;
                        if (op == TEXT) {
                            handler.onText(this, utf8(data));
                        } else {
                            handler.onBinary(this, data);
                        }
                    }
                }
            }
        }
    }

    private Frame readFrame() throws IOException, Fail {
        int b0 = in.read();
        if (b0 < 0) {
            throw new EOFException();
        }
        try {
            return frameAfter(b0);
        } catch (SocketTimeoutException e) {
            throw new Fail(1001, "Frame timed out");
        }
    }

    private Frame frameAfter(int b0) throws IOException, Fail {
        int b1 = in.read();
        if (b1 < 0) {
            throw new EOFException();
        }
        if ((b0 & 0x70) != 0) {
            throw new Fail(1002, "Reserved bits set");
        }
        boolean fin = (b0 & 0x80) != 0;
        int op = b0 & 0x0F;
        long len = b1 & 0x7F;
        if (len == 126 || len == 127) {
            byte[] ext = in.readNBytes(len == 126 ? 2 : 8);
            if (ext.length < (len == 126 ? 2 : 8)) {
                throw new EOFException();
            }
            len = 0;
            for (byte b : ext) {
                len = (len << 8) | (b & 0xFF);
            }
            if (len < 0) {
                throw new Fail(1002, "Bad frame length");
            }
        }
        if ((b1 & 0x80) == 0) {
            throw new Fail(1002, "Client frames must be masked");
        }
        boolean control = op >= 8;
        if (control ? !fin || len > 125 || op > PONG : op > BINARY) {
            throw new Fail(1002, "Invalid frame");
        }
        if (len > maxFrame) {
            throw new Fail(1009, "Frame too big");
        }
        byte[] mask = in.readNBytes(4);
        byte[] payload = in.readNBytes((int) len);
        if (mask.length < 4 || payload.length < len) {
            throw new EOFException();
        }
        for (int i = 0; i < payload.length; i++) {
            payload[i] ^= mask[i & 3];
        }
        return new Frame(fin, op, payload);
    }

    private void receivedClose(byte[] payload) throws Fail, IOException {
        int code = 1005;
        String reason = "";
        if (payload.length == 1) {
            throw new Fail(1002, "Bad close frame");
        }
        if (payload.length >= 2) {
            code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
            if (!validCode(code)) {
                throw new Fail(1002, "Bad close code");
            }
            reason = utf8(java.util.Arrays.copyOfRange(payload, 2, payload.length));
        }
        if (!closeSent) {
            closeCode = code;
            closeReason = reason;
            sendClose(
                    code == 1005 ? 1000 : code,
                    java.util.Arrays.copyOfRange(
                            payload, Math.min(2, payload.length), payload.length));
        }
    }

    private void sendClose(int code, byte[] reason) throws IOException {
        byte[] payload = new byte[2 + reason.length];
        payload[0] = (byte) (code >> 8);
        payload[1] = (byte) code;
        System.arraycopy(reason, 0, payload, 2, reason.length);
        synchronized (lock) {
            if (closeSent) {
                return;
            }
            if (closeCode == 1006) {
                closeCode = code;
            }
            closeSent = true;
            write(CLOSE, payload);
        }
    }

    private void quietClose(int code, String reason) {
        try {
            sendClose(code, reason.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "Close frame not sent", e);
        }
    }

    private void frame(int op, byte[] payload) throws IOException {
        synchronized (lock) {
            if (closeSent) {
                throw new IOException("WebSocket closed");
            }
            write(op, payload);
        }
    }

    private void write(int op, byte[] payload) throws IOException {
        int n = payload.length;
        int header = n < 126 ? 2 : n < 65536 ? 4 : 10;
        byte[] buf = new byte[header + n];
        buf[0] = (byte) (0x80 | op);
        if (n < 126) {
            buf[1] = (byte) n;
        } else if (n < 65536) {
            buf[1] = 126;
            buf[2] = (byte) (n >> 8);
            buf[3] = (byte) n;
        } else {
            buf[1] = 127;
            ByteBuffer.wrap(buf, 2, 8).putLong(n);
        }
        System.arraycopy(payload, 0, buf, header, n);
        if (op != PING) {
            guard.touch();
        }
        out.write(buf);
        out.flush();
        if (op != PING) {
            guard.touch();
        }
    }

    private static boolean validCode(int code) {
        return code >= 1000 && code <= 1003
                || code >= 1007 && code <= 1014
                || code >= 3000 && code <= 4999;
    }

    private static String utf8(byte[] bytes) throws Fail {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new Fail(1007, "Invalid UTF-8");
        }
    }
}
