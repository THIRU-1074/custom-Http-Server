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
    public String capitalizeHeader(String header) {
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = true;
        for (char ch : header.toCharArray()) {
            if (ch == '-') {
                result.append(ch);
                capitalizeNext = true;
            } else {
                if (capitalizeNext) {
                    result.append(Character.toUpperCase(ch));
                    capitalizeNext = false;
                } else {
                    result.append(Character.toLowerCase(ch));
                }
            }
        }
        return result.toString();
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
            req.version = line.split(" ")[2];
            String fullUrl = line.split(" ")[1];  // "/hello/world?name=thiru&age=21"

            String[] parts = fullUrl.split("\\?", 2); // Limit to 2 parts: before and after '?'
            String path = parts[0];
            String queryString = parts.length > 1 ? parts[1] : "";  // Empty if no '?'
            req.url = path;

            // Split the query string by '&' to get individual key=value pairs
            String[] pairs = queryString.split("&");

            for (String pair : pairs) {
                String[] keyValue = pair.split("=", 2); // Limit to 2 to handle '=' in values
                if (keyValue.length == 2) {
                    req.urlQueryParams.put(keyValue[0], keyValue[1]);
                } else if (keyValue.length == 1) {
                    // Handle case like "flag" (key without value)
                    req.urlQueryParams.put(keyValue[0], "");
                }
            }

            // Read Header
            while ((line = reader.readLine()) != null && !(line.isEmpty())) {
                line=capitalizeHeader(line);
                System.out.println(line);
                req.headers.put(line.split(": ")[0], line.split(": ")[1]);
            }
            parseCookies();
            // Read body
            if (req.headers.get("Content-Type") != null ) {
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
