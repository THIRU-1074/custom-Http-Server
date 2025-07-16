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
    private Map<String, String> matchPathParams(String formalPath, String actualPath) {
        String[] formalParts = formalPath.split("/");
        String[] actualParts = actualPath.split("/");

        if (formalParts.length != actualParts.length) return null;

        Map<String, String> pathParams = new HashMap<>();
        for (int i = 0; i < formalParts.length; i++) {
            if (formalParts[i].startsWith(":")) {
                pathParams.put(formalParts[i].substring(1), actualParts[i]);
            } else if (!formalParts[i].equals(actualParts[i])) {
                return null; // mismatch
            }
        }
        return pathParams;
    }
    void handleRequest() {
        Router router = null;
        String url = req.url;
        Map<String, ArrayList<BiConsumer<Request, Response>>> handler = null;
        if (url.charAt(url.length() - 1) != '/')
            url += "/";
        if (Jolt.routers != null) {
            for (String key : Jolt.routers.keySet()) {
                System.out.println(key);
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
        ArrayList<BiConsumer<Request,Response>>middleware=null;
        res.statusCode=404;
        if (handler != null) {
            for (String key : handler.keySet()) {
                req.urlPathParams=matchPathParams(key, req.url);
                if(req.urlPathParams!=null){
                    middleware=handler.get(key);
                    
                    break;
                }
            }
            if(middleware!=null)
                res.statusCode = 200;
        }
        if(res.statusCode!=200){
            res.isReady = true;
            return;
        }
        int i = 0;
        while (i == callBackLen) {
            middleware.get(i).accept(req, res);
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
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            callBack.run();
            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(new ClientConnection(clientSocket)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
