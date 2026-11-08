package com.germanreader.processing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DocumentStatsTest {

    @Test
    void countsWordsCharactersAndParagraphs() {
        String text = "Am Morgen sitzt meine Großmutter auf der Bank im Park.\n\nSie füttert die Tauben.";

        assertThat(DocumentStats.of(text)).isEqualTo(new DocumentStats(14, text.length(), 2));
    }

    @Test
    void keepsHyphenatedAndApostropheWordsTogetherAndIgnoresPunctuation() {
        assertThat(DocumentStats.of("Schreib mir eine E-Mail, wie geht's? – 2026!").words()).isEqualTo(7);
    }

    @Test
    void singleLineBreaksDoNotStartAParagraphButBlankLinesDo() {
        assertThat(DocumentStats.of("Zeile eins\nZeile zwei").paragraphs()).isEqualTo(1);
        assertThat(DocumentStats.of("Eins\n\n\n  \nZwei\n \nDrei").paragraphs()).isEqualTo(3);
    }

    @Test
    void countsCharactersAsCodePoints() {
        assertThat(DocumentStats.of("Ä😀").characters()).isEqualTo(2);
    }
}
