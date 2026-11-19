import java.awt.Component;
import java.awt.Font;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/** Read-only help; opening this dialog never changes the room or player choices. */
final class HowToPlay {
    private HowToPlay() { }

    static void show(Component parent) {
        JTextArea guide = new JTextArea(
                "GET INTO THE ARENA\n\n"
                + "The arena is created by the SERVER computer: start_server.bat asks for the number "
                + "of players, questions and seconds per question.\n\n"
                + "Join: on your own computer start start_client.bat (same Wi-Fi / LAN as the server). "
                + "The game finds the arena by itself. If nothing is found, type the server's IP address "
                + "(it is printed in the server window) and click CONNECT. "
                + "Pick a free colour, enter your name and click JOIN ARENA.\n\n"
                + "The game starts when all seats fill. The first player who joined can also click "
                + "START NOW with at least two players.\n\n"
                + "ANSWERING\n\n"
                + "Click an answer, or press A, B, C, D (or 1, 2, 3, 4). "
                + "Your first answer is final. Other players see that you answered, "
                + "but your choice stays hidden until the reveal.\n\n"
                + "Click 50 / 50 to remove two wrong options. You can use it once per game.\n\n"
                + "SCORING\n\n"
                + "A correct answer earns 100 points, plus up to 50 for speed. "
                + "Each preceding correct answer in your current streak adds 10 more "
                + "(up to 50). Wrong or missed answers earn 0 and reset your streak.\n\n"
                + "After the final question, see the podium and click BACK TO MENU. "
                + "The server opens a fresh arena for the next game.",
                18, 46);
        guide.setEditable(false);
        guide.setLineWrap(true);
        guide.setWrapStyleWord(true);
        guide.setFont(Ui.font(Font.PLAIN, 16));
        guide.setCaretPosition(0);
        guide.getAccessibleContext().setAccessibleName("Quiz Battle playing guide");
        JScrollPane scroll = new JScrollPane(guide);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        JOptionPane.showMessageDialog(parent, scroll, "How to play - Quiz Battle",
                JOptionPane.PLAIN_MESSAGE);
    }
}
