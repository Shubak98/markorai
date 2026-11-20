package net.gsantner.markor.ai_assistant;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GeminiMarkdownHelperTest {
    @Test
    public void stripsOuterMarkdownCodeFence() {
        assertEquals("# Updated\nText", GeminiMarkdownHelper.stripMarkdownFences(
                "```markdown\n# Updated\nText\n```"));
    }

    @Test
    public void leavesUnfencedMarkdownAndOtherLanguagesUntouched() {
        assertEquals("# Plain\nText", GeminiMarkdownHelper.stripMarkdownFences("# Plain\nText"));
        assertEquals("```java\ncode\n```",
                GeminiMarkdownHelper.stripMarkdownFences("```java\ncode\n```"));
    }

    @Test
    public void appendsOnlyGenerationModelsAndReturnsNextPageToken() throws IOException {
        final List<String> models = new ArrayList<>();
        final String nextPageToken = GeminiMarkdownHelper.appendAvailableModels(
                "{\"models\":["
                        + "{\"name\":\"models/z-model\",\"supportedGenerationMethods\":[\"generateContent\"]},"
                        + "{\"name\":\"models/embedding\",\"supportedGenerationMethods\":[\"embedContent\"]},"
                        + "{\"name\":\"models/a-model\",\"supportedGenerationMethods\":[\"generateContent\"]}"
                        + "],\"nextPageToken\":\"next token\"}", models);

        assertEquals(Arrays.asList("z-model", "a-model"), models);
        assertEquals("next token", nextPageToken);
    }

    @Test
    public void appliesInsertAfterEdit() throws IOException {
        assertEquals("# Topic\nExisting text\nNew text\n",
                GeminiMarkdownHelper.applyEdits("# Topic\nExisting text\n",
                        "{\"edits\":[{\"action\":\"insert_after\",\"target\":\"Existing text\","
                                + "\"content\":\"\\nNew text\"}]}"));
    }

    @Test
    public void appliesReplaceEdit() throws IOException {
        assertEquals("# Topic\nUpdated text\n",
                GeminiMarkdownHelper.applyEdits("# Topic\nOld text\n",
                        "{\"edits\":[{\"action\":\"replace\",\"target\":\"Old text\","
                                + "\"content\":\"Updated text\"}]}"));
    }

    @Test
    public void appliesAppendEndEdit() throws IOException {
        assertEquals("# Topic\nExisting text\nAdded at end",
                GeminiMarkdownHelper.applyEdits("# Topic\nExisting text",
                        "{\"edits\":[{\"action\":\"append_end\",\"target\":\"\","
                                + "\"content\":\"\\nAdded at end\"}]}"));
    }

    @Test
    public void appliesMultipleEditsAgainstOriginalMarkdown() throws IOException {
        assertEquals("First updated\nSecond updated\n",
                GeminiMarkdownHelper.applyEdits("First\nSecond\n",
                        "{\"edits\":["
                                + "{\"action\":\"replace\",\"target\":\"Second\",\"content\":\"Second updated\"},"
                                + "{\"action\":\"replace\",\"target\":\"First\",\"content\":\"First updated\"}"
                                + "]}"));
    }

    @Test
    public void missingEditTargetReportsTargetWithoutChangingOriginal() throws IOException {
        final String original = "# Topic\nExisting text\n";
        try {
            GeminiMarkdownHelper.applyEdits(original,
                    "{\"edits\":[{\"action\":\"replace\",\"target\":\"Missing text\","
                            + "\"content\":\"Updated text\"}]}");
            fail("Expected a missing-target error");
        } catch (IOException exception) {
            assertTrue(exception.getMessage().contains("Missing text"));
        }
        assertEquals("# Topic\nExisting text\n", original);
    }
}
