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
    static Map<String, Router> routers;

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
        Router router = null;
        String url = req.url;
        Map<String, ArrayList<BiConsumer<Request, Response>>> handler = null;
        if (url.charAt(url.length() - 1) != '/')
            url += "/";
        if (Jolt.routers != null) {
            for (String key : Jolt.routers.keySet()) {
                if (url.startsWith(key)) {
                    router = Jolt.routers.get(key);
                    url = url.substring(key.length() - 1);
                    break; // only one such key exists
                }
            }
        }
        switch (req.method) {
            case ("GET") -> {
                handler = (router == null) ? (Router.coreGetHandlers) : (router.routerGetHandlers);
                break;
            }
            case ("POST") -> {
                handler = (router == null) ? (Router.corePostHandlers) : (router.routerPostHandlers);
                break;
            }
            case ("PUT") -> {
                handler = (router == null) ? (Router.corePutHandlers) : (router.routerPutHandlers);
                break;
            }
        }
        if (handler != null && handler.get(url) != null) {
            res.statusCode = 200;
        } else {
            res.statusCode = 404;
            res.isReady = true;
            return;
        }
        int i = 0;
        while (i == callBackLen) {
            handler.get(url).get(i).accept(req, res);
            i++;
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
        if (url.charAt(url.length() - 1) != '/')
            url += "/";
        if (Router.coreGetHandlers == null)
            Router.initializeGET();
        Router.coreGetHandlers.put(url, new ArrayList<>());
        for (BiConsumer<Request, Response> r : callBack) {
            Router.coreGetHandlers.get(url).add(r);
        }
    }

    @SafeVarargs
    static void POST(String url, BiConsumer<Request, Response>... callBack) {
        if (url.charAt(url.length() - 1) != '/')
            url += "/";
        if (Router.corePostHandlers == null)
            Router.initializePOST();
        Router.corePostHandlers.put(url, new ArrayList<>());
        for (BiConsumer<Request, Response> r : callBack) {
            Router.corePostHandlers.get(url).add(r);
        }
    }

    @SafeVarargs
    static void PUT(String url, BiConsumer<Request, Response>... callBack) {
        if (url.charAt(url.length() - 1) != '/')
            url += "/";
        if (Router.corePutHandlers == null)
            Router.initializePOST();
        Router.corePutHandlers.put(url, new ArrayList<>());
        for (BiConsumer<Request, Response> r : callBack) {
            Router.corePutHandlers.get(url).add(r);
        }
    }

    @SafeVarargs
    static void ALL(String url, BiConsumer<Request, Response>... callBack) {
        GET(url, callBack);
        POST(url, callBack);
        PUT(url, callBack);
    }

    static void use(String url, Router router) {
        if (Jolt.routers == null)
            Jolt.routers = new HashMap<>();
        if (url.charAt(url.length() - 1) != '/')
            url += "/";
        Jolt.routers.put(url, router);
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
