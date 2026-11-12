import java.io.*;
import java.net.*;
import javax.swing.SwingUtilities;

/**
 * GameConnection - the player's TCP link to the server.
 * (Lecture: Socket, PrintWriter, BufferedReader - and a Thread that implements Runnable)
 *
 * It has its own LISTENER THREAD, because in.readLine() blocks until the server sends something -
 * if the window waited for it, the animations would freeze.
 *
 * Important Swing rule: only Swing's own "Event Dispatch Thread" may touch the screen.
 * So the listener thread never calls the game code directly - it hands every message over with
 * SwingUtilities.invokeLater(...), which runs it on the right thread.
 */
public class GameConnection implements Runnable {

    /** Whoever uses the connection (QuizClient) gets these callbacks, always on the Swing thread. */
    interface Listener {
        void onConnected();
        void onMessage(String[] parts);
        void onDisconnected(String reason);
    }

    private final String host;
    private final Listener listener;
    private Socket socket;
    private volatile PrintWriter out;
    private volatile boolean closing = false;

    GameConnection(String host, Listener listener) {
        this.host = host;
        this.listener = listener;
        Thread thread = new Thread(this);          // connecting can take a while, so it happens in the thread too
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void run() {
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, QuizServer.TCP_PORT), 4000);   // give up after 4 seconds
            out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            SwingUtilities.invokeLater(() -> listener.onConnected());

            String line;
            while ((line = in.readLine()) != null) {                 // blocks until the server sends a line
                final String[] parts = line.split("\\|", 9);         // e.g. QUESTION|1|10|15|text|A|B|C|D
                SwingUtilities.invokeLater(() -> listener.onMessage(parts));
            }
            fail("The server closed the connection.");
        } catch (IOException e) {
            fail(out == null ? "Could not connect to " + host + "." : "Lost the connection to the server.");
        }
    }

    private void fail(String reason) {
        if (!closing) SwingUtilities.invokeLater(() -> listener.onDisconnected(reason));
    }

    /** Sends one line to the server. */
    void send(String message) {
        PrintWriter writer = out;
        if (writer != null) writer.println(message);
    }

    /** Closes the connection quietly (no callback). */
    void close() {
        closing = true;
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
    }
}
