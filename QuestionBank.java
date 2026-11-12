import java.io.*;
import java.util.*;

/**
 * QuestionBank - loads the quiz questions from a plain text file (questions.txt).
 *
 * Every line of the file is ONE question. The fields are separated by the | character:
 *
 *   question | option A | option B | option C | option D | correct letter | fun fact
 *
 * Empty lines and lines starting with # are ignored.
 * Because the questions live in a text file, the team can add or change questions
 * without touching any Java code.
 */
public class QuestionBank {

    /** One quiz question (just a small data holder). */
    public static class Question {
        String text;        // the question itself
        String[] options;   // the four answers: A, B, C, D
        String answer;      // the correct letter: "A", "B", "C" or "D"
        String note;        // short "did you know" text shown after the question

        Question(String text, String[] options, String answer, String note) {
            this.text = text;
            this.options = options;
            this.answer = answer;
            this.note = note;
        }
    }

    /** Reads the file and returns every valid question in it. */
    public static List<Question> load(String fileName) throws IOException {
        List<Question> list = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(fileName))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;   // skip blanks and comments

                String[] p = line.split("\\|");
                boolean valid = p.length >= 7 && p[5].trim().toUpperCase().matches("[A-D]");
                if (!valid) {
                    System.out.println("Skipping a badly formatted line in " + fileName + ": " + line);
                    continue;
                }

                String[] options = { p[1].trim(), p[2].trim(), p[3].trim(), p[4].trim() };
                list.add(new Question(p[0].trim(), options, p[5].trim().toUpperCase(), p[6].trim()));
            }
        }
        return list;
    }
}
