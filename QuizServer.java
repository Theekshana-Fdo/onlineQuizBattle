import java.io.*;
import java.net.*;
import java.util.*;

/**
 * QUIZ BATTLE - SERVER
 * EC5205 Operating Systems & Network Programming project
 *
 * A QuizServer is ONE game room (arena) for 2 to 4 players. The arena is created here, on its own
 * in a console window (start_server.bat  =  java QuizServer); the game windows (QuizClient) only join it.
 *
 * What the server does:
 *   1. Answers UDP "is anybody there?" requests so clients can find it     (Discovery.java)
 *   2. Accepts TCP connections - one thread per player                     (PlayerHandler.java)
 *   3. Lobby: players choose a name + colour (no two players share a colour).
 *      The game starts when the room is full, or when the first player clicks START.
 *   4. Plays the quiz: sends each question, waits for the answers, then REVEALS every
 *      player's answer at once and updates the scores.
 *
 * Threads used:
 *   - game thread ........... runs the lobby wait, the questions, the timer and the scoring
 *   - accept thread ......... waits for new players:  serverSocket.accept()
 *   - one thread per player . reads that player's messages            (PlayerHandler)
 *   - UDP thread ............ answers discovery requests              (Discovery)
 */
public class QuizServer {

    static final int TCP_PORT = 5000;           // the game uses TCP (reliable, ordered)
    static final int NUMBER_OF_COLOURS = 6;     // players choose one of 6 colours

    // Room settings
    final int maxPlayers;
    final int questionsPerGame;
    final int secondsPerQuestion;

    // ---- Shared data. Many threads use it, so ALWAYS access it inside synchronized (lock) ----
    final Object lock = new Object();
    final List<PlayerHandler> connections = new ArrayList<>();  // everybody connected (even before choosing a name)
    final List<PlayerHandler> players = new ArrayList<>();      // joined players, in join order (the first one is the leader)
    QuestionBank.Question currentQuestion = null;               // null = no question is open
    int currentNumber = 0;                                      // 1, 2, 3 ...
    boolean gameStarted = false;
    boolean startRequested = false;                             // the leader clicked START

    private List<QuestionBank.Question> questions;
    private ServerSocket serverSocket;
    private DatagramSocket udpSocket;
    private Thread gameThread;
    private volatile boolean running = false;

    public QuizServer(int maxPlayers, int questionsPerGame, int secondsPerQuestion) {
        this.maxPlayers = Math.max(2, Math.min(4, maxPlayers));
        this.questionsPerGame = Math.max(1, questionsPerGame);
        this.secondsPerQuestion = Math.max(5, Math.min(60, secondsPerQuestion));
    }

    // ------------------------------------------------------------------
    // Start / stop
    // ------------------------------------------------------------------

    /** Loads the questions, opens the network ports and starts the threads. */
    public void start() throws IOException {
        List<QuestionBank.Question> all = QuestionBank.load("questions.txt");
        if (all.isEmpty()) throw new IOException("questions.txt has no valid questions");
        Collections.shuffle(all);                                    // a different order every game
        questions = new ArrayList<>(all.subList(0, Math.min(questionsPerGame, all.size())));

        serverSocket = new ServerSocket(TCP_PORT);                   // TCP: the game itself (BindException if busy)
        udpSocket = Discovery.startResponder();                      // UDP: lets clients find us
        running = true;

        Thread acceptThread = new Thread(this::acceptLoop);
        acceptThread.setDaemon(true);
        acceptThread.start();

        gameThread = new Thread(this::runGame);
        gameThread.start();
    }

    /** Waits until the game is over (used by the console version). */
    public void waitForEnd() throws InterruptedException {
        if (gameThread != null) gameThread.join();
    }

    /** Closes everything. */
    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
        if (udpSocket != null) udpSocket.close();
        List<PlayerHandler> copy;
        synchronized (lock) { copy = new ArrayList<>(connections); }
        for (PlayerHandler h : copy) h.close();
        if (gameThread != null) gameThread.interrupt();
    }

    // ------------------------------------------------------------------
    // The accept thread: waits for new connections and starts a thread for each one.
    // (Lecture: ServerSocket + accept(), but with a thread so we never stop listening)
    // ------------------------------------------------------------------
    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();               // blocks until somebody connects
                PlayerHandler handler = new PlayerHandler(this, socket);
                synchronized (lock) { connections.add(handler); }
                new Thread(handler).start();                         // one thread per connection
                handler.send(roomMessage());                         // so the newcomer sees which colours are taken
            } catch (IOException e) {
                if (running) System.out.println("Connection problem: " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // The game thread: lobby -> countdown -> questions -> game over
    // ------------------------------------------------------------------
    private void runGame() {
        try {
            // ---- 1. Lobby: wait until the room is full (or the leader clicks START) ----
            while (running && !readyToStart()) Thread.sleep(200);
            if (!running) return;
            Thread.sleep(800);                                       // let the last player see themselves sit down
            synchronized (lock) { gameStarted = true; }
            broadcast(roomMessage());
            broadcast("STARTING|3");
            System.out.println("\nThe battle begins!");
            Thread.sleep(3800);                                      // the clients show 3 - 2 - 1 - GO!

            // ---- 2. The questions ----
            for (int i = 0; i < questions.size() && running; i++) {
                if (connectedPlayers() == 0) {
                    System.out.println("Everybody left - ending the game.");
                    break;
                }
                playQuestion(i + 1, questions.get(i));
            }

            // ---- 3. Game over: send the final ranking ----
            broadcast(roomMessage());
            broadcast("GAMEOVER|" + ranking());
            System.out.println("\nGAME OVER!  Ranking: " + ranking());
            Thread.sleep(3000);                                      // give the message time to arrive
        } catch (InterruptedException e) {
            // the server was stopped
        } finally {
            stop();
        }
    }

    /** The game starts when the room is full, or when the leader clicked START (and 2+ players joined). */
    private boolean readyToStart() {
        synchronized (lock) {
            return players.size() >= 2 && (players.size() >= maxPlayers || startRequested);
        }
    }

    // ------------------------------------------------------------------
    // One question: send it, wait for the answers, score, REVEAL everybody's answer.
    // ------------------------------------------------------------------
    private void playQuestion(int number, QuestionBank.Question q) throws InterruptedException {
        long start = System.currentTimeMillis();
        List<PlayerHandler> round;                       // the players taking part in this question

        synchronized (lock) {
            round = new ArrayList<>(players);
            for (PlayerHandler p : round) p.answer = null;   // everybody starts without an answer
            currentQuestion = q;                             // the question is now OPEN
            currentNumber = number;
        }

        System.out.println("\nQuestion " + number + "/" + questions.size() + ": " + q.text);
        broadcast("QUESTION|" + number + "|" + questions.size() + "|" + secondsPerQuestion + "|" + q.text
                + "|" + q.options[0] + "|" + q.options[1] + "|" + q.options[2] + "|" + q.options[3]);

        // Wait until time is up OR everybody has answered (checks 10 times per second)
        while (running && System.currentTimeMillis() - start < secondsPerQuestion * 1000L
                && !everyoneAnswered(round)) {
            Thread.sleep(100);
        }

        // Time is up: CLOSE the question (late answers are ignored) and score everybody
        StringBuilder reveal = new StringBuilder();
        synchronized (lock) {
            currentQuestion = null;
            for (PlayerHandler p : round) {
                String letter = (p.answer == null) ? "-" : p.answer;
                int points = p.scoreRound(q, start, secondsPerQuestion);
                if (reveal.length() > 0) reveal.append(",");
                reveal.append(p.name).append(":").append(letter).append(":").append(points).append(":").append(p.streak);
            }
        }

        // Everybody's answer is revealed at the same moment: name:letter:points:streak
        broadcast("REVEAL|" + q.answer + "|" + q.note + "|" + reveal);
        broadcast(roomMessage());                        // the new total scores
        System.out.println("Answers: " + reveal);
        Thread.sleep(6500);                              // time to enjoy the reveal animation
    }

    /** True when every connected player in this round has answered. */
    private boolean everyoneAnswered(List<PlayerHandler> round) {
        synchronized (lock) {
            for (PlayerHandler p : round) {
                if (p.connected && p.answer == null) return false;
            }
            return true;
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Sends a message to EVERYBODY connected (players who are still choosing their colour, too). */
    void broadcast(String message) {
        List<PlayerHandler> copy;
        synchronized (lock) { copy = new ArrayList<>(connections); }
        for (PlayerHandler h : copy) h.send(message);    // send OUTSIDE the lock so one slow player cannot block the rest
    }

    /**
     * The state of the room, in one line:  ROOM|maxPlayers|started|name:colour:score:connected,...
     * Clients redraw the lobby / scoreboard from this message.
     */
    String roomMessage() {
        synchronized (lock) {
            StringBuilder list = new StringBuilder();
            for (PlayerHandler p : players) {
                if (list.length() > 0) list.append(",");
                list.append(p.name).append(":").append(p.colourIndex).append(":")
                    .append(p.score).append(":").append(p.connected ? 1 : 0);
            }
            return "ROOM|" + maxPlayers + "|" + (gameStarted ? 1 : 0) + "|" + list;
        }
    }

    int connectedPlayers() {
        synchronized (lock) {
            int count = 0;
            for (PlayerHandler p : players) if (p.connected) count++;
            return count;
        }
    }

    /** Player names from best to worst score. */
    String ranking() {
        synchronized (lock) {
            List<PlayerHandler> sorted = new ArrayList<>(players);
            sorted.sort((a, b) -> b.score - a.score);        // the sort is stable: ties keep the join order
            StringBuilder text = new StringBuilder();
            for (PlayerHandler p : sorted) {
                if (text.length() > 0) text.append(",");
                text.append(p.name);
            }
            return text.toString();
        }
    }

    /** This computer's network addresses (shown so friends can type one if auto-discovery fails). */
    static List<String> localAddresses() {
        List<String> result = new ArrayList<>();
        try {
            for (NetworkInterface card : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress address : Collections.list(card.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        result.add(address.getHostAddress());
                    }
                }
            }
        } catch (SocketException e) {
            // no network cards - return an empty list
        }
        return result;
    }

    // ------------------------------------------------------------------
    // The program you start with start_server.bat:   java QuizServer
    // It creates the arena. Players join it with start_client.bat.
    // After a game it opens a fresh arena with the same settings, so everybody can play again.
    // ------------------------------------------------------------------
    public static void main(String[] args) throws Exception {
        BufferedReader keyboard = new BufferedReader(new InputStreamReader(System.in));

        System.out.println("==================================================");
        System.out.println("   QUIZ BATTLE  -  SERVER  (creates the arena)");
        System.out.println("==================================================");
        int players = askNumber(keyboard, "Number of players (2-4)", 2, 2, 4);
        int questions = askNumber(keyboard, "Questions per game", 10, 1, 50);
        int seconds = askNumber(keyboard, "Seconds per question", 15, 5, 60);

        int arenaNumber = 1;
        while (true) {
            QuizServer server = new QuizServer(players, questions, seconds);
            // the first arena must open at once; later ones may have to wait a moment until the old port is free
            if (!openArena(server, arenaNumber == 1 ? 1 : 5)) return;

            System.out.println("\nArena " + arenaNumber + " is open for " + players + " players, " + questions + (questions == 1 ? " question." : " questions."));
            System.out.println("Players start start_client.bat - they find this arena by themselves.");
            System.out.println("If a network blocks the search, they can type one of these addresses:");
            for (String address : localAddresses()) System.out.println("   " + address);
            System.out.println("The game starts when all " + players + " seats are taken (or the first player clicks START NOW).");

            server.waitForEnd();                                   // wait until this game is over
            System.out.println("\nGame finished. A new arena opens in 5 seconds.  (Close this window to stop the server.)");
            Thread.sleep(5000);
            arenaNumber++;
        }
    }

    /** Starts the server. If the port is still busy it tries again once per second. Returns false if it cannot start. */
    private static boolean openArena(QuizServer server, int attempts) throws InterruptedException {
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                server.start();
                return true;
            } catch (BindException e) {
                if (attempt < attempts) Thread.sleep(1000);
            } catch (IOException e) {
                System.out.println("Could not start: " + e.getMessage());
                return false;
            }
        }
        System.out.println("Port " + TCP_PORT + " is already in use. Is another server running?");
        return false;
    }

    /** Asks for a number. Pressing Enter (or typing nonsense) gives the default. */
    static int askNumber(BufferedReader keyboard, String question, int defaultValue, int min, int max)
            throws IOException {
        System.out.print(question + " [" + defaultValue + "]: ");
        String line = keyboard.readLine();
        if (line == null || line.trim().isEmpty()) return defaultValue;
        try {
            int value = Integer.parseInt(line.trim());
            return Math.max(min, Math.min(max, value));   // keep it inside the allowed range
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
