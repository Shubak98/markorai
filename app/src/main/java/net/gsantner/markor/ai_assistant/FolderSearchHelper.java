package net.gsantner.markor.ai_assistant;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class FolderSearchHelper {
    public static final int MAX_FILES = 25;
    public static final int MAX_CONTEXT_BYTES = 120 * 1024;
    private static final int MAX_STORED_FILE_CHARS = 16 * 1024;

    private FolderSearchHelper() {
    }

    public static SearchResult search(final File folder, final String query) throws IOException {
        if (folder == null || !folder.isDirectory()) {
            throw new IOException("The current note's folder is not available for AI search.");
        }
        final Set<String> terms = tokenize(query);
        final List<Candidate> candidates = new ArrayList<>();
        collectFiles(folder, folder, 0, terms, candidates);
        Collections.sort(candidates, new Comparator<Candidate>() {
            @Override
            public int compare(final Candidate first, final Candidate second) {
                final int byScore = Integer.compare(second.score, first.score);
                return byScore != 0 ? byScore : first.relativePath.compareToIgnoreCase(second.relativePath);
            }
        });

        final StringBuilder context = new StringBuilder();
        String bestMatch = null;
        int included = 0;
        int contextBytes = 0;
        for (Candidate candidate : candidates) {
            if (candidate.score <= 0 || included >= MAX_FILES) {
                break;
            }
            final String header = "=== FILE: " + candidate.relativePath + " ===\n";
            final String body = candidate.content.endsWith("\n") ? candidate.content : candidate.content + "\n";
            final int remainingBytes = MAX_CONTEXT_BYTES - contextBytes;
            final int headerBytes = header.getBytes(StandardCharsets.UTF_8).length;
            if (remainingBytes <= headerBytes) {
                break;
            }
            String clippedBody = truncateUtf8(body, remainingBytes - headerBytes);
            if (clippedBody.isEmpty()) {
                continue;
            }
            final int separatorBytes = clippedBody.endsWith("\n") ? 0 : 1;
            if (separatorBytes > 0 && headerBytes
                    + clippedBody.getBytes(StandardCharsets.UTF_8).length + separatorBytes <= remainingBytes) {
                clippedBody += "\n";
            }
            context.append(header).append(clippedBody);
            contextBytes += headerBytes + clippedBody.getBytes(StandardCharsets.UTF_8).length;
            if (bestMatch == null) {
                bestMatch = candidate.relativePath;
            }
            included++;
        }
        return new SearchResult(context.toString(), bestMatch, included);
    }

    public static boolean isFolderSearchQuery(final String query) {
        final String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        final String[] cues = {
                "folder", "across notes", "all notes", "other notes", "in my notes",
                "search notes", "search folder", "find in notes", "which note",
                "what did i write", "where did i write", "where did i mention", "which file",
                "search my notes", "notes about", "what have i noted", "find where",
                "kis note", "kis file", "kahan likha", "kahan mention", "maine likha",
                "sabhi notes", "dusre notes", "folder mein", "notes mein", "mere notes", "sab notes"
        };
        for (String cue : cues) {
            if (normalized.contains(cue)) {
                return true;
            }
        }
        return false;
    }

    private static void collectFiles(final File root, final File directory, final int depth,
            final Set<String> terms, final List<Candidate> candidates) throws IOException {
        final String rootPath = root.getCanonicalPath();
        final String rootPrefix = rootPath.endsWith(File.separator) ? rootPath : rootPath + File.separator;
        final File[] children = directory.listFiles();
        if (children == null) {
            throw new IOException("Could not read the note folder: " + directory.getAbsolutePath());
        }
        java.util.Arrays.sort(children, new Comparator<File>() {
            @Override
            public int compare(final File first, final File second) {
                return first.getName().compareToIgnoreCase(second.getName());
            }
        });
        for (File child : children) {
            if (child.getName().startsWith(".")) {
                continue;
            }
            final String childPath = child.getCanonicalPath();
            if (!childPath.startsWith(rootPrefix)) {
                continue;
            }
            if (child.isDirectory()) {
                if (depth < 2) {
                    collectFiles(root, child, depth + 1, terms, candidates);
                }
            } else if (child.isFile() && isTextNote(child)) {
                candidates.add(readCandidate(root, child, terms));
            }
        }
    }

    private static boolean isTextNote(final File file) {
        final String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".md") || name.endsWith(".txt");
    }

    private static Candidate readCandidate(final File root, final File file, final Set<String> terms)
            throws IOException {
        final String relativePath = root.toURI().relativize(file.toURI()).getPath();
        final Set<String> filenameTerms = tokenize(relativePath.replace('/', ' '));
        int score = overlapScore(terms, filenameTerms, 4);
        final StringBuilder content = new StringBuilder((int) Math.min(MAX_STORED_FILE_CHARS, file.length()));
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final String trimmed = line.trim();
                if (trimmed.startsWith("#")) {
                    score += overlapScore(terms, tokenize(trimmed.replaceFirst("^#+\\s*", "")), 3);
                }
                score += overlapScore(terms, tokenize(line), 1);
                if (content.length() < MAX_STORED_FILE_CHARS) {
                    final int remaining = MAX_STORED_FILE_CHARS - content.length();
                    content.append(line, 0, Math.min(line.length(), remaining));
                    if (content.length() < MAX_STORED_FILE_CHARS) {
                        content.append('\n');
                    }
                }
            }
        }
        return new Candidate(relativePath, content.toString(), score);
    }

    private static int overlapScore(final Set<String> queryTerms, final Set<String> candidateTerms,
            final int weight) {
        int score = 0;
        for (String term : queryTerms) {
            if (candidateTerms.contains(term)) {
                score += weight;
            }
        }
        return score;
    }

    private static Set<String> tokenize(final String text) {
        final Set<String> terms = new HashSet<>();
        if (text == null) {
            return terms;
        }
        for (String term : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (term.length() > 1) {
                terms.add(term);
            }
        }
        return terms;
    }

    private static String truncateUtf8(final String value, final int maxBytes) {
        final StringBuilder truncated = new StringBuilder();
        int usedBytes = 0;
        for (int offset = 0; offset < value.length();) {
            final int codePoint = value.codePointAt(offset);
            final int charCount = Character.charCount(codePoint);
            final int codePointBytes = codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
            if (usedBytes + codePointBytes > maxBytes) {
                break;
            }
            truncated.append(value, offset, offset + charCount);
            usedBytes += codePointBytes;
            offset += charCount;
        }
        return truncated.toString();
    }

    private static final class Candidate {
        private final String relativePath;
        private final String content;
        private final int score;

        private Candidate(final String relativePath, final String content, final int score) {
            this.relativePath = relativePath;
            this.content = content;
            this.score = score;
        }
    }

    public static final class SearchResult {
        private final String context;
        private final String bestMatch;
        private final int fileCount;

        private SearchResult(final String context, final String bestMatch, final int fileCount) {
            this.context = context;
            this.bestMatch = bestMatch;
            this.fileCount = fileCount;
        }

        public String getContext() {
            return context;
        }

        public String getBestMatch() {
            return bestMatch;
        }

        public int getFileCount() {
            return fileCount;
        }
    }
}
