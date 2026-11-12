import java.awt.Graphics2D;

/**
 * Screen - one page of the game (the welcome page, the stadium, ...).
 *
 * QuizClient shows exactly one Screen at a time. It calls paint() about 60 times per second
 * (that is what makes the animations smooth) and passes on the mouse and keyboard events.
 * All coordinates are "virtual" ones on a 1100 x 700 canvas (see Ui.java).
 */
public abstract class Screen {

    /** Draws the whole screen. 'now' is the current time in milliseconds (used for animations). */
    abstract void paint(Graphics2D g, long now);

    void mouseMoved(double x, double y) { }

    void mousePressed(double x, double y) { }

    void keyTyped(char c) { }
}
