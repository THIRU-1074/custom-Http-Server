package in.jolt;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.LinkedList;
import java.util.Queue;

public class RequestHandler extends Thread {
    Request req;
    Response res;

    Socket socket;

    RequestHandler(Socket socket, Request req, Response res) {
        this.socket = socket;
        this.req = req;
        this.res = res;
    }

    void parseCookies() {
        String keyValCookies = req.headers.get("Cookies");
        if (keyValCookies == null || keyValCookies.isEmpty())
            return;

        String[] pairs = keyValCookies.split(";");
        for (String pair : pairs) {
            String[] keyValue = pair.trim().split("=", 2);
            if (keyValue.length == 2) {
                String key = keyValue[0].trim();
                String value = keyValue[1].trim();
                req.cookieJson.put(key, value);
            }
        }
    }

    void parseRequest() {

        String line;
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            line = reader.readLine();
            if (line == null || line.length() == 0) {
                socket.close();
                return;
            }
            // Start Line
            System.out.println(line);
            req.method = line.split(" ")[0];
            req.url = line.split(" ")[1];
            req.version = line.split(" ")[2];

            // Read Header
            while ((line = reader.readLine()) != null && !(line.isEmpty())) {
                System.out.println(line);
                req.headers.put(line.split(": ")[0], line.split(": ")[1]);
            }
            parseCookies();
            // Read body
            if (req.headers.get("Content-Type") != null) {
                switch (req.headers.get("Content-Type")) {
                    case ("application/json") -> {
                        req.body = new JSON();
                    }
                    default -> {
                        req.body = new text();
                    }
                }
                req.body.contentLength = Integer.parseInt(req.headers.get("Content-Length"));
                req.body.contentType = req.headers.get("Content-Type");
                char[] body = new char[req.body.contentLength];
                reader.read(body, 0, req.body.contentLength);
                String str = new String(body);
                req.body.serialized = str.getBytes();
                req.body.deserialize();
                System.out.println("Received: " + new String(body));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        parseRequest();
        req.isReady = true;
        Jolt.runJoltThread(req, res);
    }
}
