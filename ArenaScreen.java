import java.awt.*;
import java.awt.geom.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * ArenaScreen - the stadium where the whole game happens.
 *
 * It has five phases, driven by the messages the server sends:
 *   LOBBY      players sit down one by one          (ROOM)
 *   COUNTDOWN  3 - 2 - 1 - GO                       (STARTING)
 *   QUESTION   the question slides in, everybody picks an answer in secret   (QUESTION, LOCKED, HINT)
 *   REVEAL     time is up: every face-down answer card turns over, the points rise,
 *              the scores count up                                           (REVEAL, ROOM)
 *   PODIUM     the final ranking                                             (GAMEOVER)
 *
 * Everything is drawn from scratch 60 times per second. An animation is just a number that
 * goes from 0 to 1 while time passes:   p = (now - startTime) / duration
 *
 * Look: calm and clean. Every player is a ring with their initial in their own colour;
 * an answer is a card that lies face down on the table until the reveal.
 */
public class ArenaScreen extends Screen {

    static final int LOBBY = 0, COUNTDOWN = 1, QUESTION = 2, REVEAL = 3, PODIUM = 4;

    // The stadium is an ellipse (centre + radii) on the 1100 x 700 canvas
    static final double CX = 550, CY = 392, RX = 470, RY = 192;
    // Where the seats are: the angle on the ellipse, measured from the bottom (0 = bottom centre)
    static final double[][] SEAT_ANGLES = { {}, {}, { -34, 34 }, { -50, 0, 50 }, { -60, -20, 20, 60 } };
    // The four answer cards
    static final double OPT_W = 320, OPT_H = 62, OPT_GAP = 14, OPT_Y = 292;
    // Podium: x position of 1st, 2nd, 3rd, 4th place (depends on the number of players) and the column heights
    static final double[][] PODIUM_X = { {}, { 550 }, { 640, 440 }, { 550, 360, 740 }, { 550, 360, 740, 930 } };
    static final double[] PODIUM_HEIGHT = { 210, 160, 120, 85 };

    /** One player sitting in the stadium. */
    static class Seat {
        String name;
        int color;
        int score = 0;
        double shownScore = 0;      // the score on screen: it counts up smoothly towards 'score'
        boolean connected = true;
        double x, y;                // current position (glides towards the seat position)
        long joinTime;              // for the fade-in when the player sits down
        boolean locked;             // has answered the current question
        long lockTime;
        String letter;              // revealed answer: "A".."D" or "-" (no answer)
        int points;                 // points won in the last question
        int streak;                 // correct answers in a row
        long flipAt;                // when this player's answer card turns over
    }

    private final QuizClient app;
    final String myName;
    final int myColor;

    int phase = LOBBY;
    int maxPlayers = 2;
    List<Seat> seats = new ArrayList<>();

    // the current question
    int qNumber, qTotal, qSeconds;
    String qText = "";
    String[] options = new String[4];
    long qStart;
    String myAnswer = null;
    boolean[] eliminated = new boolean[4];       // answers crossed out by 50/50
    boolean lifelineUsed = false;
    String correct = null;
    String note = "";
    long revealStart;
    long cheerUntil = 0;                         // the crowd's light wave is brighter until this time

    long countdownStart;
    long podiumStart;
    String[] ranking = new String[0];

    String toast = null;
    long toastTime;

    // mouse hover and buttons
    int hoverOption = -1;
    Ui.Button startButton = new Ui.Button(CX - 130, 386, 260, 54, "START NOW", Ui.COLORS[2]);
    Ui.Button lifelineButton = new Ui.Button(CX - 80, 236, 160, 38, "50 / 50", Ui.COLORS[4]);
    Ui.Button menuButton = new Ui.Button(400, 636, 300, 50, "BACK TO MENU", Ui.COLORS[1]);

    private Color accent = Ui.COLORS[4];         // slowly changes between violet and azure
    private final String hostAddress;

    // gold dust that floats up on the podium screen
    private static final int DUST = 90;
    private double[] dustX, dustY, dustSpeed, dustSize, dustPhase;
    private long lastFrame = 0;

    ArenaScreen(QuizClient app, String myName, int myColor) {
        this.app = app;
        this.myName = myName;
        this.myColor = myColor;
        // when we are the host, show our address so friends can type it if auto-discovery fails
        String address = "";
        if (app.server != null && !QuizServer.localAddresses().isEmpty()) address = QuizServer.localAddresses().get(0);
        hostAddress = address;
        lifelineButton.fontSize = 14;
        startButton.fontSize = 18;
        menuButton.fontSize = 16;
    }

    // ------------------------------------------------------------------
    // Messages from the server
    // ------------------------------------------------------------------

    /** The server's ROOM message: who sits where, their colours and scores. */
    void roomChanged(QuizClient.Room room) {
        long now = System.currentTimeMillis();
        maxPlayers = Math.max(2, Math.min(4, room.max));
        List<Seat> updated = new ArrayList<>();              // same order as the server's list
        for (QuizClient.PlayerInfo info : room.players) {
            Seat s = findSeat(info.name);
            if (s == null) {                                   // a new player sits down
                s = new Seat();
                s.name = info.name;
                s.joinTime = now;
                s.shownScore = info.score;
                s.x = seatX(updated.size());
                s.y = seatY(updated.size());
            }
            s.color = info.color;
            s.score = info.score;
            s.connected = info.connected;
            updated.add(s);
        }
        seats = updated;
    }

    void onMessage(String[] p) {
        long now = System.currentTimeMillis();
        switch (p[0]) {
            case "STARTING":
                phase = COUNTDOWN;
                countdownStart = now;
                break;

            case "QUESTION":
                phase = QUESTION;
                qNumber = Integer.parseInt(p[1]);
                qTotal = Integer.parseInt(p[2]);
                qSeconds = Integer.parseInt(p[3]);
                qText = p[4];
                options = new String[] { p[5], p[6], p[7], p[8] };
                qStart = now;
                myAnswer = null;
                correct = null;
                note = "";
                eliminated = new boolean[4];
                for (Seat s : seats) {
                    s.locked = false;
                    s.letter = null;
                    s.points = 0;
                }
                break;

            case "LOCKED": {                                   // somebody answered (we do not know WHAT they chose)
                Seat s = findSeat(p[1]);
                if (s != null && !s.locked) {
                    s.locked = true;
                    s.lockTime = now;
                }
                break;
            }

            case "HINT":                                       // our 50/50: two wrong answers are crossed out
                eliminated["ABCD".indexOf(p[1])] = true;
                eliminated["ABCD".indexOf(p[2])] = true;
                lifelineUsed = true;
                break;

            case "REVEAL": {                                   // REVEAL|correct|note|name:letter:points:streak,...
                phase = REVEAL;
                revealStart = now;
                correct = p[1];
                note = p[2];
                int order = 0;
                boolean anyCorrect = false;
                for (String entry : p[3].split(",")) {
                    String[] e = entry.split(":");
                    Seat s = (e.length >= 4) ? findSeat(e[0]) : null;
                    if (s == null) continue;
                    s.letter = e[1];
                    s.points = Integer.parseInt(e[2]);
                    s.streak = Integer.parseInt(e[3]);
                    s.flipAt = now + 700 + order * 450;        // the cards turn over one after the other
                    order++;
                    if (s.points > 0) anyCorrect = true;
                }
                if (anyCorrect) cheerUntil = now + 3500 + order * 450;
                break;
            }

            case "INFO":
                toast = p[1];
                toastTime = now;
                break;

            case "GAMEOVER":
                phase = PODIUM;
                podiumStart = now;
                ranking = p[1].split(",");
                startDust();
                break;
        }
    }

    private Seat findSeat(String name) {
        for (Seat s : seats) if (s.name.equals(name)) return s;
        return null;
    }

    private boolean isLeader() {
        return !seats.isEmpty() && seats.get(0).name.equals(myName);
    }

    // ------------------------------------------------------------------
    // Positions
    // ------------------------------------------------------------------
    private double seatX(int index) {
        double angle = Math.toRadians(SEAT_ANGLES[maxPlayers][Math.min(index, maxPlayers - 1)]);
        return CX + RX * Math.sin(angle);
    }

    private double seatY(int index) {
        double angle = Math.toRadians(SEAT_ANGLES[maxPlayers][Math.min(index, maxPlayers - 1)]);
        return CY + RY * Math.cos(angle);
    }

    private Rectangle2D optionRect(int i) {
        int col = i % 2, row = i / 2;
        return new Rectangle2D.Double(CX - OPT_W - OPT_GAP / 2 + col * (OPT_W + OPT_GAP),
                OPT_Y + row * (OPT_H + OPT_GAP), OPT_W, OPT_H);
    }

    private Ellipse2D ellipse(double rx, double ry) {
        return new Ellipse2D.Double(CX - rx, CY - ry, 2 * rx, 2 * ry);
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------
    @Override
    void paint(Graphics2D g, long now) {
        Ui.background(g, now);
        double t = now / 1000.0;
        accent = Ui.mix(Ui.COLORS[4], Ui.COLORS[1], 0.5 + 0.5 * Math.sin(t * 0.25));   // slowly drifting accent colour

        if (phase == PODIUM) {
            paintPodium(g, now);
            drawToast(g, now);
            return;
        }
        drawStadium(g, now);
        drawQuestionCard(g, now);
        drawCenter(g, now);
        drawSeats(g, now);
        drawToast(g, now);
    }

    /** The oval stadium seen from above: seating ring with a travelling wave of light, a glowing rim and the floor. */
    private void drawStadium(Graphics2D g, long now) {
        double t = now / 1000.0;
        boolean cheering = now < cheerUntil;

        Ui.glow(g, CX, CY, 600, accent, 34);

        // the outer wall
        g.setPaint(new GradientPaint(0f, (float) (CY - RY - 66), new Color(17, 21, 48), 0f, (float) (CY + RY + 66), new Color(7, 9, 24)));
        g.fill(ellipse(RX + 66, RY + 66));
        g.setStroke(new BasicStroke(1.5f));
        g.setColor(Ui.alpha(Color.WHITE, 40));
        g.draw(ellipse(RX + 66, RY + 66));

        // the seating: three rings of small blocks. A slow wave of light runs around them,
        // and it flashes gold faster when somebody has answered correctly.
        g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int band = 0; band < 3; band++) {
            double rx = RX + 18 + band * 16, ry = RY + 18 + band * 16;
            int count = 96 + band * 18;
            for (int i = 0; i < count; i++) {
                double a = Math.PI * 2 * i / count;
                double light = 0.5 + 0.5 * Math.sin(t * 1.2 - a * 2 + band);
                if (cheering) light = Math.max(light, 0.5 + 0.5 * Math.sin(t * 9 - a * 3));
                double x = CX + rx * Math.cos(a), y = CY + ry * Math.sin(a);
                double dx = -rx * Math.sin(a), dy = ry * Math.cos(a), len = Math.hypot(dx, dy);
                dx = dx / len * 3.2;                                   // a short piece along the ring
                dy = dy / len * 3.2;
                g.setColor(Ui.mix(new Color(40, 48, 98), cheering ? Ui.GOLD : accent, light * 0.8));
                g.draw(new Line2D.Double(x - dx, y - dy, x + dx, y + dy));
            }
        }

        // the floor: a dark disc with a soft light in the middle
        Graphics2D f = (Graphics2D) g.create();
        f.translate(CX, CY);
        f.scale(1, RY / RX);
        f.setPaint(new RadialGradientPaint(new Point2D.Double(0, 0), (float) RX, new float[] { 0f, 1f },
                new Color[] { new Color(30, 42, 92), new Color(9, 13, 34) }));
        f.fill(Ui.circle(0, 0, RX));
        f.dispose();

        // thin lines on the floor, two slow spotlights, and a green ripple when answers were right
        Shape oldClip = g.getClip();
        g.clip(ellipse(RX, RY));
        g.setStroke(new BasicStroke(1.2f));
        g.setColor(Ui.alpha(Color.WHITE, 22));
        g.draw(ellipse(RX * 0.9, RY * 0.88));
        g.draw(ellipse(RX * 0.5, RY * 0.5));
        for (int k = 0; k < 2; k++) {
            double sx = CX + Math.sin(t * 0.3 + k * Math.PI) * RX * 0.8;
            Path2D.Double beam = new Path2D.Double();
            beam.moveTo(sx - 8, CY - RY);
            beam.lineTo(sx + 8, CY - RY);
            beam.lineTo(sx + 95, CY + RY);
            beam.lineTo(sx - 95, CY + RY);
            beam.closePath();
            g.setPaint(new GradientPaint(0f, (float) (CY - RY), Ui.alpha(Color.WHITE, 24), 0f, (float) (CY + RY), Ui.alpha(Color.WHITE, 0)));
            g.fill(beam);
        }
        if (phase == REVEAL && cheerUntil > revealStart) {
            double p = Ui.clamp((now - revealStart) / 1600.0);
            g.setStroke(new BasicStroke(3f));
            g.setColor(Ui.alpha(Ui.GOOD, (int) (110 * (1 - p))));
            g.draw(ellipse(RX * p, RY * p));
        }
        g.setClip(oldClip);

        // the glowing rim of the floor
        g.setStroke(new BasicStroke(16f));
        g.setColor(Ui.alpha(accent, 22));
        g.draw(ellipse(RX, RY));
        g.setStroke(new BasicStroke(7f));
        g.setColor(Ui.alpha(accent, 50));
        g.draw(ellipse(RX, RY));
        g.setStroke(new BasicStroke(2f));
        g.setColor(Ui.alpha(accent, 235));
        g.draw(ellipse(RX, RY));
    }

    /** The glass card at the top: the question (or the lobby message), plus the timer. */
    private void drawQuestionCard(Graphics2D g, long now) {
        double slide = 0;
        float fade = 1f;
        if (phase == QUESTION || phase == REVEAL) {                   // a new question glides in from above
            double p = Ui.easeOut(Ui.clamp((now - qStart) / 600.0));
            slide = -(1 - p) * 40;
            fade = (float) p;
        }
        Graphics2D c = (Graphics2D) g.create();
        c.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, fade));

        double x = 80, y = 14 + slide, w = 940, h = 136;
        Ui.glass(c, x, y, w, h, 28, Ui.alpha(accent, 120));
        c.setColor(accent);
        c.fill(Ui.rr(x + 18, y + 26, 4, h - 52, 4));                  // a short accent bar on the left

        String label, body;
        int dots = (int) (now / 400 % 4);
        switch (phase) {
            case LOBBY:
                label = "ARENA LOBBY";
                body = "Waiting for players" + "...".substring(0, dots);
                break;
            case COUNTDOWN:
                label = "GET READY";
                body = "The battle is about to begin";
                break;
            default:
                label = String.format("QUESTION %02d / %02d", qNumber, qTotal);
                body = qText;
        }
        Ui.spacedLeft(c, label, x + 40, y + 28, Ui.font(Font.BOLD, 13), Ui.lighter(accent, 0.3), 3);

        if (phase == REVEAL) {
            // the question in smaller letters, and the "did you know" fact below it
            Font small = Ui.fitFont(c, body, 780, 2, Font.BOLD, 19, 17, 15);
            Ui.textBlock(c, body, 500, y + 62, 780, small.getSize() + 4, small, Ui.TEXT, true);
            String fact = "Did you know?  " + note;
            Font factFont = Ui.fitFont(c, fact, 780, 2, Font.PLAIN, 16, 14, 13);
            Ui.textBlock(c, fact, 500, y + 108, 780, factFont.getSize() + 3, factFont, Ui.GOLD, true);
        } else if (phase == LOBBY) {
            Ui.text(c, body, 500, y + 66, Ui.font(Font.BOLD, 30), Ui.TEXT);
            String hint = app.server != null
                    ? "Friends on your network find you automatically" + (hostAddress.isEmpty() ? "" : "   -   or they type  " + hostAddress)
                    : "The game starts when all " + maxPlayers + " seats are taken.";
            Ui.text(c, hint, 500, y + 106, Ui.font(Font.PLAIN, 16), Ui.DIM);
        } else {
            Font f = Ui.fitFont(c, body, 780, 2, Font.BOLD, 28, 25, 22, 19);
            Ui.textBlock(c, body, 500, y + 76, 780, f.getSize() + 7, f, Ui.TEXT, true);
        }

        // right side: the countdown (question), the right answer (reveal) or a quiet spinner (lobby)
        double rcx = 955, rcy = y + 66;
        if (phase == QUESTION) {
            double remaining = Math.max(0, qSeconds - (now - qStart) / 1000.0);
            double fraction = remaining / qSeconds;
            Color col = fraction > 0.5 ? Ui.GOOD : (fraction > 0.25 ? Ui.GOLD : Ui.BAD);
            boolean last = remaining <= 5;
            Color numberColor = last ? Ui.mix(Ui.TEXT, Ui.BAD, 0.5 + 0.5 * Math.sin(now / 120.0)) : Ui.TEXT;   // the last seconds blink red
            Ui.text(c, "" + (int) Math.ceil(remaining), rcx, rcy - 4, Ui.font(Font.BOLD, 46), numberColor);
            Ui.spaced(c, "SECONDS", rcx, rcy + 30, Ui.font(Font.BOLD, 10), Ui.DIM, 2);
            // a thin bar along the bottom edge that empties
            c.setColor(Ui.alpha(Color.WHITE, 26));
            c.fill(Ui.rr(x + 40, y + h - 16, w - 80, 4, 4));
            c.setColor(col);
            c.fill(Ui.rr(x + 40, y + h - 16, Math.max(4, (w - 80) * fraction), 4, 4));
        } else if (phase == REVEAL && correct != null) {
            double pop = Ui.easeOut(Ui.clamp((now - revealStart) / 400.0));
            Graphics2D r = (Graphics2D) c.create();
            r.translate(rcx, rcy - 6);
            r.scale(pop, pop);
            r.setColor(Ui.alpha(Ui.GOOD, 38));
            r.fill(Ui.rr(-27, -27, 54, 54, 16));
            r.setStroke(new BasicStroke(2.2f));
            r.setColor(Ui.GOOD);
            r.draw(Ui.rr(-27, -27, 54, 54, 16));
            Ui.text(r, correct, 0, 0, Ui.font(Font.BOLD, 32), Ui.GOOD);
            r.dispose();
            Ui.spaced(c, "CORRECT", rcx, rcy + 36, Ui.font(Font.BOLD, 10), Ui.GOOD, 2);
        } else {
            c.setStroke(new BasicStroke(2f));
            c.setColor(Ui.alpha(Color.WHITE, 32));
            c.draw(Ui.circle(rcx, rcy, 24));
            c.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            c.setColor(Ui.alpha(accent, 240));
            c.draw(new Arc2D.Double(rcx - 24, rcy - 24, 48, 48, -(now / 3 % 360), 90, Arc2D.OPEN));
        }
        c.dispose();
    }

    /** What is in the middle of the floor depends on the phase. */
    private void drawCenter(Graphics2D g, long now) {
        if (phase == LOBBY) {
            Ui.spaced(g, "WAITING FOR PLAYERS", CX, 300, Ui.font(Font.BOLD, 30), Ui.TEXT, 5);
            Ui.spaced(g, seats.size() + " OF " + maxPlayers + " SEATS TAKEN", CX, 340, Ui.font(Font.BOLD, 14), Ui.DIM, 3);
            boolean canStart = isLeader() && seats.size() >= 2;
            if (canStart) {
                startButton.paint(g, now);
                Ui.text(g, "or wait - it starts by itself when all seats are taken", CX, 466, Ui.font(Font.PLAIN, 14), Ui.DIM);
            } else {
                String msg = seats.size() < 2 ? "At least 2 players are needed." : "The host can start the game at any moment.";
                Ui.text(g, msg, CX, 400, Ui.font(Font.PLAIN, 16), Ui.DIM);
            }
        } else if (phase == COUNTDOWN) {
            drawCountdown(g, now);
        } else {
            for (int i = 0; i < 4; i++) drawOption(g, i, now);
            if (phase == QUESTION) {
                lifelineButton.enabled = !lifelineUsed && myAnswer == null;
                double p = Ui.clamp((now - qStart - 900) / 400.0);
                if (p > 0) lifelineButton.paint(g, now);
            }
        }
    }

    /** 3 - 2 - 1 - GO: a big thin number with a ring that expands and fades every second. */
    private void drawCountdown(Graphics2D g, long now) {
        double t = (now - countdownStart) / 1000.0;
        String s;
        double fraction;
        if (t < 3) {
            s = "" + (3 - (int) t);
            fraction = t - (int) t;
        } else {
            s = "GO";
            fraction = Ui.clamp((t - 3) / 0.8);
        }
        float visible = (float) (1 - Ui.easeOut(Ui.clamp((fraction - 0.6) / 0.4)));      // opaque, then fades at the end
        double scale = 1.2 - 0.2 * Ui.easeOut(Ui.clamp(fraction * 3));                  // starts a little big and settles

        g.setStroke(new BasicStroke(2f));
        g.setColor(Ui.alpha(accent, (int) (170 * (1 - fraction))));
        g.draw(Ui.circle(CX, CY - 5, 80 + fraction * 230));

        Graphics2D c = (Graphics2D) g.create();
        c.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, visible))));
        Ui.glow(c, CX, CY - 5, 190, accent, 70);
        c.translate(CX, CY - 5);
        c.scale(scale, scale);
        Ui.text(c, s, 0, 0, Ui.font(Font.PLAIN, 170), s.equals("GO") ? Ui.GOOD : Ui.TEXT);
        c.dispose();
    }

    /** One of the four answer cards (the player's own choice gets their colour). */
    private void drawOption(Graphics2D g, int i, long now) {
        String letter = "ABCD".substring(i, i + 1);
        Rectangle2D r = optionRect(i);
        double p = Ui.clamp((now - qStart - 300 - i * 110) / 450.0);   // the cards glide in one after the other
        if (p <= 0) return;

        boolean mine = letter.equals(myAnswer);
        boolean crossedOut = eliminated[i];
        boolean isCorrect = (phase == REVEAL && letter.equals(correct));
        boolean fadedOut = (phase == REVEAL && !isCorrect);
        boolean hover = (phase == QUESTION && hoverOption == i && myAnswer == null && !crossedOut);

        float alpha = (float) (Ui.easeOut(p) * (crossedOut ? 0.35 : (fadedOut ? 0.45 : 1.0)));
        Graphics2D c = (Graphics2D) g.create();
        c.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        c.translate(0, (1 - Ui.easeOut(p)) * 22);

        Color base = new Color(14, 18, 42);
        Color fill = new Color(14, 18, 42, 225);
        Color border = Ui.alpha(Color.WHITE, 46);
        float borderWidth = 1.4f;
        if (hover) { fill = new Color(28, 34, 70, 232); border = Ui.alpha(Color.WHITE, 150); }
        if (mine) { fill = Ui.mix(base, Ui.COLORS[myColor], 0.22); border = Ui.COLORS[myColor]; borderWidth = 2.4f; }
        if (isCorrect) {
            fill = Ui.mix(base, Ui.GOOD, 0.22);
            border = Ui.GOOD;
            borderWidth = 2.6f;
            Ui.glow(c, r.getCenterX(), r.getCenterY(), 175, Ui.GOOD, (int) (34 + 24 * (0.5 + 0.5 * Math.sin(now / 300.0))));
        }
        Ui.card(c, r.getX(), r.getY(), r.getWidth(), r.getHeight(), 18, fill, border, borderWidth);

        // the letter in a small rounded square
        double bx = r.getX() + 16, by = r.getCenterY() - 18;
        boolean filled = isCorrect || mine;
        c.setColor(isCorrect ? Ui.GOOD : (mine ? Ui.COLORS[myColor] : Ui.alpha(Color.WHITE, 22)));
        c.fill(Ui.rr(bx, by, 36, 36, 11));
        Ui.text(c, letter, bx + 18, by + 18, Ui.font(Font.BOLD, 20), filled ? new Color(8, 10, 24) : Ui.TEXT);

        // the answer text
        double textW = OPT_W - 112;
        Font f = Ui.fitFont(c, options[i], textW, 2, Font.BOLD, 19, 17, 15, 13);
        Ui.textBlock(c, options[i], r.getX() + 66, r.getCenterY(), textW, f.getSize() + 4, f, Ui.TEXT, false);

        // a small tick on the right: this is the answer you locked in / the right answer
        if (isCorrect || (mine && phase == QUESTION)) {
            c.setColor(isCorrect ? Ui.GOOD : Ui.COLORS[myColor]);
            c.fill(Ui.circle(r.getMaxX() - 26, r.getCenterY(), 11));
            tick(c, r.getMaxX() - 26, r.getCenterY(), 11, new Color(8, 10, 24));
        }
        if (crossedOut) {                                          // 50/50 removed this answer: a thin line through it
            c.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            c.setColor(Ui.BAD);
            c.draw(new Line2D.Double(r.getX() + 62, r.getCenterY(), r.getMaxX() - 22, r.getCenterY()));
        }
        c.dispose();
    }

    /** A check mark. size = radius of the circle it sits in. */
    private void tick(Graphics2D g, double cx, double cy, double size, Color color) {
        g.setStroke(new BasicStroke((float) (size * 0.22), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(color);
        Path2D.Double path = new Path2D.Double();
        path.moveTo(cx - size * 0.42, cy + size * 0.02);
        path.lineTo(cx - size * 0.1, cy + size * 0.36);
        path.lineTo(cx + size * 0.46, cy - size * 0.34);
        g.draw(path);
    }

    // ------------------------------------------------------------------
    // The players
    // ------------------------------------------------------------------
    private void drawSeats(Graphics2D g, long now) {
        // empty seats (only while players are still arriving)
        if (phase == LOBBY || phase == COUNTDOWN) {
            for (int i = seats.size(); i < maxPlayers; i++) drawEmptySeat(g, seatX(i), seatY(i), now);
        }
        // glide every player to their seat
        for (int i = 0; i < seats.size(); i++) {
            Seat s = seats.get(i);
            s.x += (seatX(i) - s.x) * 0.18;
            s.y += (seatY(i) - s.y) * 0.18;
        }
        for (Seat s : seats) drawSeat(g, s, now);
        for (Seat s : seats) drawAnswerCard(g, s, now);            // the cards are always drawn on top
    }

    private void drawEmptySeat(Graphics2D g, double x, double y, long now) {
        float[] dash = { 6f, 7f };
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, dash, (float) (now / 60 % 13)));
        g.setColor(Ui.alpha(Color.WHITE, (int) (80 + 40 * Math.sin(now / 400.0))));
        g.draw(Ui.circle(x, y, 34));
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Ui.alpha(Color.WHITE, 90));
        g.draw(new Line2D.Double(x - 8, y, x + 8, y));                         // a thin plus sign
        g.draw(new Line2D.Double(x, y - 8, x, y + 8));
        Ui.spaced(g, "OPEN SEAT", x, y + 56, Ui.font(Font.BOLD, 10), Ui.DIM, 2.5);
    }

    private void drawSeat(Graphics2D g, Seat s, long now) {
        boolean me = s.name.equals(myName);
        double pop = Ui.easeOut(Ui.clamp((now - s.joinTime) / 500.0));        // fades in when the player sits down
        if (pop <= 0.02) return;
        Color color = s.connected ? Ui.COLORS[s.color] : new Color(100, 108, 132);

        // reaction once this player's card has turned over: green ripples for right, a red flash and a small shake for wrong
        boolean right = false, wrong = false;
        double shake = 0;
        long since = now - s.flipAt - 450;
        if (phase == REVEAL && s.letter != null && since > 0) {
            right = s.points > 0;
            wrong = !right && !s.letter.equals("-");
            if (wrong) shake = Math.sin(since / 35.0) * 4 * (1 - Ui.clamp(since / 600.0));
        }

        // the score counts up (but waits until this player's card has turned over)
        boolean hold = (phase == REVEAL && now < s.flipAt + 1600);
        if (!hold) {
            double diff = s.score - s.shownScore;
            if (Math.abs(diff) < 1) s.shownScore = s.score;
            else s.shownScore += Math.signum(diff) * Math.max(1, Math.abs(diff) * 0.07);
        }

        double cx = s.x + shake;
        double cy = s.y + Math.sin(now / 900.0 + s.name.length()) * 1.5;      // a very slow float
        double r = 34;

        // a pool of light on the floor under the player
        Graphics2D pool = (Graphics2D) g.create();
        pool.translate(cx, cy + 44);
        pool.scale(1, 0.22);
        pool.setPaint(new RadialGradientPaint(new Point2D.Double(0, 0), 80f, new float[] { 0f, 1f },
                new Color[] { Ui.alpha(color, (int) (90 * pop)), Ui.alpha(color, 0) }));
        pool.fill(Ui.circle(0, 0, 80));
        pool.dispose();

        // ripples (right) or a flash (wrong) around the avatar
        if (right) {
            for (int k = 0; k < 3; k++) {
                double pk = since / 1000.0 - k * 0.25;
                if (pk > 0 && pk < 1.2) {
                    g.setStroke(new BasicStroke(2.2f));
                    g.setColor(Ui.alpha(Ui.GOOD, (int) (150 * (1 - pk / 1.2))));
                    g.draw(Ui.circle(cx, cy, r + 4 + pk * 52));
                }
            }
        }
        if (wrong && since < 700) {
            g.setStroke(new BasicStroke(3f));
            g.setColor(Ui.alpha(Ui.BAD, (int) (210 * (1 - since / 700.0))));
            g.draw(Ui.circle(cx, cy, r + 7));
        }

        // the avatar: a ring with the first letter of the name. While the player thinks, a bright arc runs around the ring.
        boolean thinking = (phase == QUESTION && !s.locked && s.connected);
        drawAvatar(g, cx, cy, r, color, s.name.substring(0, 1).toUpperCase(), thinking, now, pop);

        // a small green tick on the avatar: this player has locked in an answer
        boolean showTick = s.locked && s.connected
                && (phase == QUESTION || (phase == REVEAL && (s.letter == null || now < s.flipAt)));
        if (showTick) {
            double tp = Ui.easeOut(Ui.clamp((now - s.lockTime) / 300.0));
            g.setColor(new Color(10, 13, 32));
            g.fill(Ui.circle(cx + 25, cy + 25, 13 * tp));
            g.setColor(Ui.GOOD);
            g.fill(Ui.circle(cx + 25, cy + 25, 10.5 * tp));
            if (tp > 0.6) tick(g, cx + 25, cy + 25, 10.5, new Color(8, 10, 24));
        }

        // the name plate: name (and a small tag), score below
        Graphics2D c = (Graphics2D) g.create();
        c.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) pop));
        Font nameFont = Ui.font(Font.BOLD, 15);
        c.setFont(nameFont);
        double nameWidth = c.getFontMetrics().stringWidth(s.name);
        String tag = me ? "YOU" : (!s.connected ? "LEFT" : null);
        double tagWidth = (tag == null) ? 0 : 44;
        double plateW = Math.max(112, nameWidth + 32 + tagWidth);
        double py = cy + 46;
        Ui.glass(c, cx - plateW / 2, py, plateW, 56, 16, me ? Ui.alpha(Ui.GOLD, 150) : null);
        double startX = cx - (nameWidth + tagWidth) / 2;
        Ui.textLeft(c, s.name, startX, py + 18, nameFont, me ? Ui.GOLD : Ui.TEXT);
        if (tag != null) {
            double tagX = startX + nameWidth + 8;
            Color tagColor = me ? Ui.GOLD : Ui.BAD;
            c.setColor(Ui.alpha(tagColor, 40));
            c.fill(Ui.rr(tagX, py + 10, 34, 16, 8));
            Ui.spaced(c, tag, tagX + 17, py + 18, Ui.font(Font.BOLD, 9), tagColor, 1.5);
        }
        double diff = Math.abs(s.score - s.shownScore);
        double scale = 1 + Math.min(0.18, diff / 400.0);                         // the number swells a little while it counts
        Graphics2D sc = (Graphics2D) c.create();
        sc.translate(cx, py + 39);
        sc.scale(scale, scale);
        Ui.text(sc, "" + (int) Math.round(s.shownScore), 0, 0, Ui.font(Font.BOLD, 24), Ui.lighter(color, 0.25));
        sc.dispose();
        c.dispose();
    }

    /** A player's avatar: a dark disc, a ring in the player's colour and the first letter of the name. */
    private void drawAvatar(Graphics2D g, double cx, double cy, double r, Color color, String letter,
                            boolean thinking, long now, double fade) {
        Graphics2D c = (Graphics2D) g.create();
        c.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Ui.clamp(fade)));
        c.setPaint(new RadialGradientPaint(new Point2D.Double(cx, cy - r * 0.3), (float) (r * 1.4), new float[] { 0f, 1f },
                new Color[] { Ui.mix(new Color(20, 26, 56), color, 0.30), new Color(9, 12, 30) }));
        c.fill(Ui.circle(cx, cy, r));
        if (thinking) {
            c.setStroke(new BasicStroke(3.5f));
            c.setColor(Ui.alpha(color, 70));
            c.draw(Ui.circle(cx, cy, r));
            c.setStroke(new BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            c.setColor(color);
            c.draw(new Arc2D.Double(cx - r, cy - r, 2 * r, 2 * r, -(now / 2 % 360), 100, Arc2D.OPEN));
        } else {
            c.setStroke(new BasicStroke(3.5f));
            c.setColor(color);
            c.draw(Ui.circle(cx, cy, r));
        }
        Ui.text(c, letter, cx, cy + 1, Ui.font(Font.BOLD, (int) (r * 0.95)), Ui.TEXT);
        c.dispose();
    }

    /**
     * The answer card above a player: it lies face down once the player has answered (nobody knows the letter),
     * and at the reveal it turns over. The points then rise above it.
     */
    private void drawAnswerCard(Graphics2D g, Seat s, long now) {
        if (!s.connected) return;
        double cx = s.x, cy = s.y - 94;
        Color color = Ui.COLORS[s.color];

        boolean turning = phase == REVEAL && s.letter != null && now >= s.flipAt;
        boolean faceDown = s.locked && (phase == QUESTION || (phase == REVEAL && !turning));

        if (turning) {
            double p = Ui.clamp((now - s.flipAt) / 450.0);
            double squeeze = Math.max(0.03, Math.abs(Math.cos(p * Math.PI)));    // 1 -> 0 -> 1 looks like a card turning
            drawCard(g, cx, cy, squeeze, p >= 0.5, s);

            // the points rise and fade
            double t = (now - s.flipAt - 450) / 1000.0;
            if (t > 0 && s.points > 0) {
                double rise = Ui.easeOut(Ui.clamp(t / 0.8)) * 30;
                float fade = (float) (1 - Ui.clamp((t - 2.0) / 0.7));
                Graphics2D f = (Graphics2D) g.create();
                f.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, fade)));
                Ui.outlined(f, "+" + s.points, cx, cy - 58 - rise, Ui.font(Font.BOLD, 26), Ui.GOLD);
                if (s.streak >= 2) Ui.spaced(f, "STREAK x" + s.streak, cx, cy - 36 - rise, Ui.font(Font.BOLD, 10), Ui.DIM, 2);
                f.dispose();
            }
        } else if (faceDown) {
            double pop = Ui.easeOut(Ui.clamp((now - s.lockTime) / 350.0));
            Graphics2D c = (Graphics2D) g.create();
            c.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) pop));
            drawCard(c, cx, cy + (1 - pop) * 14, 1.0, false, s);
            c.dispose();
        }
    }

    /** One answer card. front = false: the back (a "?" in the player's colour). squeeze < 1 while it turns. */
    private void drawCard(Graphics2D g, double cx, double cy, double squeeze, boolean front, Seat s) {
        Color color = Ui.COLORS[s.color];
        boolean none = "-".equals(s.letter);
        boolean right = s.points > 0;
        Graphics2D c = (Graphics2D) g.create();
        c.translate(cx, cy);
        c.scale(squeeze, 1);
        c.setPaint(new GradientPaint(0f, -33f, new Color(32, 40, 80), 0f, 33f, new Color(12, 16, 38)));
        c.fill(Ui.rr(-27, -33, 54, 66, 14));
        c.setStroke(new BasicStroke(2.2f));
        c.setColor(front ? (none ? Ui.alpha(Color.WHITE, 80) : (right ? Ui.GOOD : Ui.BAD)) : Ui.alpha(color, 210));
        c.draw(Ui.rr(-27, -33, 54, 66, 14));
        if (front) {
            Ui.text(c, none ? "-" : s.letter, 0, -3, Ui.font(Font.BOLD, 34), none ? Ui.DIM : Ui.TEXT);
            c.setColor(color);
            c.fill(Ui.rr(-12, 21, 24, 4, 4));                      // a small bar in the player's colour
        } else {
            Ui.text(c, "?", 0, 0, Ui.font(Font.BOLD, 26), Ui.alpha(color, 220));
        }
        c.dispose();
    }

    private void drawToast(Graphics2D g, long now) {
        if (toast == null) return;
        double age = now - toastTime;
        if (age > 3500) { toast = null; return; }
        double slide = Ui.easeOut(Ui.clamp(age / 300.0)) * Ui.clamp((3500 - age) / 300.0);
        Graphics2D c = (Graphics2D) g.create();
        c.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) slide));
        Ui.glass(c, CX - 230, 158 - (1 - slide) * 20, 460, 38, 19, Ui.alpha(Ui.GOLD, 140));
        Ui.text(c, toast, CX, 177 - (1 - slide) * 20, Ui.font(Font.BOLD, 15), Ui.TEXT);
        c.dispose();
    }

    // ------------------------------------------------------------------
    // The podium (game over)
    // ------------------------------------------------------------------
    private void startDust() {
        Random random = new Random();
        dustX = new double[DUST];
        dustY = new double[DUST];
        dustSpeed = new double[DUST];
        dustSize = new double[DUST];
        dustPhase = new double[DUST];
        for (int i = 0; i < DUST; i++) {
            dustX[i] = random.nextDouble() * Ui.W;
            dustY[i] = random.nextDouble() * Ui.H;
            dustSpeed[i] = 10 + random.nextDouble() * 28;
            dustSize[i] = 1.2 + random.nextDouble() * 2.2;
            dustPhase[i] = random.nextDouble() * 6.28;
        }
        lastFrame = System.currentTimeMillis();
    }

    private void paintPodium(Graphics2D g, long now) {
        double since = now - podiumStart;
        int n = Math.min(ranking.length, 4);
        if (n == 0) return;

        boolean tie = n >= 2 && findSeat(ranking[0]) != null && findSeat(ranking[1]) != null
                && findSeat(ranking[0]).score == findSeat(ranking[1]).score;

        drawDust(g, now, since);

        // title
        double tp = Ui.easeOut(Ui.clamp((since - 200) / 700.0));
        String title = tie ? "IT'S A DRAW" : ranking[0].toUpperCase() + " WINS";
        Ui.spaced(g, title, CX, 62 + (1 - tp) * 14, Ui.font(Font.BOLD, 42), Ui.alpha(Ui.GOLD, (int) (255 * tp)), 6);
        Ui.spaced(g, "FINAL RESULTS", CX, 106, Ui.font(Font.BOLD, 13), Ui.alpha(Ui.DIM, (int) (255 * tp)), 4);

        double floorY = 598;
        double[] xs = PODIUM_X[n];
        g.setStroke(new BasicStroke(1.4f));
        g.setColor(Ui.alpha(Color.WHITE, (int) (50 * tp)));
        g.draw(new Line2D.Double(180, floorY + 8, 920, floorY + 8));

        // the columns: the last place rises first, the winner last
        for (int rank = n - 1; rank >= 0; rank--) {
            Seat s = findSeat(ranking[rank]);
            if (s == null) continue;
            Color color = Ui.COLORS[s.color];
            int place = rank;                                                    // equal scores share the same place
            while (place > 0 && findSeat(ranking[place - 1]) != null && findSeat(ranking[place - 1]).score == s.score) place--;
            boolean winner = (place == 0 && !tie);
            double delay = 500 + (n - 1 - rank) * 650;
            double rise = Ui.clamp((since - delay) / 700.0);
            if (rise <= 0) continue;
            double blockH = PODIUM_HEIGHT[place] * Ui.easeOut(rise);
            double top = floorY - blockH, bx = xs[rank] - 75;

            if (winner) {                                                       // a soft spotlight falls on the winner
                Path2D.Double cone = new Path2D.Double();
                cone.moveTo(xs[rank] - 26, 130);
                cone.lineTo(xs[rank] + 26, 130);
                cone.lineTo(xs[rank] + 150, floorY);
                cone.lineTo(xs[rank] - 150, floorY);
                cone.closePath();
                g.setPaint(new GradientPaint(0f, 130f, Ui.alpha(Ui.GOLD, (int) (46 * rise)), 0f, (float) floorY, Ui.alpha(Ui.GOLD, 0)));
                g.fill(cone);
            }

            g.setPaint(new GradientPaint(0f, (float) top, Ui.alpha(color, 105), 0f, (float) floorY, Ui.alpha(color, 12)));
            g.fill(Ui.rr(bx, top, 150, blockH, 16));
            g.setStroke(new BasicStroke(1.4f));
            g.setColor(Ui.alpha(color, 120));
            g.draw(Ui.rr(bx, top, 150, blockH, 16));
            g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(color);
            g.draw(new Line2D.Double(bx + 14, top + 1.5, bx + 136, top + 1.5));      // a bright edge on top of the column

            if (PODIUM_HEIGHT[place] >= 130 && blockH > 110) {
                Ui.text(g, "" + (place + 1), xs[rank], top + 56, Ui.font(Font.PLAIN, 62), Ui.alpha(Color.WHITE, 225));
                Ui.spaced(g, s.score + " PTS", xs[rank], top + 100, Ui.font(Font.BOLD, 14), Ui.alpha(Color.WHITE, 220), 2);
            } else if (PODIUM_HEIGHT[place] < 130 && blockH > 66) {
                Ui.text(g, "" + (place + 1), xs[rank], top + 32, Ui.font(Font.PLAIN, 40), Ui.alpha(Color.WHITE, 225));
                Ui.spaced(g, s.score + " PTS", xs[rank], top + 62, Ui.font(Font.BOLD, 13), Ui.alpha(Color.WHITE, 220), 2);
            }

            // the player's avatar settles on top of the column
            double drop = Ui.clamp((since - delay - 450) / 600.0);
            if (drop <= 0) continue;
            double radius = winner ? 42 : 34;
            double y = top - radius - 14 - (1 - Ui.easeOut(drop)) * 50;
            if (winner) {
                Ui.glow(g, xs[rank], y, 110, Ui.GOLD, (int) (80 * drop));
                g.setStroke(new BasicStroke(2f));
                g.setColor(Ui.alpha(Ui.GOLD, (int) (230 * drop)));
                g.draw(Ui.circle(xs[rank], y, radius + 8));                       // a thin gold ring marks the winner
            }
            drawAvatar(g, xs[rank], y, radius, color, s.name.substring(0, 1).toUpperCase(), false, now, drop);
            Ui.text(g, s.name, xs[rank], y - radius - 22, Ui.font(Font.BOLD, 18),
                    Ui.alpha(s.name.equals(myName) ? Ui.GOLD : Ui.TEXT, (int) (255 * drop)));
            if (winner) Ui.spaced(g, "WINNER", xs[rank], y - radius - 44, Ui.font(Font.BOLD, 10), Ui.alpha(Ui.GOLD, (int) (255 * drop)), 4);
        }

        if (since > 1500) menuButton.paint(g, now);
    }

    /** Fine gold dust that drifts upwards. */
    private void drawDust(Graphics2D g, long now, double since) {
        if (dustX == null) return;
        double dt = Math.min(0.05, (now - lastFrame) / 1000.0);
        lastFrame = now;
        double fadeIn = Ui.clamp(since / 1500.0);
        for (int i = 0; i < DUST; i++) {
            dustY[i] -= dustSpeed[i] * dt;
            dustX[i] += Math.sin(now / 1500.0 + dustPhase[i]) * 8 * dt;
            if (dustY[i] < -10) dustY[i] = Ui.H + 10;                            // a speck that left the screen starts again at the bottom
            double twinkle = 0.35 + 0.65 * Math.abs(Math.sin(now / 700.0 + dustPhase[i]));
            g.setColor(Ui.alpha(i % 3 == 0 ? Color.WHITE : Ui.GOLD, (int) (190 * twinkle * fadeIn)));
            g.fill(Ui.circle(dustX[i], dustY[i], dustSize[i]));
        }
    }

    // ------------------------------------------------------------------
    // Mouse and keyboard
    // ------------------------------------------------------------------
    @Override
    void mouseMoved(double x, double y) {
        hoverOption = -1;
        for (int i = 0; i < 4; i++) {
            if (phase == QUESTION && optionRect(i).contains(x, y)) hoverOption = i;
        }
        startButton.hover = startButton.contains(x, y);
        lifelineButton.hover = lifelineButton.contains(x, y);
        menuButton.hover = menuButton.contains(x, y);
    }

    @Override
    void mousePressed(double x, double y) {
        if (phase == QUESTION) {
            for (int i = 0; i < 4; i++) {
                if (optionRect(i).contains(x, y)) chooseAnswer(i);
            }
            if (lifelineButton.enabled && lifelineButton.contains(x, y)) app.send("LIFELINE|" + qNumber);
        } else if (phase == LOBBY) {
            if (isLeader() && seats.size() >= 2 && startButton.contains(x, y)) app.send("START");
        } else if (phase == PODIUM) {
            if (menuButton.contains(x, y)) app.backToMenu();
        }
    }

    /** Keys A, B, C, D (or 1-4) answer too. */
    @Override
    void keyTyped(char c) {
        if (phase != QUESTION) return;
        int index = "ABCD".indexOf(Character.toUpperCase(c));
        if (index < 0) index = "1234".indexOf(c);
        if (index >= 0) chooseAnswer(index);
    }

    /** The player picks an answer. The first pick counts - it is sent to the server and locked. */
    private void chooseAnswer(int index) {
        if (phase != QUESTION || myAnswer != null || eliminated[index]) return;
        myAnswer = "ABCD".substring(index, index + 1);
        app.send("ANSWER|" + qNumber + "|" + myAnswer);
        Seat me = findSeat(myName);
        if (me != null && !me.locked) {                    // show the face-down card at once, without waiting for the server
            me.locked = true;
            me.lockTime = System.currentTimeMillis();
        }
    }
}
