package net.gsantner.markor.ai_assistant;

import org.junit.Test;

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
}
