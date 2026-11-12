package onlineQuizBattle;
import java.io.*;
import java.net.*;
import java.util.*;

public class QuizClient {
    public static void main(String[] args) throws IOException {
        Scanner sc = new Scanner(System.in);
        System.out.print("Server IP (Enter for local): ");
        String host = sc.nextLine().trim();
        if (host.isEmpty()) host = "127.0.0.1";
        System.out.print("Your name: ");
        String name = sc.nextLine().trim();

        Socket socket = new Socket(host, 5000);
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        out.println("JOIN|" + name);

        // Listener thread: prints server messages while main thread reads your keyboard
        Thread listener = new Thread(() -> {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    String[] p = line.split("\\|");
                    switch (p[0]) {
                        case "INFO":
                            System.out.println(">> " + p[1]);
                            break;
                        case "QUESTION":
                            System.out.println("\nQ: " + p[1]);
                            System.out.println(p[2]);
                            System.out.println("You have " + p[3] + "s - type A/B/C and press Enter:");
                            break;
                        case "SCORES":
                            System.out.println("\n--- Scoreboard ---");
                            for (String s : p[1].split(",")) System.out.println(s.replace(":", " : "));
                            break;
                        case "WINNER":
                            System.out.println("\nWinner: " + p[1] + "!");
                            System.exit(0);
                    }
                }
            } catch (IOException e) { }
            System.out.println("Disconnected from server.");
            System.exit(0);
        });
        listener.setDaemon(true);
        listener.start();

        
        while (sc.hasNextLine()) {
            out.println("ANSWER|" + sc.nextLine().trim());
        }
    }
}