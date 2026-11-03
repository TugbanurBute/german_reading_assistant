package com.germanreader.service.translation.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.germanreader.dto.PartOfSpeech;
import com.germanreader.dto.SourceLanguage;
import com.germanreader.dto.TargetLanguage;
import com.germanreader.dto.TranslationRequest;
import com.germanreader.dto.TranslationResponse;
import com.germanreader.dto.TranslationType;
import com.germanreader.dto.grammar.Article;
import com.germanreader.dto.grammar.Auxiliary;
import com.germanreader.dto.grammar.GrammaticalCase;
import com.germanreader.dto.grammar.GrammaticalNumber;
import com.germanreader.dto.grammar.NounGrammar;
import com.germanreader.dto.grammar.OtherGrammar;
import com.germanreader.dto.grammar.Tense;
import com.germanreader.dto.grammar.VerbGrammar;
import com.germanreader.exception.ErrorCode;
import com.germanreader.exception.TranslationException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class MockTranslationProviderTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final MockTranslationProvider provider = new MockTranslationProvider(JSON);

    private static final String BENCH_SENTENCE = "Am Morgen sitzt meine Großmutter auf der Bank im Park.";
    private static final String BANK_SENTENCE = "Danach bringt sie ihr Geld zur Bank.";

    private static TranslationRequest word(String word, String sentence, TargetLanguage target) {
        return new TranslationRequest(word, sentence, SourceLanguage.DE, target, TranslationType.WORD);
    }

    private static TranslationRequest sentence(String sentence, TargetLanguage target) {
        return new TranslationRequest(sentence, null, SourceLanguage.DE, target, TranslationType.SENTENCE);
    }

    @Test
    void translatesNounWithFullGrammarToTurkish() {
        TranslationResponse response = provider.translate(
                word("Häusern", "Die Kinder spielen zwischen den Häusern.", TargetLanguage.TR));

        assertThat(response.translation()).isEqualTo("evler");
        assertThat(response.lemma()).isEqualTo("Haus");
        assertThat(response.partOfSpeech()).isEqualTo(PartOfSpeech.NOUN);
        assertThat(response.grammar()).isEqualTo(new NounGrammar(Article.DAS, "Häuser", GrammaticalCase.DATIVE,
                GrammaticalNumber.PLURAL));
        assertThat(response.sentenceTranslation()).isEqualTo("Çocuklar evlerin arasında oynuyor.");
        assertThat(response.targetLanguage()).isEqualTo(TargetLanguage.TR);
    }

    @Test
    void translatesSeparableVerbToEnglish() {
        TranslationResponse response = provider.translate(word("rufe", "Ich rufe dich morgen an.", TargetLanguage.EN));

        assertThat(response.lemma()).isEqualTo("anrufen");
        assertThat(response.grammar()).isInstanceOfSatisfying(VerbGrammar.class, verb -> {
            assertThat(verb.tense()).isEqualTo(Tense.PRESENT);
            assertThat(verb.auxiliary()).isEqualTo(Auxiliary.HABEN);
            assertThat(verb.separablePrefix()).isEqualTo("an");
        });
        assertThat(response.sentenceTranslation()).isEqualTo("I'll call you tomorrow.");
    }

    @Test
    void sameWordInDifferentContextsHasDifferentMeanings() {
        TranslationResponse bench = provider.translate(word("Bank", BENCH_SENTENCE, TargetLanguage.EN));
        TranslationResponse bank = provider.translate(word("Bank", BANK_SENTENCE, TargetLanguage.EN));

        assertThat(bench.translation()).isEqualTo("bench");
        assertThat(bank.translation()).isEqualTo("bank");
        assertThat(((NounGrammar) bench.grammar()).plural()).isEqualTo("Bänke");
        assertThat(((NounGrammar) bank.grammar()).plural()).isEqualTo("Banken");
    }

    @Test
    void returnsOtherGrammarForAdjectives() {
        TranslationResponse response = provider.translate(
                word("hohen", "Das alte Schloss steht auf einem hohen Berg.", TargetLanguage.TR));

        assertThat(response.partOfSpeech()).isEqualTo(PartOfSpeech.ADJECTIVE);
        assertThat(response.lemma()).isEqualTo("hoch");
        assertThat(response.grammar()).isInstanceOf(OtherGrammar.class);
    }

    @Test
    void keepsUnknownGrammarValuesAsNull() {
        TranslationResponse response = provider.translate(
                word("Regens", "Wegen des Regens bleiben wir heute zu Hause.", TargetLanguage.EN));

        assertThat(((NounGrammar) response.grammar()).plural()).isNull();
    }

    @Test
    void translatesWholeSentence() {
        TranslationResponse response = provider.translate(sentence(BANK_SENTENCE, TargetLanguage.TR));

        assertThat(response.translationType()).isEqualTo(TranslationType.SENTENCE);
        assertThat(response.translation()).isEqualTo("Daha sonra parasını bankaya götürüyor.");
        assertThat(response.sentenceTranslation()).isEqualTo(response.translation());
        assertThat(response.grammar()).isNull();
        assertThat(response.partOfSpeech()).isNull();
    }

    @Test
    void matchesContextDespiteExtraWhitespace() {
        TranslationResponse response = provider.translate(
                word("Geld", "  Danach bringt sie\n ihr Geld zur Bank. ", TargetLanguage.EN));

        assertThat(response.translation()).isEqualTo("money");
    }

    @Test
    void unknownSentenceFailsWithMockNotFound() {
        assertThatThrownBy(() -> provider.translate(word("Hund", "Der Hund bellt.", TargetLanguage.TR)))
                .isInstanceOfSatisfying(TranslationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MOCK_TRANSLATION_NOT_FOUND));
    }

    @Test
    void unknownWordInKnownSentenceFailsWithMockNotFound() {
        assertThatThrownBy(() -> provider.translate(word("zwischen", "Die Kinder spielen zwischen den Häusern.",
                TargetLanguage.TR)))
                .isInstanceOfSatisfying(TranslationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MOCK_TRANSLATION_NOT_FOUND));
    }

    @Test
    void usesItsOwnCacheNamespace() {
        assertThat(provider.cacheNamespace()).isEqualTo("mock");
    }

    @Test
    void everyWordInTheDataFileIsServedInBothLanguages() throws Exception {
        List<Map<String, Object>> data;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(MockTranslationProvider.DATA_FILE)) {
            data = JSON.readValue(in, new TypeReference<>() { });
        }

        int wordCount = 0;
        for (Map<String, Object> entry : data) {
            String sentenceText = (String) entry.get("sentence");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> words = (List<Map<String, Object>>) entry.get("words");
            for (Map<String, Object> w : words) {
                for (TargetLanguage target : TargetLanguage.values()) {
                    assertThat(provider.translate(word((String) w.get("text"), sentenceText, target)).translation())
                            .isNotBlank();
                }
                wordCount++;
            }
        }
        assertThat(wordCount).isGreaterThanOrEqualTo(20);
    }
}
