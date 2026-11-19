import java.awt.*;
import java.awt.geom.*;

/**
 * WelcomeScreen - the first page of the game. Players can only JOIN here: the arena itself
 * (number of players, number of questions) is created by the server (start_server.bat).
 *
 * What the page does:
 *   1. It searches the network for the server with a UDP broadcast (Discovery) - again and again,
 *      so it is fine to open the game BEFORE the server is running. The arena is found as soon as it opens.
 *   2. When the server answers, it connects with TCP and shows who already sits in the arena.
 *   3. The player types a name and picks a colour. A colour that another player already took is crossed out -
 *      and the SERVER makes the final decision, so two players can never get the same colour.
 *   4. If the search finds nothing (some networks block it), the player can type the server's IP address.
 */
public class WelcomeScreen extends Screen {

    static final int SEARCHING = 0, CONNECTING = 1, CONNECTED = 2;       // where we are on the way to the arena

    private final QuizClient app;
    private final long born = System.currentTimeMillis();     // for the intro animation

    // ---- what the player has chosen ----
    int myColor = 0;               // -1 = nothing chosen
    boolean pickedColour = false;  // did the player click a colour (or only get the default one)?

    // ---- the connection to the server ----
    int joinState = SEARCHING;
    String serverIp = "";
    boolean[] taken = new boolean[QuizServer.NUMBER_OF_COLOURS];   // colours other players already use
    QuizClient.Room room = new QuizClient.Room();

    boolean busy = false;          // waiting for the server's answer to our JOIN
    String error = null;
    long errorTime = 0;

    // ---- widgets ----
    Ui.Button helpButton = new Ui.Button(880, 18, 200, 38, "HOW TO PLAY", Ui.COLORS[1]);   // opens the playing guide (HowToPlay.java)
    Ui.Button connectButton = new Ui.Button(690, 272, 160, 46, "CONNECT", Ui.COLORS[1]);
    Ui.Button mainButton = new Ui.Button(375, 590, 350, 58, "JOIN ARENA", Ui.COLORS[2]);
    Ui.TextBox nameBox = new Ui.TextBox(420, 357, 300, 46, 12, "[A-Za-z0-9_ ]", "Your name");
    Ui.TextBox ipBox = new Ui.TextBox(420, 272, 250, 46, 15, "[0-9.]", "or type the server's IP");

    static final double SWATCH_Y = 485;

    WelcomeScreen(QuizClient app) {
        this.app = app;
        mainButton.fontSize = 19;
        nameBox.focused = true;
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------
    @Override
    void paint(Graphics2D g, long now) {
        Ui.background(g, now);
        drawTitle(g, now);
        helpButton.paint(g, now);

        Ui.glass(g, 150, 195, 800, 465, 30, null);

        label(g, "ARENA", 240);
        drawStatus(g, now);

        label(g, "YOUR NAME", 380);
        nameBox.paint(g, now);

        label(g, "YOUR COLOUR", SWATCH_Y);
        for (int i = 0; i < QuizServer.NUMBER_OF_COLOURS; i++) drawSwatch(g, i, now);

        // error message (fades out after a few seconds)
        if (error != null) {
            double fade = 1 - Ui.clamp((now - errorTime - 4000) / 1000.0);
            if (fade <= 0) error = null;
            else Ui.text(g, error, Ui.W / 2.0, 568, Ui.font(Font.BOLD, 16), Ui.alpha(Ui.BAD, (int) (255 * fade)));
        }

        // the big green button: only usable when we are connected to an arena that still has a free seat
        mainButton.label = busy ? "JOINING..." : "JOIN ARENA";
        mainButton.enabled = !busy && joinState == CONNECTED && myColor >= 0
                && !room.started && room.players.size() < room.max;
        mainButton.paint(g, now);
    }

    /**
     * "QUIZ BATTLE": the letters fade in one after another, then a thin line in the six player colours
     * with a small light that travels along it.
     */
    private void drawTitle(Graphics2D g, long now) {
        String title = "QUIZ BATTLE";
        Font f = Ui.font(Font.BOLD, 58);
        double spacing = 13;
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        double x = Ui.W / 2.0 - Ui.spacedWidth(g, title, f, spacing) / 2.0;
        for (int i = 0; i < title.length(); i++) {
            String letter = title.substring(i, i + 1);
            double p = Ui.clamp((now - born - 150 - i * 70) / 600.0);              // each letter starts a little later
            double rise = (1 - Ui.easeOut(p)) * 16;
            Ui.textLeft(g, letter, x, 66 + rise, f, Ui.alpha(Ui.TEXT, (int) (255 * p)));
            x += fm.charWidth(title.charAt(i)) + spacing;
        }

        // the colour line under the title
        double lineW = 340, lineX = Ui.W / 2.0 - lineW / 2, lineY = 108;
        double lineP = Ui.easeOut(Ui.clamp((now - born - 700) / 900.0));          // it grows out of the middle
        double w = lineW * lineP;
        float[] fractions = new float[Ui.COLORS.length];
        for (int i = 0; i < fractions.length; i++) fractions[i] = i / (float) (fractions.length - 1);
        g.setPaint(new LinearGradientPaint((float) lineX, 0f, (float) (lineX + lineW), 0f, fractions, Ui.COLORS));
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(Ui.W / 2.0 - w / 2, lineY, Ui.W / 2.0 + w / 2, lineY));
        if (lineP >= 1) {
            double travel = (now / 2200.0) % 1.0;                                  // a small white light runs along the line
            Ui.glow(g, lineX + lineW * travel, lineY, 13, Color.WHITE, 200);
        }

        Ui.spaced(g, "FAST FINGERS  /  BIG BRAINS  /  ONE ARENA", Ui.W / 2.0, 138,
                Ui.font(Font.PLAIN, 14), Ui.alpha(Ui.DIM, (int) (255 * lineP)), 3);
    }

    private void label(Graphics2D g, String text, double cy) {
        Ui.spacedLeft(g, text, 190, cy, Ui.font(Font.BOLD, 13), Ui.DIM, 2.5);
    }

    /** The "ARENA" rows: searching / connecting / connected (with the players already inside). */
    private void drawStatus(Graphics2D g, long now) {
        double cy = 240;
        Font f = Ui.font(Font.BOLD, 18);
        if (joinState == CONNECTED) {
            double pulse = (now / 1600.0) % 1.0;                                 // a ring that expands from the green dot
            g.setStroke(new BasicStroke(1.5f));
            g.setColor(Ui.alpha(Ui.GOOD, (int) (150 * (1 - pulse))));
            g.draw(Ui.circle(436, cy, 6 + pulse * 12));
            g.setColor(Ui.GOOD);
            g.fill(Ui.circle(436, cy, 6));
            String info;
            if (room.started) info = "A game is already running - wait for the next arena";
            else if (room.players.size() >= room.max) info = "This arena is full";
            else info = "Arena found at " + serverIp + "  -  " + room.players.size() + " of " + room.max + " seats taken";
            Ui.textLeft(g, info, 468, cy, f, Ui.TEXT);

            // the players who are already sitting in the arena
            if (room.players.isEmpty()) {
                Ui.textLeft(g, "Nobody has joined yet - you can be the first.", 420, 295, Ui.font(Font.PLAIN, 16), Ui.DIM);
            }
            for (int i = 0; i < room.players.size(); i++) {
                QuizClient.PlayerInfo p = room.players.get(i);
                double px = 436 + i * 130;
                g.setColor(Ui.COLORS[p.color]);
                g.fill(Ui.circle(px, 295, 8));
                Ui.textLeft(g, p.name, px + 18, 295, Ui.font(Font.PLAIN, 16), Ui.DIM);
            }
            return;
        }

        // not connected yet: a thin spinner and the manual IP row
        g.setStroke(new BasicStroke(2f));
        g.setColor(Ui.alpha(Color.WHITE, 40));
        g.draw(Ui.circle(436, cy, 13));
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Ui.COLORS[1]);
        g.draw(new Arc2D.Double(423, cy - 13, 26, 26, -(now / 3 % 360), 90, Arc2D.OPEN));
        String dots = "...".substring(0, (int) (now / 400 % 4));
        Ui.textLeft(g, joinState == SEARCHING ? "Waiting for the server to open an arena" + dots
                                              : "Connecting to " + serverIp + dots, 468, cy, f, Ui.TEXT);
        ipBox.paint(g, now);
        connectButton.paint(g, now);
        Ui.textLeft(g, "Start start_server.bat on the host computer. This page finds the arena by itself.",
                420, 336, Ui.font(Font.PLAIN, 13), Ui.DIM);
    }

    private double swatchX(int i) {
        return 450 + i * 74;
    }

    /** One colour choice: a flat disc. The chosen one gets a thin ring around it, a taken one is dimmed and crossed out. */
    private void drawSwatch(Graphics2D g, int i, long now) {
        boolean isTaken = taken[i];
        boolean selected = (i == myColor);
        double cx = swatchX(i), cy = SWATCH_Y;

        Color c = Ui.COLORS[i];
        if (isTaken) c = Ui.mix(c, new Color(34, 38, 62), 0.82);
        if (selected) {
            Ui.glow(g, cx, cy, 44 + 4 * Math.sin(now / 400.0), c, 90);             // a slowly breathing glow
            g.setStroke(new BasicStroke(2f));
            g.setColor(Ui.alpha(Color.WHITE, 230));
            g.draw(Ui.circle(cx, cy, 30));
        }
        g.setPaint(new GradientPaint(0f, (float) (cy - 22), Ui.lighter(c, 0.18), 0f, (float) (cy + 22), Ui.darker(c, 0.82)));
        g.fill(Ui.circle(cx, cy, 22));
        if (isTaken) {                                                              // crossed out
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Ui.alpha(Ui.BAD, 210));
            g.draw(new Line2D.Double(cx - 13, cy + 13, cx + 13, cy - 13));
            Ui.spaced(g, "TAKEN", cx, cy + 40, Ui.font(Font.BOLD, 10), Ui.DIM, 1.5);
        } else {
            Ui.spaced(g, Ui.COLOR_NAMES[i].toUpperCase(), cx, cy + 40, Ui.font(Font.BOLD, 10),
                    selected ? Ui.TEXT : Ui.alpha(Ui.DIM, 170), 1.5);
        }
    }

    // ------------------------------------------------------------------
    // Mouse and keyboard
    // ------------------------------------------------------------------
    @Override
    void mouseMoved(double x, double y) {
        helpButton.hover = helpButton.contains(x, y);
        mainButton.hover = mainButton.contains(x, y);
        connectButton.hover = connectButton.contains(x, y);
    }

    @Override
    void mousePressed(double x, double y) {
        if (helpButton.contains(x, y)) { HowToPlay.show(app); return; }     // the guide only reads - it changes nothing
        if (busy) return;

        boolean ipVisible = (joinState != CONNECTED);
        nameBox.focused = nameBox.contains(x, y);
        ipBox.focused = ipVisible && ipBox.contains(x, y);

        for (int i = 0; i < QuizServer.NUMBER_OF_COLOURS; i++) {
            if (Math.hypot(x - swatchX(i), y - SWATCH_Y) <= 32) pickColour(i);
        }
        if (ipVisible && connectButton.contains(x, y)) connectManually();
        if (mainButton.enabled && mainButton.contains(x, y)) join();
    }

    @Override
    void keyTyped(char c) {
        boolean ipVisible = (joinState != CONNECTED);
        if (c == '\n' || c == '\r') {
            if (ipVisible && ipBox.focused) connectManually();
            else if (mainButton.enabled) join();
        } else if (c == '\t') {
            if (ipVisible) { ipBox.focused = nameBox.focused; nameBox.focused = !ipBox.focused; }
        } else if (nameBox.focused) {
            nameBox.type(c);
        } else if (ipBox.focused) {
            ipBox.type(c);
        }
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------
    private void pickColour(int i) {
        if (taken[i]) showError("The " + Ui.COLOR_NAMES[i].toLowerCase() + " colour is already taken.");
        else { myColor = i; pickedColour = true; }
    }

    /** The player typed the server's IP address instead of waiting for the automatic search. */
    private void connectManually() {
        String ip = ipBox.value();
        if (ip.isEmpty()) { showError("Type the server's IP address first."); return; }
        serverIp = ip;
        joinState = CONNECTING;
        app.connectTo(ip);
    }

    /** The big button: ask the server for a seat. The answer comes back as JOINED or JOINFAIL. */
    private void join() {
        String name = nameBox.value();
        if (name.isEmpty()) name = "Player";
        busy = true;
        error = null;
        app.joinRoom(name, myColor);
    }

    private void resetRoom() {
        room = new QuizClient.Room();
        taken = new boolean[QuizServer.NUMBER_OF_COLOURS];
    }

    void showError(String message) {
        error = message;
        errorTime = System.currentTimeMillis();
    }

    /** Called at the start of the program and whenever the player comes back to this page: start searching again. */
    void reset() {
        busy = false;
        error = null;
        resetRoom();
        joinState = SEARCHING;
        app.closeConnection();
        app.searchForServer();               // UDP broadcast: "is a quiz server running?"
    }

    // ------------------------------------------------------------------
    // Messages from the network (QuizClient calls these)
    // ------------------------------------------------------------------

    /** The UDP search is over: ip is the server's address, or null if nobody answered. */
    void onSearchResult(String ip) {
        if (joinState != SEARCHING) return;
        if (ip == null) {
            app.searchForServer();           // nobody yet - keep looking, the server may be started any moment
        } else {
            serverIp = ip;
            joinState = CONNECTING;
            app.connectTo(ip);
        }
    }

    void onConnected() {
        joinState = CONNECTED;
    }

    /** The server tells us who sits in the arena: update the "taken" colours live. */
    void onRoom(QuizClient.Room newRoom) {
        room = newRoom;
        taken = new boolean[QuizServer.NUMBER_OF_COLOURS];
        for (QuizClient.PlayerInfo p : room.players) {
            if (p.color >= 0 && p.color < taken.length) taken[p.color] = true;
        }
        if (myColor >= 0 && taken[myColor]) {                            // somebody took "my" colour
            if (pickedColour) showError("Somebody just took the " + Ui.COLOR_NAMES[myColor].toLowerCase() + " colour - pick another one.");
            myColor = -1;
            pickedColour = false;
        }
        if (myColor < 0) {                                               // choose the first free colour for the player
            for (int i = 0; i < taken.length && myColor < 0; i++) if (!taken[i]) myColor = i;
        }
    }

    void onJoinFailed(String reason) {
        busy = false;
        showError(reason);
    }

    /** The connection was lost (or could not be made): go back to searching. */
    void onDisconnected(String reason) {
        busy = false;
        showError(reason);
        resetRoom();
        joinState = SEARCHING;
        app.searchForServer();
    }
}
