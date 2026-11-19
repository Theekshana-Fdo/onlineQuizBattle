import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;

/**
 * QuizClient - the game window. Start this class to play:   java -cp out QuizClient
 *
 * It does two jobs:
 *   1. It is the window (a JPanel inside a JFrame) that draws the current Screen 60 times per second.
 *   2. It owns the network connection and passes every server message to the right screen.
 *
 * The client can only JOIN an arena. The arena (number of players, questions ...) is created
 * by the server: start_server.bat / QuizServer.
 *
 * Two screens exist: WelcomeScreen (find the arena, pick name and colour) and ArenaScreen (the stadium).
 */
public class QuizClient extends JPanel implements GameConnection.Listener {

    // ------------------------------------------------------------------
    // Small data classes: what the server tells us about the room (ROOM message)
    // ------------------------------------------------------------------
    static class PlayerInfo {
        String name;
        int color;
        int score;
        boolean connected;
    }

    static class Room {
        int max = 4;
        boolean started = false;
        List<PlayerInfo> players = new ArrayList<>();      // in join order - the first one is the leader
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------
    private GameConnection connection = null;       // our link to the server
    private final WelcomeScreen welcome = new WelcomeScreen(this);
    private ArenaScreen arena = null;               // exists from the moment we have joined
    private Screen current = welcome;
    private Room lastRoom = new Room();
    private int searchId = 0;                       // numbers the UDP searches, so an old search is ignored

    // where the 1100 x 700 canvas sits inside the window (it is scaled to fit)
    private double scale = 1, offsetX = 0, offsetY = 0;

    QuizClient() {
        setBackground(new Color(8, 10, 30));
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);        // so the Tab key reaches us (switches between the input boxes)

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                current.mouseMoved(toVirtualX(e.getX()), toVirtualY(e.getY()));
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                current.mousePressed(toVirtualX(e.getX()), toVirtualY(e.getY()));
            }
        });
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent e) {
                current.keyTyped(e.getKeyChar());
            }
        });

        // the animation clock: repaint about 60 times per second
        new javax.swing.Timer(16, e -> repaint()).start();

        welcome.reset();                            // start looking for the server right away
    }

    // ------------------------------------------------------------------
    // Drawing: the virtual canvas is scaled to the window size
    // ------------------------------------------------------------------
    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        scale = Math.min(getWidth() / (double) Ui.W, getHeight() / (double) Ui.H);
        offsetX = (getWidth() - Ui.W * scale) / 2;
        offsetY = (getHeight() - Ui.H * scale) / 2;
        g.translate(offsetX, offsetY);
        g.scale(scale, scale);
        g.clipRect(0, 0, Ui.W, Ui.H);
        Ui.smooth(g);
        current.paint(g, System.currentTimeMillis());
        g.dispose();
    }

    private double toVirtualX(int screenX) {
        return (screenX - offsetX) / scale;
    }

    private double toVirtualY(int screenY) {
        return (screenY - offsetY) / scale;
    }

    // ------------------------------------------------------------------
    // Actions that the screens ask for
    // ------------------------------------------------------------------

    /** Looks for a game on the local network (UDP broadcast). The answer comes back to the welcome page. */
    void searchForServer() {
        final int id = ++searchId;
        Thread thread = new Thread(() -> {
            String ip = Discovery.findServer();                       // takes up to 2 seconds, so not on the Swing thread
            SwingUtilities.invokeLater(() -> {
                if (id == searchId) welcome.onSearchResult(ip);       // ignore the answer if the player has moved on
            });
        });
        thread.setDaemon(true);
        thread.start();
    }

    /** Opens a TCP connection to the server. (We join the room later, when the player presses the button.) */
    void connectTo(String ip) {
        closeConnection();
        connection = new GameConnection(ip, this);
    }

    void closeConnection() {
        searchId++;                                                   // cancels a search that is still running
        if (connection != null) connection.close();
        connection = null;
    }

    /** JOIN ARENA: tell the server our name and colour. */
    void joinRoom(String name, int color) {
        send("JOIN|" + name + "|" + color);
    }

    void send(String message) {
        if (connection != null) connection.send(message);
    }

    /** Leaves the stadium and shows the welcome page again. */
    void backToMenu() {
        arena = null;
        welcome.reset();                            // closes the old connection and searches for the server again
        current = welcome;
    }

    // ------------------------------------------------------------------
    // Messages from the server (GameConnection calls these on the Swing thread)
    // ------------------------------------------------------------------
    @Override
    public void onConnected() {
        welcome.onConnected();
    }

    @Override
    public void onMessage(String[] p) {
        switch (p[0]) {
            case "ROOM":
                lastRoom = parseRoom(p);
                if (arena != null) arena.roomChanged(lastRoom);
                else welcome.onRoom(lastRoom);
                break;

            case "JOINED":                                            // JOINED|name|colour - we have a seat!
                arena = new ArenaScreen(this, p[1], Integer.parseInt(p[2]));
                arena.roomChanged(lastRoom);
                current = arena;
                break;

            case "JOINFAIL":                                          // name/colour problem, room full, game started ...
                welcome.onJoinFailed(p[1]);
                break;

            default:                                                  // STARTING, QUESTION, LOCKED, HINT, REVEAL, INFO, GAMEOVER
                if (arena != null) arena.onMessage(p);
        }
    }

    @Override
    public void onDisconnected(String reason) {
        if (arena != null && arena.phase == ArenaScreen.PODIUM) return;   // the server closes after the game - that is normal
        boolean inGame = (arena != null);
        connection = null;
        arena = null;
        current = welcome;
        if (inGame) {                                                 // we were in the stadium: back to the menu with a message
            welcome.reset();
            welcome.showError(reason);
        } else {
            welcome.onDisconnected(reason);
        }
    }

    /** ROOM|max|started|name:colour:score:connected,name:colour:score:connected,... */
    private Room parseRoom(String[] p) {
        Room room = new Room();
        room.max = Integer.parseInt(p[1]);
        room.started = p[2].equals("1");
        if (p.length > 3 && !p[3].isEmpty()) {
            for (String entry : p[3].split(",")) {
                String[] f = entry.split(":");
                if (f.length < 4) continue;
                PlayerInfo info = new PlayerInfo();
                info.name = f[0];
                info.color = Integer.parseInt(f[1]);
                info.score = Integer.parseInt(f[2]);
                info.connected = f[3].equals("1");
                room.players.add(info);
            }
        }
        return room;
    }

    // ------------------------------------------------------------------
    // main: create the window
    // ------------------------------------------------------------------
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Online Quiz Battle");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

            // 1100 x 700 if the screen is big enough, otherwise a smaller window with the same shape
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            double fit = Math.min(1.0, Math.min((screen.width - 60) / (double) Ui.W, (screen.height - 120) / (double) Ui.H));
            QuizClient client = new QuizClient();
            client.setPreferredSize(new Dimension((int) (Ui.W * fit), (int) (Ui.H * fit)));

            frame.setContentPane(client);
            frame.setMinimumSize(new Dimension(640, 420));
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            client.requestFocusInWindow();
        });
    }
}
