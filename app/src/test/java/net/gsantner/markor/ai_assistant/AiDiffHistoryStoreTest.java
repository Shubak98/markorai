package net.gsantner.markor.ai_assistant;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AiDiffHistoryStoreTest {
    @Test
    public void savesNewestFirstWithDiffCountsAndSnapshots() throws Exception {
        final File root = Files.createTempDirectory("ai-diff-history").toFile();
        try {
            final File notes = new File(root, "notes");
            assertTrue(notes.mkdir());
            final File document = new File(notes, "lesson.md");
            final AiDiffHistoryStore store = new AiDiffHistoryStore(new File(root, "files/ai_diff_history"));

            store.addEntry(document, "Update title", "gemini", "gemini-3.5-flash-lite",
                    "# Old\nKeep", "# New\nKeep");
            store.addEntry(document, "Add summary", "groq", "llama-model",
                    "# New\nKeep", "# New\nKeep\nSummary");

            final List<AiDiffHistoryStore.Entry> history = store.getHistory(document);
            assertEquals(2, history.size());
            assertEquals("Add summary", history.get(0).getInstruction());
            assertEquals("groq", history.get(0).getProvider());
            assertEquals("# New\nKeep", history.get(0).getBeforeSnapshot());
            assertEquals(1, history.get(0).getAddedCount());
            assertEquals(0, history.get(0).getRemovedCount());
            assertEquals("  # New\n  Keep\n+ Summary\n", history.get(0).getDiffText());
            assertEquals("Update title", history.get(1).getInstruction());
            assertEquals(1, history.get(1).getAddedCount());
            assertEquals(1, history.get(1).getRemovedCount());
            assertEquals("- # Old\n+ # New\n  Keep\n", history.get(1).getDiffText());
            assertFalse(history.get(0).getId().equals(history.get(1).getId()));
            assertEquals(0, listFiles(notes).length);
            final File[] historyFiles = listFiles(new File(root, "files/ai_diff_history"));
            assertEquals(1, historyFiles.length);
            assertTrue(historyFiles[0].getName().matches("[a-f0-9]{64}\\.json"));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    public void isolatesHistoryByCanonicalFilePath() throws Exception {
        final File root = Files.createTempDirectory("ai-diff-history-isolation").toFile();
        try {
            final AiDiffHistoryStore store = new AiDiffHistoryStore(new File(root, "history"));
            final File first = new File(root, "first.md");
            final File second = new File(root, "second.md");
            store.addEntry(first, "First note", "gemini", "model", "before", "after");
            store.addEntry(second, "Second note", "groq", "model", "before", "after");

            assertEquals("First note", store.getHistory(first).get(0).getInstruction());
            assertEquals("Second note", store.getHistory(second).get(0).getInstruction());
        } finally {
            deleteTree(root);
        }
    }

    @Test
    public void capsEntriesPerFileAtTheNewestHundred() throws Exception {
        final File root = Files.createTempDirectory("ai-diff-history-cap").toFile();
        try {
            final AiDiffHistoryStore store = new AiDiffHistoryStore(new File(root, "history"));
            final File document = new File(root, "note.md");
            for (int index = 0; index < AiDiffHistoryStore.MAX_ENTRIES_PER_FILE + 3; index++) {
                store.addEntry(document, "Prompt " + index, "custom", "model",
                        "before " + index, "after " + index);
            }

            final List<AiDiffHistoryStore.Entry> history = store.getHistory(document);
            assertEquals(AiDiffHistoryStore.MAX_ENTRIES_PER_FILE, history.size());
            assertEquals("Prompt 102", history.get(0).getInstruction());
            assertEquals("Prompt 3", history.get(history.size() - 1).getInstruction());
        } finally {
            deleteTree(root);
        }
    }

    @Test
    public void exportsOnlyAddedContentWithoutDiffPrefixes() throws Exception {
        final File root = Files.createTempDirectory("ai-diff-history-added-export").toFile();
        try {
            final AiDiffHistoryStore store = new AiDiffHistoryStore(new File(root, "history"));
            final File document = new File(root, "lesson.md");
            store.addEntry(document, "Refresh lesson", "gemini", "model",
                    "# Old title\nKeep this context\nRemove this line",
                    "# New title\n\nKeep this context\nAdd this line");

            final String exported = AiDiffHistoryStore.exportDiffsAsText(
                    store.getHistory(document), document.getName(), true);

            assertTrue(exported.contains("Source: lesson.md"));
            assertTrue(exported.contains("Order: Newest first"));
            assertTrue(exported.contains("Instruction: Refresh lesson"));
            assertTrue(exported.contains("# New title\nAdd this line\n"));
            assertFalse(exported.contains("+ "));
            assertFalse(exported.contains("- "));
            assertFalse(exported.contains("Keep this context"));
            assertFalse(exported.contains("Remove this line"));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    public void exportsFullDiffWithTimestampInstructionAndProvider() throws Exception {
        final File root = Files.createTempDirectory("ai-diff-history-full-export").toFile();
        try {
            final AiDiffHistoryStore store = new AiDiffHistoryStore(new File(root, "history"));
            final File document = new File(root, "lesson.md");
            store.addEntry(document, "Refresh lesson", "gemini", "model-1",
                    "# Old title\nKeep this context", "# New title\nKeep this context");

            final String exported = AiDiffHistoryStore.exportDiffsAsText(
                    store.getHistory(document), document.getName(), false);

            assertTrue(exported.contains("Source: lesson.md"));
            assertTrue(exported.contains("Date/Time: "));
            assertTrue(exported.contains("Instruction: Refresh lesson"));
            assertTrue(exported.contains("Provider: gemini"));
            assertTrue(exported.contains("Model: model-1"));
            assertTrue(exported.contains("- # Old title"));
            assertTrue(exported.contains("+ # New title"));
            assertTrue(exported.contains("  Keep this context"));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    public void clearsOnlyTheRequestedFilesHistory() throws Exception {
        final File root = Files.createTempDirectory("ai-diff-history-clear").toFile();
        try {
            final AiDiffHistoryStore store = new AiDiffHistoryStore(new File(root, "history"));
            final File first = new File(root, "first.md");
            final File second = new File(root, "second.md");
            store.addEntry(first, "First", "gemini", "model", "before", "after");
            store.addEntry(second, "Second", "groq", "model", "before", "after");

            store.clearHistory(first);

            assertTrue(store.getHistory(first).isEmpty());
            assertEquals(1, store.getHistory(second).size());
            assertFalse(store.getHistory(second).isEmpty());
        } finally {
            deleteTree(root);
        }
    }

    private static File[] listFiles(final File directory) {
        final File[] files = directory.listFiles();
        return files == null ? new File[0] : files;
    }

    private static void deleteTree(final File file) {
        final File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        if (!file.delete()) {
            throw new AssertionError("Could not remove test file " + file);
        }
    }
}
