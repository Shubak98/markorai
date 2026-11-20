package net.gsantner.markor.ai_assistant;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class SimpleLineDiffTest {
    @Test
    public void reportsChangedLinesAndKeepsContext() {
        final List<SimpleLineDiff.Line> result = SimpleLineDiff.diff(
                "# Title\nold line\nKeep me", "# Title\nnew line\nKeep me");

        assertEquals(4, result.size());
        assertEquals(SimpleLineDiff.Type.UNCHANGED, result.get(0).getType());
        assertEquals("# Title", result.get(0).getText());
        assertEquals(SimpleLineDiff.Type.REMOVED, result.get(1).getType());
        assertEquals("old line", result.get(1).getText());
        assertEquals(SimpleLineDiff.Type.ADDED, result.get(2).getType());
        assertEquals("new line", result.get(2).getText());
        assertEquals(SimpleLineDiff.Type.UNCHANGED, result.get(3).getType());
        assertEquals("Keep me", result.get(3).getText());
    }

    @Test
    public void handlesEmptyDocumentsAndTrailingNewline() {
        final List<SimpleLineDiff.Line> result = SimpleLineDiff.diff("", "first\n");

        assertEquals(2, result.size());
        assertEquals(SimpleLineDiff.Type.ADDED, result.get(1).getType());
        assertEquals("", result.get(1).getText());
        assertEquals(SimpleLineDiff.Type.ADDED, result.get(0).getType());
        assertEquals("first", result.get(0).getText());
    }
}
