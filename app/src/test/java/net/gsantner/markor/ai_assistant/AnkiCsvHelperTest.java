package net.gsantner.markor.ai_assistant;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class AnkiCsvHelperTest {
    @Test
    public void escapesCommasQuotesAndNewlinesUsingRfc4180Quoting() {
        final AnkiCsvHelper.Card card =
                new AnkiCsvHelper.Card("term, \"quoted\"", "line one\nline \"two\"");

        assertEquals("Front,Back\r\n\"term, \"\"quoted\"\"\",\"line one\r\nline \"\"two\"\"\"\r\n",
                AnkiCsvHelper.toCsv(Arrays.asList(card)));
    }
}
