package in.jolt;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.LinkedList;
import java.util.Queue;

public class ClientConnection extends Thread {
    Socket socket;
    Request req;
    Response res;

    public ClientConnection(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        handleClient();
    }

    void handleClient() {
        try {
            try {
                socket.setSoTimeout(120000);
                System.out.println("New client connected");
                ClientResponder responder = new ClientResponder(socket);
                responder.start();
                while (true) {
                    // Read data from client
                    req = new Request();
                    res = new Response();
                    responder.responseQueue.add(res);
                    if (socket.isClosed())
                        break;
                    RequestHandler reqhandle = new RequestHandler(socket, req, res);
                    reqhandle.start();
                    while (!req.isReady) {
                        // System.out.println("Request is not Ready...!");
                        try {
                            Thread.sleep(250);
                            continue;
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    if (req.headers.get("Connection") != null && req.headers.get("Connection").equals("keep-alive")) {
                        continue;
                    }
                    while (!responder.responseQueue.isEmpty()) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    socket.close();
                    System.out.println("Client disconnected");
                    responder.stopResponding();
                    break;
                }
            } catch (SocketException e) {
                System.out.println("Closing Client Socket");
                socket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
