package in.jolt;

import java.util.*;
import java.util.function.BiConsumer;

public class Router {
    static Map<String, ArrayList<BiConsumer<Request, Response>>> coreGetHandlers;
    static Map<String, ArrayList<BiConsumer<Request, Response>>> corePostHandlers;
    static Map<String, ArrayList<BiConsumer<Request, Response>>> corePutHandlers;
    static Map<String, Router> routers;

    static void initializeGET() {
        coreGetHandlers = new HashMap<>();
    }

    static void initializePOST() {
        corePostHandlers = new HashMap<>();
    }

    static void initializePUT() {
        corePutHandlers = new HashMap<>();
    }

    Map<String, ArrayList<BiConsumer<Request, Response>>> routerGetHandlers;
    Map<String, ArrayList<BiConsumer<Request, Response>>> routerPostHandlers;
    Map<String, ArrayList<BiConsumer<Request, Response>>> routerPutHandlers;

    public Router() {
        routerGetHandlers = new HashMap<>();
        routerPostHandlers = new HashMap<>();
        routerPutHandlers = new HashMap<>();
    }

    @SafeVarargs
    final void GET(String url, BiConsumer<Request, Response>... callBack) {
        if (url.charAt(url.length() - 1) != '/')
            url += "/";
        routerGetHandlers.put(url, new ArrayList<>());
        for (BiConsumer<Request, Response> r : callBack) {
            routerGetHandlers.get(url).add(r);
        }
    }

    @SafeVarargs
    final void POST(String url, BiConsumer<Request, Response>... callBack) {
        if (url.charAt(url.length() - 1) != '/')
            url += "/";
        routerPostHandlers.put(url, new ArrayList<>());
        for (BiConsumer<Request, Response> r : callBack) {
            routerPostHandlers.get(url).add(r);
        }
    }

    @SafeVarargs
    final void PUT(String url, BiConsumer<Request, Response>... callBack) {
        if (url.charAt(url.length() - 1) != '/')
            url += "/";
        routerPutHandlers.put(url, new ArrayList<>());
        for (BiConsumer<Request, Response> r : callBack) {
            routerPutHandlers.get(url).add(r);
        }
    }

    @SafeVarargs
    final void ALL(String url, BiConsumer<Request, Response>... callBack) {
        GET(url, callBack);
        POST(url, callBack);
        PUT(url, callBack);
    }
}
