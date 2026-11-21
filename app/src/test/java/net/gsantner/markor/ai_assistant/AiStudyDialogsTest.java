package net.gsantner.markor.ai_assistant;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class AiStudyDialogsTest {
    @Test
    public void parsesQuizQuestionsAndCorrectAnswerIndex() throws IOException {
        final List<AiStudyDialogs.Question> questions = AiStudyDialogs.parseQuiz(
                "{\"questions\":[{\"question\":\"2+2?\",\"options\":[\"1\",\"2\",\"3\",\"4\"],"
                        + "\"correctIndex\":3,\"explanation\":\"Addition.\"}]}");

        assertEquals(1, questions.size());
        assertEquals(3, questions.get(0).getCorrectIndex());
        assertEquals("4", questions.get(0).getOptions().get(3));
    }

    @Test
    public void rejectsQuizQuestionWithWrongOptionCount() throws IOException {
        try {
            AiStudyDialogs.parseQuiz("{\"questions\":[{\"question\":\"Q\",\"options\":[\"A\",\"B\"],"
                    + "\"correctIndex\":0,\"explanation\":\"Why\"}]}");
            fail("Expected malformed quiz response to be rejected");
        } catch (IOException expected) {
            assertEquals("The AI provider returned a malformed quiz question.", expected.getMessage());
        }
    }
}
