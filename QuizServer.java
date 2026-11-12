package onlineQuizBattle;
import java.io.*;
import java.net.*;
import java.util.*;

public class QuizServer {
    static final int PORT = 5000;
    static final int TIME_LIMIT = 15; // seconds per question

    // {question, options, correct answer}
    static final String[][] QUESTIONS = {
        {"Which protocol gives reliable delivery?", "A) UDP  B) TCP  C) ICMP", "B"},
        {"Default HTTP port?", "A) 21  B) 443  C) 80", "C"},
        {"IP works at which layer?", "A) Network  B) Transport  C) Application", "A"}
    };

    // Shared data: every access goes through synchronized(lock)
    static final Map<String, Handler> players = new LinkedHashMap<>();
    static final Map<String, String> answerChoices = new HashMap<>();
    static final Map<String, Long> answerTimes = new HashMap<>();
    static final Object lock = new Object();

    // One Handler (thread) per connected player
    static class Handler implements Runnable {
        Socket socket;
        PrintWriter out;
        String name;
        int score = 0;

        Handler(Socket socket) { this.socket = socket; }

        void send(String msg) { if (out != null) out.println(msg); }

        public void run() {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()))) {
                out = new PrintWriter(socket.getOutputStream(), true);
                String line;
                while ((line = in.readLine()) != null) {
                    String[] p = line.split("\\|");
                    if (p[0].equals("JOIN") && p.length > 1) {
                        synchronized (lock) {
                            String n = p[1];
                            int i = 2;
                            while (players.containsKey(n)) n = p[1] + i++; // keep names unique
                            name = n;
                            players.put(name, this);
                        }
                        broadcast("INFO|" + name + " joined (" + playerCount() + " players)");
                    } else if (p[0].equals("ANSWER") && name != null && p.length > 1) {
                        synchronized (lock) {
                            if (!answerChoices.containsKey(name)) { // only first answer counts
                                answerChoices.put(name, p[1].toUpperCase());
                                answerTimes.put(name, System.currentTimeMillis()); // server clock
                            }
                        }
                    }
                }
            } catch (IOException e) {
                // player dropped, handled below
            } finally {
                if (name != null) {
                    synchronized (lock) { if (players.get(name) == this) players.remove(name); }
                    broadcast("INFO|" + name + " left");
                }
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }

    static int playerCount() { synchronized (lock) { return players.size(); } }

    static void broadcast(String msg) {
        List<Handler> copy;
        synchronized (lock) { copy = new ArrayList<>(players.values()); }
        for (Handler h : copy) h.send(msg); // send outside the lock so a slow player can't block others
    }

    static String scoreboard() {
        synchronized (lock) {
            List<Handler> list = new ArrayList<>(players.values());
            list.sort((a, b) -> b.score - a.score);
            StringBuilder sb = new StringBuilder();
            for (Handler h : list) {
                if (sb.length() > 0) sb.append(",");
                sb.append(h.name).append(":").append(h.score);
            }
            return sb.toString();
        }
    }

    public static void main(String[] args) throws Exception {
        ServerSocket server = new ServerSocket(PORT);

        // Accept loop runs in its own thread so main can run the game
        Thread acceptThread = new Thread(() -> {
            while (true) {
                try {
                    Socket s = server.accept();
                    new Thread(new Handler(s)).start();
                } catch (IOException e) { break; }
            }
        });
        acceptThread.setDaemon(true);
        acceptThread.start();

        System.out.println("Server running on port " + PORT);
        System.out.println("Press Enter when all players have joined...");
        new BufferedReader(new InputStreamReader(System.in)).readLine();

        for (String[] q : QUESTIONS) {
            synchronized (lock) { answerChoices.clear(); answerTimes.clear(); }
            long start = System.currentTimeMillis();
            broadcast("QUESTION|" + q[0] + "|" + q[1] + "|" + TIME_LIMIT);

            // wait until timer ends OR everyone has answered
            while (System.currentTimeMillis() - start < TIME_LIMIT * 1000L) {
                synchronized (lock) {
                    if (!players.isEmpty() && answerChoices.size() >= players.size()) break;
                }
                Thread.sleep(100);
            }

            // scoring: 100 for correct + up to 50 speed bonus
            synchronized (lock) {
                for (Map.Entry<String, String> e : answerChoices.entrySet()) {
                    Handler h = players.get(e.getKey());
                    if (h != null && e.getValue().equals(q[2])) {
                        double used = (answerTimes.get(e.getKey()) - start) / 1000.0;
                        int bonus = (int) (50 * Math.max(0, TIME_LIMIT - used) / TIME_LIMIT);
                        h.score += 100 + bonus;
                    }
                }
            }
            broadcast("SCORES|" + scoreboard());
            Thread.sleep(2000);
        }

        String winner = "nobody";
        synchronized (lock) {
            int best = -1;
            for (Handler h : players.values())
                if (h.score > best) { best = h.score; winner = h.name; }
        }
        broadcast("WINNER|" + winner);
        Thread.sleep(500);
        System.exit(0);
    }
}