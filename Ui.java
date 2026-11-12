import java.awt.*;
import java.awt.geom.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Ui - small drawing helpers shared by all screens: colours, text, buttons, animation maths.
 *
 * The look of the game: a dark "night broadcast" style - glass panels, thin lines, soft glows
 * and widely spaced capital letters. Colours are used sparingly: only the players' colours,
 * green for right, red for wrong and a muted gold for the winner.
 *
 * The whole game is drawn on a "virtual canvas" of 1100 x 700 units. QuizClient scales that canvas
 * to fit the window, so every screen can use fixed coordinates and still look right at any size.
 */
public class Ui {

    static final int W = 1100;
    static final int H = 700;

    // The six player colours. A player picks one, and nobody else may pick the same one.
    static final Color[] COLORS = {
        new Color(255, 99, 110),   // 0 coral
        new Color(72, 158, 255),   // 1 azure
        new Color(52, 211, 153),   // 2 emerald
        new Color(251, 191, 68),   // 3 amber
        new Color(167, 139, 250),  // 4 violet
        new Color(34, 211, 238)    // 5 cyan
    };
    static final String[] COLOR_NAMES = { "Coral", "Azure", "Emerald", "Amber", "Violet", "Cyan" };

    static final Color GOLD = new Color(236, 196, 110);
    static final Color GOOD = new Color(62, 220, 130);     // correct answer
    static final Color BAD = new Color(248, 80, 100);      // wrong answer
    static final Color TEXT = new Color(232, 237, 248);
    static final Color DIM = new Color(134, 146, 176);

    static final String FAMILY = pickFamily();

    /** Uses the nice Windows font when it exists, otherwise Java's default sans-serif font. */
    private static String pickFamily() {
        for (String name : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
            if (name.equals("Segoe UI")) return name;
        }
        return Font.SANS_SERIF;
    }

    static Font font(int style, int size) {
        return new Font(FAMILY, style, size);
    }

    // ------------------------------------------------------------------
    // Animation maths. p is progress from 0.0 (start) to 1.0 (finished).
    // ------------------------------------------------------------------

    static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /** Starts fast, ends slowly - the smooth "glide into place" movement. */
    static double easeOut(double p) {
        return 1 - Math.pow(1 - p, 3);
    }

    /** Slow - fast - slow. */
    static double easeInOut(double p) {
        return p < 0.5 ? 4 * p * p * p : 1 - Math.pow(-2 * p + 2, 3) / 2;
    }

    // ------------------------------------------------------------------
    // Colours
    // ------------------------------------------------------------------

    static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
    }

    /** p = 0 gives colour a, p = 1 gives colour b. */
    static Color mix(Color a, Color b, double p) {
        p = clamp(p);
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * p),
                         (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * p),
                         (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * p));
    }

    static Color lighter(Color c, double p) {
        return mix(c, Color.WHITE, p);
    }

    static Color darker(Color c, double factor) {
        return new Color((int) (c.getRed() * factor), (int) (c.getGreen() * factor), (int) (c.getBlue() * factor));
    }

    // ------------------------------------------------------------------
    // Drawing helpers
    // ------------------------------------------------------------------

    /** Turns on smooth edges for shapes and text. */
    static void smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    static RoundRectangle2D rr(double x, double y, double w, double h, double arc) {
        return new RoundRectangle2D.Double(x, y, w, h, arc, arc);
    }

    static Ellipse2D circle(double cx, double cy, double r) {
        return new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r);
    }

    /** A soft round light: strongest in the middle, invisible at the edge. */
    static void glow(Graphics2D g, double cx, double cy, double r, Color c, int strength) {
        g.setPaint(new RadialGradientPaint(new Point2D.Double(cx, cy), (float) r, new float[] { 0f, 1f },
                new Color[] { alpha(c, strength), alpha(c, 0) }));
        g.fill(circle(cx, cy, r));
    }

    /** A dark "glass" panel with a thin light edge. edge = null gives the default grey-white edge. */
    static void glass(Graphics2D g, double x, double y, double w, double h, double arc, Color edge) {
        RoundRectangle2D shape = rr(x, y, w, h, arc);
        g.setPaint(new GradientPaint(0f, (float) y, new Color(26, 32, 66, 218), 0f, (float) (y + h), new Color(10, 13, 32, 228)));
        g.fill(shape);
        g.setStroke(new BasicStroke(1.4f));
        g.setColor(edge != null ? edge : new Color(255, 255, 255, 36));
        g.draw(shape);
        g.setColor(new Color(255, 255, 255, 30));                                    // a faint highlight on the top edge
        g.draw(new Line2D.Double(x + arc * 0.6, y + 1.2, x + w - arc * 0.6, y + 1.2));
    }

    /** The background: deep blue-black, two slowly drifting soft lights, a fine dot grid and a dark vignette. */
    static void background(Graphics2D g, long now) {
        g.setPaint(new GradientPaint(0, 0, new Color(6, 8, 20), 0, H, new Color(11, 15, 36)));
        g.fillRect(0, 0, W, H);
        double t = now / 1000.0;
        glow(g, 260 + Math.sin(t * 0.15) * 130, 130 + Math.cos(t * 0.12) * 40, 440, new Color(70, 84, 210), 52);
        glow(g, 860 + Math.cos(t * 0.13) * 130, 590 + Math.sin(t * 0.10) * 40, 480, new Color(30, 150, 165), 38);
        g.setColor(new Color(255, 255, 255, 11));
        for (int x = 30; x < W; x += 40) {
            for (int y = 30; y < H; y += 40) g.fillRect(x, y, 2, 2);
        }
        g.setPaint(new RadialGradientPaint(new Point2D.Double(W / 2.0, H / 2.0), 780f, new float[] { 0.5f, 1f },
                new Color[] { new Color(0, 0, 0, 0), new Color(0, 0, 0, 150) }));
        g.fillRect(0, 0, W, H);
    }

    // ------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------

    /** Draws text centred on (cx, cy). */
    static void text(Graphics2D g, String s, double cx, double cy, Font f, Color c) {
        g.setFont(f);
        g.setColor(c);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, (float) (cx - fm.stringWidth(s) / 2.0), (float) (cy + (fm.getAscent() - fm.getDescent()) / 2.0));
    }

    /** Draws text starting at x (left aligned), vertically centred on cy. */
    static void textLeft(Graphics2D g, String s, double x, double cy, Font f, Color c) {
        g.setFont(f);
        g.setColor(c);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, (float) x, (float) (cy + (fm.getAscent() - fm.getDescent()) / 2.0));
    }

    /** Text with a soft shadow - readable on any background. */
    static void outlined(Graphics2D g, String s, double cx, double cy, Font f, Color c) {
        text(g, s, cx, cy + 2, f, new Color(0, 0, 0, 100));
        text(g, s, cx, cy, f, c);
    }

    /** Width of a text drawn with extra space between the letters. */
    static double spacedWidth(Graphics2D g, String s, Font f, double spacing) {
        FontMetrics fm = g.getFontMetrics(f);
        double w = 0;
        for (int i = 0; i < s.length(); i++) w += fm.charWidth(s.charAt(i)) + spacing;
        return w - spacing;
    }

    /** Letter-spaced text, centred on (cx, cy). Capital letters with spacing give the "premium" look. */
    static void spaced(Graphics2D g, String s, double cx, double cy, Font f, Color c, double spacing) {
        spacedLeft(g, s, cx - spacedWidth(g, s, f, spacing) / 2, cy, f, c, spacing);
    }

    /** Letter-spaced text, starting at x. */
    static void spacedLeft(Graphics2D g, String s, double x, double cy, Font f, Color c, double spacing) {
        g.setFont(f);
        g.setColor(c);
        FontMetrics fm = g.getFontMetrics();
        float y = (float) (cy + (fm.getAscent() - fm.getDescent()) / 2.0);
        for (int i = 0; i < s.length(); i++) {
            g.drawString(String.valueOf(s.charAt(i)), (float) x, y);
            x += fm.charWidth(s.charAt(i)) + spacing;
        }
    }

    /** Splits a long text into lines that fit into maxW. */
    static List<String> wrapLines(Graphics2D g, String s, double maxW, Font f) {
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        List<String> lines = new ArrayList<>();
        String line = "";
        for (String word : s.split(" ")) {
            String test = line.isEmpty() ? word : line + " " + word;
            if (fm.stringWidth(test) > maxW && !line.isEmpty()) {
                lines.add(line);
                line = word;
            } else {
                line = test;
            }
        }
        if (!line.isEmpty()) lines.add(line);
        return lines;
    }

    /** Picks the biggest of the given font sizes whose wrapped text needs at most maxLines lines. */
    static Font fitFont(Graphics2D g, String s, double maxW, int maxLines, int style, int... sizes) {
        Font f = font(style, sizes[sizes.length - 1]);
        for (int size : sizes) {
            f = font(style, size);
            if (wrapLines(g, s, maxW, f).size() <= maxLines) return f;
        }
        return f;
    }

    /** Draws wrapped text; (x, cy) is the centre (or the left edge when center is false) of the block. */
    static void textBlock(Graphics2D g, String s, double x, double cy, double maxW, double lineHeight,
                          Font f, Color c, boolean center) {
        List<String> lines = wrapLines(g, s, maxW, f);
        g.setFont(f);
        g.setColor(c);
        FontMetrics fm = g.getFontMetrics();
        double top = cy - (lines.size() - 1) * lineHeight / 2.0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            double px = center ? x - fm.stringWidth(line) / 2.0 : x;
            g.drawString(line, (float) px, (float) (top + i * lineHeight + (fm.getAscent() - fm.getDescent()) / 2.0));
        }
    }

    // ------------------------------------------------------------------
    // Widgets (we draw our own, so they match the game's look and can be animated)
    // ------------------------------------------------------------------

    /**
     * A button. enabled = true: filled with its colour. enabled = false: an outline only
     * (it is still just a picture - the screen decides what a click does).
     */
    static class Button {
        double x, y, w, h;
        String label;
        Color color;
        boolean enabled = true;
        boolean hover = false;
        int fontSize = 17;

        Button(double x, double y, double w, double h, String label, Color color) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.label = label;
            this.color = color;
        }

        boolean contains(double px, double py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }

        void paint(Graphics2D g, long now) {
            RoundRectangle2D shape = rr(x, y, w, h, 16);
            double cx = x + w / 2, cy = y + h / 2;
            Font f = font(Font.BOLD, fontSize);
            if (enabled) {
                if (hover) glow(g, cx, cy, Math.max(w, h) * 0.75, color, 70);          // a soft light appears under the mouse
                g.setPaint(new GradientPaint(0f, (float) y, lighter(color, hover ? 0.20 : 0.10), 0f, (float) (y + h), darker(color, 0.74)));
                g.fill(shape);
                g.setStroke(new BasicStroke(1.2f));
                g.setColor(alpha(Color.WHITE, hover ? 120 : 60));
                g.draw(shape);
                spaced(g, label, cx, cy, f, Color.WHITE, 2);
            } else {
                g.setColor(alpha(Color.WHITE, hover ? 24 : 9));
                g.fill(shape);
                g.setStroke(new BasicStroke(1.2f));
                g.setColor(alpha(Color.WHITE, hover ? 90 : 42));
                g.draw(shape);
                spaced(g, label, cx, cy, f, hover ? TEXT : DIM, 2);
            }
        }
    }

    /** A one-line text input box. Characters arrive from QuizClient's keyboard listener. */
    static class TextBox {
        double x, y, w, h;
        int maxLength;
        String allowed;            // regular expression for ONE allowed character
        String placeholder;
        StringBuilder text = new StringBuilder();
        boolean focused = false;

        TextBox(double x, double y, double w, double h, int maxLength, String allowed, String placeholder) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.maxLength = maxLength;
            this.allowed = allowed;
            this.placeholder = placeholder;
        }

        boolean contains(double px, double py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }

        void type(char c) {
            if (c == '\b') {
                if (text.length() > 0) text.setLength(text.length() - 1);
            } else if (c >= 32 && c != 127 && text.length() < maxLength && String.valueOf(c).matches(allowed)) {
                text.append(c);
            }
        }

        String value() {
            return text.toString().trim();
        }

        void paint(Graphics2D g, long now) {
            card(g, x, y, w, h, 14, new Color(6, 8, 22, 235),
                    focused ? alpha(COLORS[1], 230) : new Color(255, 255, 255, 50), focused ? 2f : 1.4f);
            Font f = font(Font.BOLD, 20);
            if (text.length() == 0 && !focused) {
                textLeft(g, placeholder, x + 16, y + h / 2, f, new Color(100, 110, 146));
                return;
            }
            textLeft(g, text.toString(), x + 16, y + h / 2, f, TEXT);
            if (focused && (now / 500) % 2 == 0) {                    // blinking cursor
                g.setFont(f);
                double cursorX = x + 16 + g.getFontMetrics().stringWidth(text.toString()) + 2;
                g.setColor(TEXT);
                g.fill(new Rectangle2D.Double(cursorX, y + 11, 2, h - 22));
            }
        }
    }

    /** A rounded card with an optional border. */
    static void card(Graphics2D g, double x, double y, double w, double h, double arc,
                     Color fill, Color border, float borderWidth) {
        RoundRectangle2D shape = rr(x, y, w, h, arc);
        g.setColor(fill);
        g.fill(shape);
        if (border != null) {
            g.setStroke(new BasicStroke(borderWidth));
            g.setColor(border);
            g.draw(shape);
        }
    }
}
