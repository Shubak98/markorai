package net.gsantner.markor.ai_assistant;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

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
}
