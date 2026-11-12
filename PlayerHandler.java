import java.io.*;
import java.net.*;
import java.util.*;

/**
 * PlayerHandler - one of these runs in its OWN THREAD for every connection to the server.
 * (Lecture: Processes and Threads - creating a thread by implementing Runnable)
 *
 * Why one thread per connection?
 *   in.readLine() BLOCKS: it waits until the player sends something. If the server had
 *   only one thread it could serve only one player at a time (the "Hurry up, I'm waiting"
 *   picture in Lecture 1). With one thread per player everybody can play at once.
 *
 * Messages a player can send (one line of text each, parts separated by |):
 *   JOIN|name|colour       sit down in the room with this name and colour (0-5)
 *   START                  the leader (first player) starts the game early
 *   ANSWER|number|letter   answer question <number> with A, B, C or D
 *   LIFELINE|number        use the 50/50 lifeline on question <number>
 */
public class PlayerHandler implements Runnable {

    private final QuizServer server;
    private final Socket socket;        // the TCP connection to this player
    private final PrintWriter out;      // used to SEND text to this player
    volatile boolean connected = true;  // becomes false when the player disconnects

    // Player data. Several threads use it, so only touch it while holding server.lock
    String name = null;                 // set when the player has joined
    int colourIndex = -1;               // 0-5, nobody else may use the same colour
    int score = 0;
    int streak = 0;                     // correct answers in a row
    boolean lifelineUsed = false;       // 50/50 can be used once per game
    String answer = null;               // answer to the current question (null = no answer yet)
    long answerTime = 0;                // when that answer arrived (server clock)

    public PlayerHandler(QuizServer server, Socket socket) throws IOException {
        this.server = server;
        this.socket = socket;
        this.out = new PrintWriter(socket.getOutputStream(), true);   // true = flush after every line
    }

    /** Sends one line of text to this player. */
    void send(String message) {
        out.println(message);
    }

    /** Closes the connection (this also ends the thread, because readLine() then fails). */
    void close() {
        try { socket.close(); } catch (IOException ignored) { }
    }

    /** The thread body: keep reading this player's messages until they disconnect. */
    @Override
    public void run() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            String line;
            while ((line = in.readLine()) != null) {     // blocks until a line arrives
                handleMessage(line);
            }
        } catch (IOException e) {
            // connection lost - the cleanup happens in finally
        } finally {
            leave();
        }
    }

    /** Decides what to do with one message from the player. */
    private void handleMessage(String line) {
        String[] p = line.split("\\|", 3);       // COMMAND | data | data
        String command = p[0];

        if (command.equals("JOIN") && p.length >= 3) {
            if (name == null) join(p[1], toNumber(p[2]));        // ignore a second JOIN
        } else if (name == null) {
            return;                                             // ignore everything until joined
        } else if (command.equals("START")) {
            requestStart();
        } else if (command.equals("ANSWER") && p.length >= 3) {
            submitAnswer(toNumber(p[1]), p[2].trim().toUpperCase());
        } else if (command.equals("LIFELINE") && p.length >= 2) {
            useLifeline(toNumber(p[1]));
        }
    }

    /** Sits the player down - if the room is open, not full, and the colour is still free. */
    private void join(String wantedName, int wantedColour) {
        String clean = wantedName.replaceAll("[^A-Za-z0-9_ ]", "").trim();   // keeps the protocol safe
        if (clean.isEmpty()) clean = "Player";
        if (clean.length() > 12) clean = clean.substring(0, 12);

        String problem = null;
        synchronized (server.lock) {                 // check + take the colour in ONE step, so two players can never get the same one
            if (server.gameStarted) {
                problem = "The game has already started.";
            } else if (server.players.size() >= server.maxPlayers) {
                problem = "The room is full.";
            } else if (wantedColour < 0 || wantedColour >= QuizServer.NUMBER_OF_COLOURS) {
                problem = "Please pick a colour.";
            } else {
                for (PlayerHandler other : server.players) {
                    if (other.colourIndex == wantedColour) problem = "That colour was just taken - pick another one.";
                }
            }
            if (problem == null) {
                String uniqueName = clean;
                int number = 2;
                while (nameIsUsed(uniqueName)) uniqueName = clean + number++;   // two "Sam"s become Sam and Sam2
                name = uniqueName;
                colourIndex = wantedColour;
                server.players.add(this);
            }
        }

        if (problem != null) {
            send("JOINFAIL|" + problem);
            return;
        }
        send("JOINED|" + name + "|" + colourIndex);
        server.broadcast(server.roomMessage());      // everybody sees the new player + the taken colour
        System.out.println("[+] " + name + " joined (colour #" + colourIndex + ")");
    }

    private boolean nameIsUsed(String wanted) {      // call while holding the lock
        for (PlayerHandler other : server.players) {
            if (wanted.equals(other.name)) return true;
        }
        return false;
    }

    /** Only the leader (the first player to join) can start the game early. */
    private void requestStart() {
        synchronized (server.lock) {
            boolean isLeader = !server.players.isEmpty() && server.players.get(0) == this;
            if (isLeader && server.players.size() >= 2 && !server.gameStarted) server.startRequested = true;
        }
    }

    /** Stores the player's answer - but only the FIRST answer to the CURRENT question counts. */
    private void submitAnswer(int questionNumber, String letter) {
        boolean accepted = false;
        synchronized (server.lock) {
            boolean questionIsOpen = server.currentQuestion != null
                    && questionNumber == server.currentNumber;       // ignores answers to old questions
            if (questionIsOpen && answer == null && letter.matches("[A-D]")) {
                answer = letter;
                answerTime = System.currentTimeMillis();             // SERVER clock, so nobody can fake a fast time
                accepted = true;
            }
        }
        if (accepted) server.broadcast("LOCKED|" + name);            // everybody sees "this player has answered" (but not WHAT)
    }

    /** 50/50 lifeline: tells the player two WRONG answers to ignore. One use per game. */
    private void useLifeline(int questionNumber) {
        String hint = null;
        synchronized (server.lock) {
            QuestionBank.Question q = server.currentQuestion;
            if (q != null && questionNumber == server.currentNumber && answer == null && !lifelineUsed) {
                lifelineUsed = true;
                List<String> wrong = new ArrayList<>(Arrays.asList("A", "B", "C", "D"));
                wrong.remove(q.answer);              // keep only the 3 wrong letters
                Collections.shuffle(wrong);
                hint = wrong.get(0) + "|" + wrong.get(1);   // cross out two of them
            }
        }
        if (hint != null) send("HINT|" + hint);
    }

    /**
     * Called by the server when time is up (while it holds the lock).
     * Updates score and streak and returns the points won in this question.
     *   points = 100 (correct) + up to 50 (speed bonus) + 10 per correct answer in a row (max 50)
     */
    int scoreRound(QuestionBank.Question q, long questionStart, int timeLimitSeconds) {
        if (answer == null || !answer.equals(q.answer)) {
            streak = 0;                                      // wrong or no answer: the streak is broken
            return 0;
        }
        double secondsUsed = (answerTime - questionStart) / 1000.0;
        int speedBonus = (int) (50 * Math.max(0, timeLimitSeconds - secondsUsed) / timeLimitSeconds);
        int streakBonus = Math.min(streak, 5) * 10;
        streak++;

        int points = 100 + speedBonus + streakBonus;
        score += points;
        return points;
    }

    /** Cleans up when the connection ends. */
    private void leave() {
        connected = false;
        boolean wasPlayer;
        boolean gameRunning;
        synchronized (server.lock) {
            server.connections.remove(this);
            wasPlayer = (name != null);
            gameRunning = server.gameStarted;
            // in the lobby the seat (and the colour) become free again; during the game the player stays in the list, greyed out
            if (wasPlayer && !gameRunning) server.players.remove(this);
        }
        if (wasPlayer) {
            System.out.println("[-] " + name + " left");
            server.broadcast(server.roomMessage());
            if (gameRunning) server.broadcast("INFO|" + name + " left the arena");
        }
        close();
    }

    /** Turns text into a number, or -1 if it is not a number. */
    private int toNumber(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
