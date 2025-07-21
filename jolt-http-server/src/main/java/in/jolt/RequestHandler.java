package in.jolt;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Arrays;
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
        try {
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream requestBuf = new ByteArrayOutputStream();

            byte[] temp = new byte[8192];
            int bytesRead;

            // Read all available bytes — you may optionally use Content-Length limit if needed
            while ((bytesRead = in.read(temp)) != -1) {
                requestBuf.write(temp, 0, bytesRead);

                // Optional: break early if header and full body (Content-Length) are read
                // We'll handle it by splitting instead
                if (in.available() == 0) break;
            }

            byte[] fullRequest = requestBuf.toByteArray();

            // === Step 1: Find position of \r\n\r\n (end of headers) ===
            int headerEndIndex = -1;
            for (int i = 0; i < fullRequest.length - 3; i++) {
                if (fullRequest[i] == '\r' && fullRequest[i + 1] == '\n' &&
                    fullRequest[i + 2] == '\r' && fullRequest[i + 3] == '\n') {
                    headerEndIndex = i + 4;
                    break;
                }
            }

            if (headerEndIndex == -1) {
                socket.close();
                return;
            }
            
            // === Step 2: Split into headers and body ===
            byte[] headerBytes = Arrays.copyOfRange(fullRequest, 0, headerEndIndex);
            byte[] bodyBytes = Arrays.copyOfRange(fullRequest, headerEndIndex, fullRequest.length);

            String headerText = new String(headerBytes, "ISO-8859-1"); // raw-safe string
            String[] lines = headerText.split("\r\n");

            // === Step 3: Parse Request Line ===
            String requestLine = lines[0];
            String[] requestParts = requestLine.split(" ");
            req.method = requestParts[0];
            req.version = requestParts[2];

            String fullUrl = requestParts[1];
            String[] urlParts = fullUrl.split("\\?", 2);
            req.url = urlParts[0];

            if (urlParts.length > 1) {
                for (String pair : urlParts[1].split("&")) {
                    String[] kv = pair.split("=", 2);
                    req.urlQueryParams.put(kv[0], kv.length > 1 ? kv[1] : "");
                }
            }

            // === Step 4: Parse Headers ===
            int contentLength = 0;
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                int colon = line.indexOf(": ");
                if (colon != -1) {
                    String key = capitalizeHeader(line.substring(0, colon));
                    String value = line.substring(colon + 2);
                    req.headers.put(key, value);
                    if (key.equalsIgnoreCase("Content-Length")) {
                        contentLength = Integer.parseInt(value.trim());
                    }
                }
            }

            parseCookies();
            System.out.println(new String(fullRequest, "ISO-8859-1"));
            // === Step 5: Parse Body based on Content-Type ===
            if (bodyBytes.length > 0 && req.headers.get("Content-Type") != null) {
                String type = req.headers.get("Content-Type");
                switch (type) {
                    case "application/json" -> req.body = new JSON();
                    case "image/png" -> req.body = new image("src/main/resources/reqBody.png");
                    case "image/jpeg"->req.body = new image("src/main/resources/reqBody.jpg");
                    default -> req.body = new text();
                }

                req.body.contentLength = contentLength;
                req.body.contentType = type;
                req.body.serialized = bodyBytes;
                req.body.deserialize();
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
