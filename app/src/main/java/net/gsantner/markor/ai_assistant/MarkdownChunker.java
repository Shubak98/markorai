package net.gsantner.markor.ai_assistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MarkdownChunker {
    public static final int MAX_FULL_CONTEXT_CHARS = 150_000;
    public static final int MAX_SELECTED_CONTEXT_CHARS = 100_000;

    private MarkdownChunker() {
    }

    public static String selectContext(final String markdown, final String instruction) {
        if (markdown == null || markdown.length() <= MAX_FULL_CONTEXT_CHARS) {
            return markdown == null ? "" : markdown;
        }

        final List<Section> sections = splitSections(markdown);
        final StringBuilder outline = new StringBuilder("Document heading outline:\n");
        for (Section section : sections) {
            if (section.heading != null) {
                if (outline.length() + section.heading.length() + 1 > MAX_SELECTED_CONTEXT_CHARS / 2) {
                    outline.append("... remaining headings omitted\n");
                    break;
                }
                outline.append(section.heading).append('\n');
            }
        }
        final List<String> keywords = keywords(instruction);
        final List<Section> ranked = new ArrayList<>(sections);
        Collections.sort(ranked, new Comparator<Section>() {
            @Override
            public int compare(final Section left, final Section right) {
                final int byScore = Integer.compare(score(right, keywords), score(left, keywords));
                return byScore != 0 ? byScore : Integer.compare(left.position, right.position);
            }
        });

        final int budget = Math.max(0, MAX_SELECTED_CONTEXT_CHARS - outline.length());
        final List<Section> selected = new ArrayList<>();
        int used = 0;
        for (Section section : ranked) {
            if (selected.isEmpty() || score(section, keywords) > 0) {
                final int remaining = budget - used;
                if (remaining <= 0) {
                    break;
                }
                final String selectedText = safePrefix(section.text, remaining);
                if (!selectedText.isEmpty()) {
                    selected.add(new Section(section.heading,
                            selectedText, section.position));
                    used += selectedText.length();
                }
            }
        }
        Collections.sort(selected, Comparator.comparingInt(section -> section.position));

        final StringBuilder result = new StringBuilder(outline.length() + used)
                .append(outline).append("\nRelevant document sections:\n");
        for (Section section : selected) {
            if (result.length() >= MAX_SELECTED_CONTEXT_CHARS) {
                break;
            }
            final int remaining = MAX_SELECTED_CONTEXT_CHARS - result.length();
            result.append(safePrefix(section.text, remaining));
        }
        return result.toString();
    }

    private static String safePrefix(final String text, final int maxChars) {
        int end = Math.min(text.length(), maxChars);
        if (end < text.length() && end > 0) {
            final int lineBreak = text.lastIndexOf('\n', end - 1);
            if (lineBreak >= end - 4096) {
                end = lineBreak + 1;
            }
            if (end > 0 && end < text.length()
                    && Character.isHighSurrogate(text.charAt(end - 1))
                    && Character.isLowSurrogate(text.charAt(end))) {
                end--;
            }
        }
        return text.substring(0, end);
    }

    private static List<Section> splitSections(final String markdown) {
        final List<Section> sections = new ArrayList<>();
        final String[] lines = markdown.split("(?<=\\n)", -1);
        String heading = null;
        int position = 0;
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            final String stripped = line.replaceFirst("[\\r\\n]+$", "");
            if (stripped.matches("^#{1,3}\\s+.*")) {
                if (text.length() > 0) {
                    sections.add(new Section(heading, text.toString(), position));
                }
                heading = stripped;
                position++;
                text = new StringBuilder(line);
            } else {
                text.append(line);
            }
        }
        if (text.length() > 0) {
            sections.add(new Section(heading, text.toString(), position));
        }
        if (sections.isEmpty()) {
            sections.add(new Section(null, markdown, 0));
        }
        return sections;
    }

    private static List<String> keywords(final String instruction) {
        final Set<String> result = new LinkedHashSet<>();
        if (instruction != null) {
            for (String token : instruction.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
                if (token.length() >= 3) {
                    result.add(token);
                }
            }
        }
        return new ArrayList<>(result);
    }

    private static int score(final Section section, final List<String> keywords) {
        int result = 0;
        final String text = section.text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            int index = 0;
            while ((index = text.indexOf(keyword, index)) >= 0) {
                result++;
                index += keyword.length();
            }
        }
        return result;
    }

    private static final class Section {
        private final String heading;
        private final String text;
        private final int position;

        private Section(final String heading, final String text, final int position) {
            this.heading = heading;
            this.text = text;
            this.position = position;
        }
    }
}
