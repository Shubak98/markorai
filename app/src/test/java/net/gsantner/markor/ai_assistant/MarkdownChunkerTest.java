package net.gsantner.markor.ai_assistant;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MarkdownChunkerTest {
    @Test
    public void preservesFullTextAtAndBelowThreshold() {
        final String markdown = repeat("x", MarkdownChunker.MAX_FULL_CONTEXT_CHARS);
        assertEquals(markdown, MarkdownChunker.selectContext(markdown, "anything"));
        assertEquals("small note", MarkdownChunker.selectContext("small note", "anything"));
    }

    @Test
    public void oversizedNoteIncludesOutlineAndRelevantSectionsWithinBudget() {
        final String markdown = "# Biology\n" + repeat("background ", 14_000)
                + "\n## Photosynthesis\n" + repeat("chlorophyll energy glucose ", 2_500)
                + "\n## Astronomy\n" + repeat("stars planets orbit ", 2_500);

        final String selected = MarkdownChunker.selectContext(markdown, "explain chlorophyll");

        assertTrue(selected.length() <= MarkdownChunker.MAX_SELECTED_CONTEXT_CHARS);
        assertTrue(selected.contains("# Biology"));
        assertTrue(selected.contains("## Photosynthesis"));
        assertTrue(selected.contains("## Astronomy"));
        assertTrue(selected.contains("chlorophyll"));
    }

    private static String repeat(final String text, final int count) {
        final StringBuilder result = new StringBuilder(text.length() * count);
        for (int index = 0; index < count; index++) {
            result.append(text);
        }
        return result.toString();
    }
}
