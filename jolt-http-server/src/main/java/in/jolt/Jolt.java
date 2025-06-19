package in.jolt;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.*;
import java.util.function.BiConsumer;

public class Jolt {
    Request req;
    Response res;
    int callBackLen;
    String authType;

    private Jolt(Request req, Response res) {
        Response.statusMapper();
        this.req = req;
        this.res = res;
        authType = "Bearer";
    }

    static void runJoltThread(Request req, Response res) {
        Jolt serverThread = new Jolt(req, res);
        serverThread.handleRequest();
    }

    void handleRequest() {
        switch (req.method) {
            case ("GET") -> {
                if (Handlers.getHandlers.get(req.url) != null) {
                    res.statusCode = 200;
                } else {
                    res.statusCode = 404;
                    break;
                }
                int i = 0;
                while (i == callBackLen) {
                    Handlers.getHandlers.get(req.url).get(i).accept(req, res);
                    i++;
                }
                break;
            }
            case ("POST") -> {
                int i = 0;
                if (Handlers.postHandlers.get(req.url) != null) {
                    res.statusCode = 200;
                } else {
                    res.statusCode = 404;
                    break;
                }
                while (i == callBackLen) {
                    Handlers.postHandlers.get(req.url).get(i).accept(req, res);
                    i++;
                }
            }
        }
        res.isReady = true;
        // System.out.println("Readiness has been set...");
    }

    static void authorizeClient(Request req, Response res, String realm) {
        if (req.authorize())
            return;
        res.statusCode = 401;
        res.headers.put("WWW-Authenticate", "Basic realm=\"" + realm + "\", error=\"" + req.authFlag + "\"");
        throw new UnauthorizedException(req.authFlag);
    }

    void next() {
        callBackLen++;
    }

    @SafeVarargs
    static void GET(String url, BiConsumer<Request, Response>... callBack) {
        if (Handlers.getHandlers == null)
            Handlers.initializeGET();
        Handlers.getHandlers.put(url, new ArrayList<>());
        for (BiConsumer<Request, Response> r : callBack) {
            Handlers.getHandlers.get(url).add(r);
        }
    }

    @SafeVarargs
    static void POST(String url, BiConsumer<Request, Response>... callBack) {
        if (Handlers.postHandlers == null)
            Handlers.initializePOST();
        Handlers.postHandlers.put(url, new ArrayList<>());
        for (BiConsumer<Request, Response> r : callBack) {
            Handlers.postHandlers.get(url).add(r);
        }
    }

    @SafeVarargs
    static void PUT(String url, BiConsumer<Request, Response>... callBack) {
        if (Handlers.postHandlers == null)
            Handlers.initializePOST();
        Handlers.postHandlers.put(url, new ArrayList<>());
        for (BiConsumer<Request, Response> r : callBack) {
            Handlers.postHandlers.get(url).add(r);
        }
    }

    static void listen(int port, Runnable callBack) {
        while (true) {
            try (ServerSocket serverSocket = new ServerSocket(port)) {
                callBack.run();
                new Thread(() -> {
                    try {
                        ClientConnection.connectToClient(serverSocket.accept());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }).run();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
