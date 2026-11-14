import java.awt.*;
import java.awt.geom.*;

/**
 * WelcomeScreen - the first page of the game.
 *
 *   HOST A GAME : choose how many players (2-4) and how many questions, then create the arena.
 *                 Your computer becomes the server (a QuizServer runs inside this window).
 *   JOIN A GAME : the program searches the network for a host with UDP (Discovery), connects
 *                 with TCP, and shows which colours are already taken.
 *
 * In both cases you type a name and pick a colour. A colour that another player already took
 * is crossed out - and the SERVER makes the final decision, so two players can never get the same colour.
 */
public class WelcomeScreen extends Screen {

    static final int HOST = 0, JOIN = 1;                                     // the two tabs
    static final int SEARCHING = 0, CONNECTING = 1, CONNECTED = 2, NOT_FOUND = 3;   // state of the join tab

    private final QuizClient app;
    private final long born = System.currentTimeMillis();     // for the intro animation

    // ---- what the player has chosen ----
    int mode = HOST;
    int players = 2;               // seats in the room (host only)
    int questions = 10;            // questions per game (host only)
    int myColor = 0;               // -1 = nothing chosen

    // ---- the join tab ----
    int joinState = SEARCHING;
    String serverIp = "";
    boolean[] taken = new boolean[QuizServer.NUMBER_OF_COLOURS];   // colours other players already use
    QuizClient.Room room = new QuizClient.Room();

    boolean busy = false;          // waiting for the server's answer
    String error = null;
    long errorTime = 0;

    // ---- widgets ----
    Ui.Button helpButton = new Ui.Button(880, 18, 200, 38, "HOW TO PLAY", Ui.COLORS[1]);
    static final int[] PLAYER_CHOICES = { 2, 3, 4 };
    static final int[] QUESTION_CHOICES = { 5, 10, 15 };
    Ui.Button hostTab = new Ui.Button(325, 172, 220, 52, "HOST A GAME", Ui.COLORS[1]);
    Ui.Button joinTab = new Ui.Button(555, 172, 220, 52, "JOIN A GAME", Ui.COLORS[4]);
    Ui.Button[] playerButtons = new Ui.Button[3];
    Ui.Button[] questionButtons = new Ui.Button[3];
    Ui.Button connectButton = new Ui.Button(690, 342, 160, 46, "CONNECT", Ui.COLORS[1]);
    Ui.Button mainButton = new Ui.Button(375, 590, 350, 58, "CREATE ARENA", Ui.COLORS[2]);
    Ui.TextBox nameBox = new Ui.TextBox(420, 402, 300, 46, 12, "[A-Za-z0-9_ ]", "Your name");
    Ui.TextBox ipBox = new Ui.TextBox(420, 342, 250, 46, 15, "[0-9.]", "Host IP address");

    static final double SWATCH_Y = 502;

    WelcomeScreen(QuizClient app) {
        this.app = app;
        for (int i = 0; i < 3; i++) {
            playerButtons[i] = new Ui.Button(420 + i * 100, 282, 84, 46, "" + PLAYER_CHOICES[i], Ui.COLORS[1]);
            questionButtons[i] = new Ui.Button(420 + i * 100, 342, 84, 46, "" + QUESTION_CHOICES[i], Ui.COLORS[4]);
            playerButtons[i].fontSize = 24;
            questionButtons[i].fontSize = 24;
        }
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

        hostTab.enabled = (mode == HOST);
        joinTab.enabled = (mode == JOIN);
        hostTab.paint(g, now);
        joinTab.paint(g, now);

        Ui.glass(g, 150, 245, 800, 425, 30, null);

        if (mode == HOST) {
            label(g, "PLAYERS", 305);
            label(g, "QUESTIONS", 365);
            for (int i = 0; i < 3; i++) {
                playerButtons[i].enabled = (players == PLAYER_CHOICES[i]);
                questionButtons[i].enabled = (questions == QUESTION_CHOICES[i]);
                playerButtons[i].paint(g, now);
                questionButtons[i].paint(g, now);
            }
            Ui.textLeft(g, "seats in the arena", 740, 305, Ui.font(Font.PLAIN, 17), Ui.DIM);
            Ui.textLeft(g, "per game", 740, 365, Ui.font(Font.PLAIN, 17), Ui.DIM);
        } else {
            label(g, "GAME", 305);
            drawJoinStatus(g, now);
        }

        label(g, "YOUR NAME", 425);
        nameBox.paint(g, now);

        label(g, "YOUR COLOUR", SWATCH_Y);
        for (int i = 0; i < QuizServer.NUMBER_OF_COLOURS; i++) drawSwatch(g, i, now);

        // error message (fades out after a few seconds)
        if (error != null) {
            double fade = 1 - Ui.clamp((now - errorTime - 4000) / 1000.0);
            if (fade <= 0) error = null;
            else Ui.text(g, error, Ui.W / 2.0, 568, Ui.font(Font.BOLD, 16), Ui.alpha(Ui.BAD, (int) (255 * fade)));
        }

        // the big green button
        if (mode == HOST) {
            mainButton.label = busy ? "STARTING..." : "CREATE ARENA";
            mainButton.enabled = !busy && myColor >= 0;
        } else {
            mainButton.label = busy ? "JOINING..." : "JOIN ARENA";
            mainButton.enabled = !busy && joinState == CONNECTED && myColor >= 0
                    && !room.started && room.players.size() < room.max;
        }
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

    /** The "GAME" row of the join tab: searching... / connected / type the IP. */
    private void drawJoinStatus(Graphics2D g, long now) {
        double cy = 305;
        Font f = Ui.font(Font.BOLD, 18);
        switch (joinState) {
            case SEARCHING:
            case CONNECTING:
                g.setStroke(new BasicStroke(2f));
                g.setColor(Ui.alpha(Color.WHITE, 40));
                g.draw(Ui.circle(436, cy, 13));
                g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(Ui.COLORS[1]);
                g.draw(new Arc2D.Double(423, cy - 13, 26, 26, -(now / 3 % 360), 90, Arc2D.OPEN));   // a thin spinner
                String dots = "...".substring(0, (int) (now / 400 % 4));
                Ui.textLeft(g, joinState == SEARCHING ? "Searching your network for a game" + dots
                                                      : "Connecting to " + serverIp + dots, 468, cy, f, Ui.TEXT);
                break;
            case CONNECTED:
                double pulse = (now / 1600.0) % 1.0;                                 // a ring that expands from the green dot
                g.setStroke(new BasicStroke(1.5f));
                g.setColor(Ui.alpha(Ui.GOOD, (int) (150 * (1 - pulse))));
                g.draw(Ui.circle(436, cy, 6 + pulse * 12));
                g.setColor(Ui.GOOD);
                g.fill(Ui.circle(436, cy, 6));
                String info = room.started ? "Game already running - you cannot join"
                        : "Connected to " + serverIp + "  -  " + room.players.size() + " of " + room.max + " seats taken";
                Ui.textLeft(g, info, 468, cy, f, Ui.TEXT);
                // the players who are already sitting in the room
                for (int i = 0; i < room.players.size(); i++) {
                    QuizClient.PlayerInfo p = room.players.get(i);
                    double px = 436 + i * 130;
                    g.setColor(Ui.COLORS[p.color]);
                    g.fill(Ui.circle(px, 365, 8));
                    Ui.textLeft(g, p.name, px + 18, 365, Ui.font(Font.PLAIN, 16), Ui.DIM);
                }
                break;
            default:   // NOT_FOUND
                Ui.textLeft(g, "No game found automatically - type the host's IP address:", 420, cy, f, Ui.TEXT);
                ipBox.paint(g, now);
                connectButton.paint(g, now);
        }
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
        hostTab.hover = hostTab.contains(x, y);
        joinTab.hover = joinTab.contains(x, y);
        mainButton.hover = mainButton.contains(x, y);
        connectButton.hover = connectButton.contains(x, y);
        for (int i = 0; i < 3; i++) {
            playerButtons[i].hover = playerButtons[i].contains(x, y);
            questionButtons[i].hover = questionButtons[i].contains(x, y);
        }
    }

    @Override
    void mousePressed(double x, double y) {
        if (helpButton.contains(x, y)) { HowToPlay.show(app); return; }
        if (hostTab.contains(x, y)) { setMode(HOST); return; }
        if (joinTab.contains(x, y)) { setMode(JOIN); return; }
        if (busy) return;

        boolean ipVisible = (mode == JOIN && joinState == NOT_FOUND);
        nameBox.focused = nameBox.contains(x, y);
        ipBox.focused = ipVisible && ipBox.contains(x, y);

        if (mode == HOST) {
            for (int i = 0; i < 3; i++) {
                if (playerButtons[i].contains(x, y)) players = PLAYER_CHOICES[i];
                if (questionButtons[i].contains(x, y)) questions = QUESTION_CHOICES[i];
            }
        }
        for (int i = 0; i < QuizServer.NUMBER_OF_COLOURS; i++) {
            if (Math.hypot(x - swatchX(i), y - SWATCH_Y) <= 32) pickColour(i);
        }
        if (ipVisible && connectButton.contains(x, y)) connectManually();
        if (mainButton.enabled && mainButton.contains(x, y)) doMainAction();
    }

    @Override
    void keyTyped(char c) {
        if (c == '\n' || c == '\r') {
            if (mode == JOIN && joinState == NOT_FOUND && ipBox.focused) connectManually();
            else if (mainButton.enabled) doMainAction();
        } else if (c == '\t') {
            boolean ipVisible = (mode == JOIN && joinState == NOT_FOUND);
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
    private void setMode(int newMode) {
        if (mode == newMode || busy) return;
        mode = newMode;
        error = null;
        resetRoom();
        app.closeConnection();
        if (mode == JOIN) {
            joinState = SEARCHING;
            app.searchForServer();          // UDP broadcast: "is anybody hosting a quiz?"
        }
    }

    private void pickColour(int i) {
        if (taken[i]) showError("The " + Ui.COLOR_NAMES[i].toLowerCase() + " colour is already taken.");
        else myColor = i;
    }

    private void connectManually() {
        String ip = ipBox.value();
        if (ip.isEmpty()) { showError("Type the host's IP address first."); return; }
        serverIp = ip;
        joinState = CONNECTING;
        app.connectTo(ip);
    }

    private void doMainAction() {
        String name = nameBox.value();
        if (name.isEmpty()) name = "Player";
        busy = true;
        error = null;
        if (mode == HOST) app.hostGame(name, myColor, players, questions);
        else app.joinRoom(name, myColor);
    }

    private void resetRoom() {
        room = new QuizClient.Room();
        taken = new boolean[QuizServer.NUMBER_OF_COLOURS];
    }

    void showError(String message) {
        error = message;
        errorTime = System.currentTimeMillis();
    }

    /** Called when the player comes back to this page after a game. */
    void reset() {
        mode = HOST;
        busy = false;
        error = null;
        resetRoom();
    }

    // ------------------------------------------------------------------
    // Messages from the network (QuizClient calls these)
    // ------------------------------------------------------------------

    /** The UDP search is over: ip is the server's address, or null if nobody answered. */
    void onSearchResult(String ip) {
        if (mode != JOIN || joinState != SEARCHING) return;
        if (ip == null) {
            joinState = NOT_FOUND;
            nameBox.focused = false;
            ipBox.focused = true;
        } else {
            serverIp = ip;
            joinState = CONNECTING;
            app.connectTo(ip);
        }
    }

    void onConnected() {
        if (mode == JOIN) joinState = CONNECTED;
    }

    /** The server tells us who sits in the room: update the "taken" colours live. */
    void onRoom(QuizClient.Room newRoom) {
        room = newRoom;
        taken = new boolean[QuizServer.NUMBER_OF_COLOURS];
        for (QuizClient.PlayerInfo p : room.players) {
            if (p.color >= 0 && p.color < taken.length) taken[p.color] = true;
        }
        if (mode == JOIN && myColor >= 0 && taken[myColor]) {            // somebody just took "my" colour
            showError("Somebody just took the " + Ui.COLOR_NAMES[myColor].toLowerCase() + " colour - pick another one.");
            myColor = -1;
            for (int i = 0; i < taken.length && myColor < 0; i++) if (!taken[i]) myColor = i;
        }
        if (mode == JOIN && myColor < 0) {                               // choose the first free colour for the player
            for (int i = 0; i < taken.length && myColor < 0; i++) if (!taken[i]) myColor = i;
        }
    }

    void onJoinFailed(String reason) {
        busy = false;
        showError(reason);
    }

    void onDisconnected(String reason) {
        busy = false;
        showError(reason);
        if (mode == JOIN) {
            joinState = NOT_FOUND;
            ipBox.focused = true;
            nameBox.focused = false;
            resetRoom();
        }
    }
}
