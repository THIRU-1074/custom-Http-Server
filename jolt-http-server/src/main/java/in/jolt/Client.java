package in.jolt;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Scanner;

public class Client {

    public static void main(String[] args) {
        String serverAddress = "192.168.166.198"; // or use IP like "127.0.0.1"
        int port = 5000;

        try (Socket socket = new Socket(serverAddress, port)) {
            PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
            Scanner scanner = new Scanner(System.in);

            System.out.println("Connected to server at " + serverAddress + ":" + port);
            System.out.println("Type messages to send. Type 'exit' to quit.");

            while (true) {
                System.out.print("> ");
                String message = scanner.nextLine();
                if (message.equalsIgnoreCase("exit")) {
                    break;
                }
                out.println(message);
            }

            System.out.println("Disconnected from server.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
