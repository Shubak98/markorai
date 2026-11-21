package net.gsantner.markor.ai_assistant;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AnkiCsvHelper {
    private AnkiCsvHelper() {
    }

    public static List<Card> parseCards(final String json) throws IOException {
        final Map<?, ?> object = GeminiMarkdownHelper.parseJsonObject(json);
        final Object value = object.get("cards");
        if (!(value instanceof List<?>) || ((List<?>) value).isEmpty()) {
            throw new IOException("The AI provider returned no Anki cards.");
        }
        final List<Card> cards = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof Map<?, ?>)) {
                throw new IOException("The AI provider returned an invalid Anki card.");
            }
            final Map<?, ?> card = (Map<?, ?>) item;
            final Object front = card.get("front");
            final Object back = card.get("back");
            if (!(front instanceof String) || ((String) front).trim().isEmpty()
                    || !(back instanceof String) || ((String) back).trim().isEmpty()) {
                throw new IOException("Every Anki card must have non-empty front and back text.");
            }
            cards.add(new Card((String) front, (String) back));
        }
        return cards;
    }

    public static String toCsv(final List<Card> cards) {
        final StringBuilder csv = new StringBuilder("Front,Back\r\n");
        for (Card card : cards) {
            csv.append(escape(card.front)).append(',').append(escape(card.back)).append("\r\n");
        }
        return csv.toString();
    }

    private static String escape(final String value) {
        final String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.indexOf(',') >= 0 || normalized.indexOf('"') >= 0
                || normalized.indexOf('\n') >= 0) {
            return '"' + normalized.replace("\"", "\"\"").replace("\n", "\r\n") + '"';
        }
        return normalized;
    }

    public static final class Card {
        private final String front;
        private final String back;

        public Card(final String front, final String back) {
            this.front = front;
            this.back = back;
        }

        public String getFront() {
            return front;
        }

        public String getBack() {
            return back;
        }
    }
}
