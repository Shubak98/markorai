package net.gsantner.markor.ai_assistant;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FolderSearchHelperTest {
    @Test
    public void ranksHeadingsAndCapsFolderContext() throws Exception {
        final File folder = File.createTempFile("ai-assistant", "folder");
        assertTrue(folder.delete());
        assertTrue(folder.mkdir());
        try {
            write(new File(folder, "heading.md"), "# Quantum mechanics\nA useful overview.\n");
            write(new File(folder, "quantum.md"), "A short note that mentions quantum.\n");
            final File hidden = new File(folder, ".git");
            assertTrue(hidden.mkdir());
            write(new File(hidden, "hidden.md"), "# Quantum mechanics\n");

            final FolderSearchHelper.SearchResult ranked = FolderSearchHelper.search(folder, "quantum mechanics");
            assertEquals("heading.md", ranked.getBestMatch());
            assertFalse(ranked.getContext().contains(".git/hidden.md"));

            final char[] largeText = new char[20_000];
            Arrays.fill(largeText, 'x');
            for (int index = 0; index < 35; index++) {
                write(new File(folder, String.format("large-%02d.txt", index)),
                        "# Caplimit document\n" + new String(largeText));
            }
            final FolderSearchHelper.SearchResult capped = FolderSearchHelper.search(folder, "caplimit");
            assertTrue(capped.getFileCount() <= FolderSearchHelper.MAX_FILES);
            assertTrue(capped.getFileCount() > 0);
            assertTrue(capped.getContext().getBytes(StandardCharsets.UTF_8).length
                    <= FolderSearchHelper.MAX_CONTEXT_BYTES);
        } finally {
            deleteTree(folder);
        }
    }

    private static void write(final File file, final String content) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
        }
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
