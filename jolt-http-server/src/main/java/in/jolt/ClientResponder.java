package in.jolt;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.LinkedList;
import java.util.Queue;

public class ClientResponder extends Thread {
    Socket socket;
    Queue<Response> responseQueue;
    boolean canRespond;

    public ClientResponder(Socket socket) {
        this.socket = socket;
        responseQueue = new LinkedList<>();
        canRespond = true;
    }

    void stopResponding() {
        canRespond = false;
    }

    void provideResponse() {
        Response res = null;
        try {
            while (canRespond) {
                if (responseQueue.isEmpty()) {
                    // System.out.println("Queue is empty...");
                    try {
                        Thread.sleep(500);
                        continue;
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                } else if (!(res = responseQueue.peek()).isReady) {
                    try {
                        Thread.sleep(500);
                        // System.out.println("Here");
                        continue;
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                res.serialize();
                BufferedOutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                outputStream.write(res.serialized.getBytes());
                if (res.body != null)
                    outputStream.write(res.body.serialized);
                outputStream.flush(); // ensure all data is sent
                responseQueue.remove();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        provideResponse();
    }
}
