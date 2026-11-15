package in.jolt;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Minimal HTTP/1.1 client over a raw socket, so tests control framing byte by byte. */
final class RawClient implements Closeable {

    record Reply(int status, Map<String, List<String>> headers, byte[] body) {
        String header(String name) {
            List<String> v = headers.get(name);
            return v == null ? null : v.get(0);
        }

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    RawClient(int port) throws IOException {
        socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(5000);
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    RawClient send(String s) throws IOException {
        return send(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    RawClient send(byte[] b) throws IOException {
        out.write(b);
        out.flush();
        return this;
    }

    Reply read() throws IOException {
        String statusLine = line();
        if (statusLine == null) {
            throw new IOException("connection closed");
        }
        int status = Integer.parseInt(statusLine.split(" ")[1]);
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        String l;
        while (!(l = line()).isEmpty()) {
            int c = l.indexOf(':');
            headers.computeIfAbsent(l.substring(0, c), k -> new ArrayList<>())
                    .add(l.substring(c + 1).trim());
        }
        String len =
                headers.containsKey("Content-Length") ? headers.get("Content-Length").get(0) : "0";
        byte[] body = in.readNBytes(Integer.parseInt(len));
        return new Reply(status, headers, body);
    }

    /** Reads a reply whose body is not announced (HEAD): headers only. */
    Reply readHeadOnly() throws IOException {
        String statusLine = line();
        int status = Integer.parseInt(statusLine.split(" ")[1]);
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        String l;
        while (!(l = line()).isEmpty()) {
            int c = l.indexOf(':');
            headers.computeIfAbsent(l.substring(0, c), k -> new ArrayList<>())
                    .add(l.substring(c + 1).trim());
        }
        return new Reply(status, headers, new byte[0]);
    }

    Reply get(String path) throws IOException {
        return send("GET " + path + " HTTP/1.1\r\nHost: t\r\n\r\n").read();
    }

    Reply request(String method, String path, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        return send(method
                        + " "
                        + path
                        + " HTTP/1.1\r\nHost: t\r\nContent-Type: application/json\r\n"
                        + "Content-Length: "
                        + b.length
                        + "\r\n\r\n")
                .send(b)
                .read();
    }

    boolean isClosedByPeer() throws IOException {
        try {
            return in.read() < 0;
        } catch (java.net.SocketException e) {
            return true;
        }
    }

    private String line() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                String s = b.toString(StandardCharsets.ISO_8859_1);
                return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
            }
            b.write(c);
        }
        return b.size() == 0 ? null : b.toString(StandardCharsets.ISO_8859_1);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
