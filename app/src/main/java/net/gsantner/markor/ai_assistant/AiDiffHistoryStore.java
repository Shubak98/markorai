package net.gsantner.markor.ai_assistant;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class AiDiffHistoryStore {
    public static final int MAX_ENTRIES_PER_FILE = 100;
    private static final String HISTORY_DIRECTORY = "ai_diff_history";

    private final File historyDirectory;

    public AiDiffHistoryStore(final Context context) {
        this(privateHistoryDirectory(context));
    }

    AiDiffHistoryStore(final File historyDirectory) {
        this.historyDirectory = historyDirectory;
    }

    public synchronized List<Entry> getHistory(final File document) throws IOException {
        final File historyFile = historyFile(document);
        if (!historyFile.isFile()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(readEntries(historyFile));
    }

    public synchronized Entry addEntry(final File document, final String instruction,
            final String provider, final String model, final String beforeSnapshot,
            final String afterSnapshot) throws IOException {
        if (beforeSnapshot == null || afterSnapshot == null) {
            throw new IOException("Cannot save edit history without document snapshots.");
        }
        final File historyFile = historyFile(document);
        final List<Entry> entries = historyFile.isFile()
                ? readEntries(historyFile) : new ArrayList<>();
        final Entry entry = createEntry(instruction, provider, model, beforeSnapshot, afterSnapshot);
        entries.add(0, entry);
        if (entries.size() > MAX_ENTRIES_PER_FILE) {
            entries.subList(MAX_ENTRIES_PER_FILE, entries.size()).clear();
        }
        writeEntries(historyFile, entries);
        return entry;
    }

    public synchronized void clearHistory(final File document) throws IOException {
        final File historyFile = historyFile(document);
        if (historyFile.exists() && !historyFile.delete()) {
            throw new IOException("Could not clear AI edit history for " + document.getName() + ".");
        }
    }

    public static String exportDiffsAsText(final List<Entry> entries, final String sourceFileName,
            final boolean addedOnly) {
        final StringBuilder output = new StringBuilder("AI Edit Diff Export\n")
                .append("Source: ").append(safeString(sourceFileName)).append('\n')
                .append("Order: Newest first\n\n");
        final SimpleDateFormat dateFormat = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        if (entries == null) {
            return output.toString();
        }
        for (Entry entry : entries) {
            if (entry == null) {
                continue;
            }
            final StringBuilder content = new StringBuilder();
            if (addedOnly) {
                for (String line : entry.diffText.split("\\r?\\n")) {
                    if (line.startsWith("+ ")) {
                        final String addedLine = line.substring(2);
                        if (!addedLine.trim().isEmpty()) {
                            content.append(addedLine).append('\n');
                        }
                    }
                }
                if (content.length() == 0) {
                    continue;
                }
            } else {
                content.append(entry.diffText);
                if (content.length() > 0 && content.charAt(content.length() - 1) != '\n') {
                    content.append('\n');
                }
            }

            output.append("Date/Time: ").append(dateFormat.format(new Date(entry.timestampMs)))
                    .append('\n')
                    .append("Instruction: ").append(entry.instruction.isEmpty()
                            ? "No instruction recorded" : entry.instruction)
                    .append('\n');
            if (!addedOnly) {
                output.append("Provider: ").append(entry.provider.isEmpty()
                                ? "Unknown" : entry.provider)
                        .append('\n');
                if (!entry.model.isEmpty()) {
                    output.append("Model: ").append(entry.model).append('\n');
                }
            }
            output.append('\n').append(content).append('\n');
        }
        return output.toString();
    }

    private static Entry createEntry(final String instruction, final String provider, final String model,
            final String beforeSnapshot, final String afterSnapshot) {
        final List<SimpleLineDiff.Line> lines = SimpleLineDiff.diff(beforeSnapshot, afterSnapshot);
        int added = 0;
        int removed = 0;
        final StringBuilder diff = new StringBuilder();
        for (SimpleLineDiff.Line line : lines) {
            if (line.getType() == SimpleLineDiff.Type.ADDED) {
                added++;
            } else if (line.getType() == SimpleLineDiff.Type.REMOVED) {
                removed++;
            }
            diff.append(prefix(line.getType())).append(line.getText()).append('\n');
        }
        return new Entry(UUID.randomUUID().toString(), System.currentTimeMillis(),
                safeString(instruction), safeString(provider), safeString(model), added, removed,
                diff.toString(), beforeSnapshot);
    }

    private static String prefix(final SimpleLineDiff.Type type) {
        if (type == SimpleLineDiff.Type.ADDED) {
            return "+ ";
        }
        if (type == SimpleLineDiff.Type.REMOVED) {
            return "- ";
        }
        return "  ";
    }

    private File historyFile(final File document) throws IOException {
        if (document == null) {
            throw new IOException("The current note is not available for edit history.");
        }
        final String canonicalPath;
        try {
            canonicalPath = document.getCanonicalPath();
        } catch (IOException exception) {
            throw new IOException("Could not identify the current note for edit history.", exception);
        }
        return new File(historyDirectory, sha256(canonicalPath) + ".json");
    }

    private static String sha256(final String value) throws IOException {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            final char[] hex = new char[digest.length * 2];
            final char[] alphabet = "0123456789abcdef".toCharArray();
            for (int index = 0; index < digest.length; index++) {
                final int byteValue = digest[index] & 0xff;
                hex[index * 2] = alphabet[byteValue >>> 4];
                hex[index * 2 + 1] = alphabet[byteValue & 0x0f];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable for edit history.", exception);
        }
    }

    private List<Entry> readEntries(final File historyFile) throws IOException {
        final ByteArrayOutputStream contents = new ByteArrayOutputStream();
        try (java.io.FileInputStream input = new java.io.FileInputStream(historyFile)) {
            final byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                contents.write(buffer, 0, count);
            }
        }
        final List<?> encoded = GeminiMarkdownHelper.parseJsonArray(
                new String(contents.toByteArray(), StandardCharsets.UTF_8));
        final List<Entry> entries = new ArrayList<>();
        for (Object value : encoded) {
            if (!(value instanceof Map<?, ?>)) {
                throw new IOException("AI edit history contains an invalid entry.");
            }
            final Map<?, ?> object = (Map<?, ?>) value;
            entries.add(new Entry(stringValue(object, "id"), numberValue(object, "timestampMs"),
                    stringValue(object, "instruction"), stringValue(object, "provider"),
                    stringValue(object, "model"), (int) numberValue(object, "addedCount"),
                    (int) numberValue(object, "removedCount"), stringValue(object, "diffText"),
                    stringValue(object, "beforeSnapshot")));
        }
        return entries;
    }

    private static File privateHistoryDirectory(final Context context) {
        if (context == null || context.getFilesDir() == null) {
            throw new IllegalArgumentException("Application internal storage is not available.");
        }
        return new File(context.getFilesDir(), HISTORY_DIRECTORY);
    }

    private void writeEntries(final File historyFile, final List<Entry> entries) throws IOException {
        if (!historyDirectory.isDirectory()
                && !historyDirectory.mkdirs() && !historyDirectory.isDirectory()) {
            throw new IOException("Could not create private AI edit history storage.");
        }
        final File temporaryFile = new File(historyDirectory,
                historyFile.getName() + "." + UUID.randomUUID() + ".tmp");
        try {
            final byte[] contents = encodeEntries(entries).getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream output = new FileOutputStream(temporaryFile)) {
                output.write(contents);
                output.getFD().sync();
            }
            if (!temporaryFile.renameTo(historyFile)) {
                throw new IOException("Could not persist AI edit history.");
            }
        } catch (IOException exception) {
            if (temporaryFile.exists() && !temporaryFile.delete()) {
                exception.addSuppressed(new IOException("Could not remove temporary AI edit history data."));
            }
            throw exception;
        }
    }

    private static String encodeEntries(final List<Entry> entries) {
        final StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < entries.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            final Entry entry = entries.get(index);
            json.append('{')
                    .append("\"id\":").append(quote(entry.id)).append(',')
                    .append("\"timestampMs\":").append(entry.timestampMs).append(',')
                    .append("\"instruction\":").append(quote(entry.instruction)).append(',')
                    .append("\"provider\":").append(quote(entry.provider)).append(',')
                    .append("\"model\":").append(quote(entry.model)).append(',')
                    .append("\"addedCount\":").append(entry.addedCount).append(',')
                    .append("\"removedCount\":").append(entry.removedCount).append(',')
                    .append("\"diffText\":").append(quote(entry.diffText)).append(',')
                    .append("\"beforeSnapshot\":").append(quote(entry.beforeSnapshot))
                    .append('}');
        }
        return json.append(']').toString();
    }

    private static String quote(final String value) {
        final StringBuilder quoted = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            switch (character) {
                case '"':
                    quoted.append("\\\"");
                    break;
                case '\\':
                    quoted.append("\\\\");
                    break;
                case '\b':
                    quoted.append("\\b");
                    break;
                case '\f':
                    quoted.append("\\f");
                    break;
                case '\n':
                    quoted.append("\\n");
                    break;
                case '\r':
                    quoted.append("\\r");
                    break;
                case '\t':
                    quoted.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        quoted.append("\\u");
                        final String hex = Integer.toHexString(character);
                        for (int padding = hex.length(); padding < 4; padding++) {
                            quoted.append('0');
                        }
                        quoted.append(hex);
                    } else {
                        quoted.append(character);
                    }
                    break;
            }
        }
        return quoted.append('"').toString();
    }

    private static String stringValue(final Map<?, ?> json, final String key) throws IOException {
        final Object value = json.get(key);
        if (!(value instanceof String)) {
            throw new IOException("AI edit history entry is missing \"" + key + "\".");
        }
        return (String) value;
    }

    private static long numberValue(final Map<?, ?> json, final String key) throws IOException {
        final Object value = json.get(key);
        if (!(value instanceof Number)) {
            throw new IOException("AI edit history entry is missing \"" + key + "\".");
        }
        return ((Number) value).longValue();
    }

    private static String safeString(final String value) {
        return value == null ? "" : value;
    }

    public static final class Entry {
        private final String id;
        private final long timestampMs;
        private final String instruction;
        private final String provider;
        private final String model;
        private final int addedCount;
        private final int removedCount;
        private final String diffText;
        private final String beforeSnapshot;

        private Entry(final String id, final long timestampMs, final String instruction,
                final String provider, final String model, final int addedCount, final int removedCount,
                final String diffText, final String beforeSnapshot) {
            this.id = id;
            this.timestampMs = timestampMs;
            this.instruction = instruction;
            this.provider = provider;
            this.model = model;
            this.addedCount = addedCount;
            this.removedCount = removedCount;
            this.diffText = diffText;
            this.beforeSnapshot = beforeSnapshot;
        }

        public String getId() {
            return id;
        }

        public long getTimestampMs() {
            return timestampMs;
        }

        public String getInstruction() {
            return instruction;
        }

        public String getProvider() {
            return provider;
        }

        public String getModel() {
            return model;
        }

        public int getAddedCount() {
            return addedCount;
        }

        public int getRemovedCount() {
            return removedCount;
        }

        public String getDiffText() {
            return diffText;
        }

        public String getBeforeSnapshot() {
            return beforeSnapshot;
        }
    }
}
