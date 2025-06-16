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
    Socket socket;
    int callBackLen;
    String authType;
    private Jolt(Socket socket) {
        Response.statusMapper();
        req=new Request();
        res=new Response();
        this.socket=socket;
        authType="Bearer";
    }
    static void runJoltThread(Socket socket){
        Jolt serverThread = new Jolt(socket);
        serverThread.handleClient();
    }
    static void authorizeClient(Request req,Response res,String realm){
        if(req.authorize())
        return;
        res.statusCode=401;
        res.headers.put("WWW-Authenticate","Basic realm=\""+realm+"\", error=\""+req.authFlag+"\"");
        throw new UnauthorizedException(req.authFlag);
    }
    void next() {
        callBackLen++;
    }
    @SafeVarargs
    static void GET(String url,BiConsumer<Request,Response>...callBack) {
        if(Handlers.getHandlers==null)
        Handlers.initializeGET();
        Handlers.getHandlers.put(url, new ArrayList<>());
        for (BiConsumer<Request,Response> r : callBack) {
            Handlers.getHandlers.get(url).add(r);
        }
    }
    @SafeVarargs
    static void POST(String url, BiConsumer<Request,Response>... callBack) {
        if(Handlers.postHandlers==null)
        Handlers.initializePOST();
        Handlers.postHandlers.put(url, new ArrayList<>());
        for (BiConsumer<Request,Response> r : callBack) {
            Handlers.postHandlers.get(url).add(r);
        }
    }
    @SafeVarargs
    static void PUT(String url, BiConsumer<Request,Response>... callBack){
         if(Handlers.postHandlers==null)
        Handlers.initializePOST();
        Handlers.postHandlers.put(url, new ArrayList<>());
        for (BiConsumer<Request,Response> r : callBack) {
            Handlers.postHandlers.get(url).add(r);
        }
    }
    void parseRequest(BufferedReader reader) {
        String line;
        try {
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
                String str= new String(body);
                req.body.serialized=str.getBytes(); 
                req.body.deserialize();
                System.out.println("Received: " + new String(body));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    void handleRequest() {
        switch (req.method) {
            case ("GET") -> {
                if (Handlers.getHandlers.get(req.url) != null) {
                    res.statusCode = 200;
                } else {
                    res.statusCode = 404;
                    return;
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
                    return;
                }
                while (i == callBackLen) {
                    Handlers.postHandlers.get(req.url).get(i).accept(req, res);
                    i++;
                }
            }
        }
    }

    void handleClient() {
        Request req=new Request();
        try {
            try {
                socket.setSoTimeout(120000);
                System.out.println("New client connected");
                while (true) {
                    // Read data from client
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    parseRequest(reader);
                    handleRequest();
                    provideResponse();
                    if (req.headers.get("Connection")!=null && req.headers.get("Connection").equals("keep-alive")) {
                        continue;
                    }
                    socket.close();
                    System.out.println("Client disconnected");
                    } }catch (SocketException e) {
                        System.out.println("Closing Client Socket");
                        socket.close();
                    }
                }
         catch (IOException e) {
            e.printStackTrace();
        }
    }

    static void listen(int port, Runnable callBack) {
        while (true) {
            try (ServerSocket serverSocket = new ServerSocket(port)) {
                callBack.run();
                new Thread(() -> {
                    try {
                        Jolt.runJoltThread(serverSocket.accept());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }).run();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    void provideResponse() {
        try{
            res.serialize();
            BufferedOutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
            outputStream.write(res.serialized.getBytes());
            if(res.body!=null)
            outputStream.write(res.body.serialized);
            outputStream.flush(); // ensure all data is sent
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
