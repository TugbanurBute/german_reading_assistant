package com.germanreader.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.germanreader.dto.SourceLanguage;
import com.germanreader.dto.TargetLanguage;
import com.germanreader.dto.TranslationRequest;
import com.germanreader.dto.TranslationType;
import org.junit.jupiter.api.Test;

class TranslationCacheKeyTest {

    private static final String BENCH_SENTENCE = "Am Morgen sitzt meine Großmutter auf der Bank im Park.";
    private static final String BANK_SENTENCE = "Danach bringt sie ihr Geld zur Bank.";

    private static TranslationRequest word(String word, String context, TargetLanguage target) {
        return new TranslationRequest(word, context, SourceLanguage.DE, target, TranslationType.WORD);
    }

    private static TranslationRequest sentence(String text, String context) {
        return new TranslationRequest(text, context, SourceLanguage.DE, TargetLanguage.TR, TranslationType.SENTENCE);
    }

    private static String mockKey(TranslationRequest request) {
        return TranslationCacheKey.of("mock", "mock-v1", request).hash();
    }

    @Test
    void hashIsSha256Hex() {
        assertThat(mockKey(word("Bank", BENCH_SENTENCE, TargetLanguage.TR))).matches("[0-9a-f]{64}");
    }

    @Test
    void whitespaceAndUnicodeFormDoNotChangeTheKey() {
        TranslationRequest plain = word("Bank", BENCH_SENTENCE, TargetLanguage.TR);
        TranslationRequest messy = word(" Bank\n", "  Am Morgen sitzt meine\tGroßmutter auf der Bank im Park. ",
                TargetLanguage.TR);
        // "ä" as one code point (NFC) and as "a" + combining diaeresis (NFD)
        TranslationRequest composed = word("Häusern", "Die Kinder spielen zwischen den Häusern.",
                TargetLanguage.TR);
        TranslationRequest decomposed = word("Häusern", "Die Kinder spielen zwischen den Häusern.",
                TargetLanguage.TR);

        assertThat(mockKey(messy)).isEqualTo(mockKey(plain));
        assertThat(mockKey(decomposed)).isEqualTo(mockKey(composed));
    }

    @Test
    void storesNormalizedTextAndContext() {
        TranslationCacheKey key = TranslationCacheKey.of("mock", "mock-v1",
                word(" Bank ", "Am  Morgen sitzt meine Großmutter auf der Bank im Park.", TargetLanguage.TR));

        assertThat(key.selectedText()).isEqualTo("Bank");
        assertThat(key.context()).isEqualTo(BENCH_SENTENCE);
    }

    @Test
    void targetLanguageChangesTheKey() {
        assertThat(mockKey(word("Bank", BENCH_SENTENCE, TargetLanguage.TR)))
                .isNotEqualTo(mockKey(word("Bank", BENCH_SENTENCE, TargetLanguage.EN)));
    }

    @Test
    void sameWordInDifferentSentencesHasDifferentKeys() {
        assertThat(mockKey(word("Bank", BENCH_SENTENCE, TargetLanguage.TR)))
                .isNotEqualTo(mockKey(word("Bank", BANK_SENTENCE, TargetLanguage.TR)));
    }

    @Test
    void caseIsKept() {
        String sentence = "Am Morgen ruft er morgen an.";
        assertThat(mockKey(word("Morgen", sentence, TargetLanguage.TR)))
                .isNotEqualTo(mockKey(word("morgen", sentence, TargetLanguage.TR)));
    }

    @Test
    void providerNamespaceAndPromptVersionChangeTheKey() {
        TranslationRequest request = word("Bank", BENCH_SENTENCE, TargetLanguage.TR);
        String mock = TranslationCacheKey.of("mock", "v1", request).hash();

        assertThat(TranslationCacheKey.of("gemini:model-a", "v1", request).hash()).isNotEqualTo(mock);
        assertThat(TranslationCacheKey.of("gemini:model-a", "v1", request).hash())
                .isNotEqualTo(TranslationCacheKey.of("gemini:model-b", "v1", request).hash());
        assertThat(TranslationCacheKey.of("mock", "v2", request).hash()).isNotEqualTo(mock);
    }

    @Test
    void sentenceRequestsIgnoreContext() {
        TranslationCacheKey key = TranslationCacheKey.of("mock", "mock-v1", sentence(BANK_SENTENCE, "anything"));

        assertThat(key.context()).isNull();
        assertThat(key.hash()).isEqualTo(mockKey(sentence(BANK_SENTENCE, null)));
    }

    @Test
    void wordAndSentenceWithTheSameTextHaveDifferentKeys() {
        assertThat(mockKey(word("Danke", "Danke", TargetLanguage.TR)))
                .isNotEqualTo(mockKey(sentence("Danke", null)));
    }
}
