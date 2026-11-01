package com.example.germanreader.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextNormalizerTest {

    @Test
    void collapsesWhitespaceAndLineBreaks() {
        assertThat(TextNormalizer.normalize("  Die Kinder\n spielen\t\tdraußen. "))
                .isEqualTo("Die Kinder spielen draußen.");
    }

    @Test
    void composesDecomposedUmlauts() {
        String decomposed = "Häuser"; // "a" + combining diaeresis, as some keyboards/PDFs produce
        assertThat(TextNormalizer.normalize(decomposed)).isEqualTo("Häuser");
    }

    @Test
    void preservesCase() {
        assertThat(TextNormalizer.normalize("Essen")).isNotEqualTo(TextNormalizer.normalize("essen"));
    }

    @Test
    void returnsNullForNull() {
        assertThat(TextNormalizer.normalize(null)).isNull();
    }
}
