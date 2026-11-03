package com.germanreader.service.translation.gemini;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GeminiResponseParserTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final GeminiResponseParser parser = new GeminiResponseParser(JSON);

    private static final TranslationRequest WORD_REQUEST = new TranslationRequest(
            "Häusern", "Die Kinder spielen zwischen den Häusern.", SourceLanguage.DE, TargetLanguage.TR,
            TranslationType.WORD);

    private static final TranslationRequest SENTENCE_REQUEST = new TranslationRequest(
            "Ich lese.", null, SourceLanguage.DE, TargetLanguage.EN, TranslationType.SENTENCE);

    /**
     * A complete WORD answer as the schema forces Gemini to return it: every field present, null unless
     * overridden. {@code overrides} are alternating field names and values.
     */
    private static String wordJson(Object... overrides) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (String name : List.of("lemma", "partOfSpeech", "article", "plural", "grammaticalCase", "number",
                "tense", "person", "pastParticiple", "auxiliary", "separablePrefix", "grammarDetails")) {
            fields.put(name, null);
        }
        fields.put("translation", "x");
        fields.put("sentenceTranslation", "y");
        for (int i = 0; i < overrides.length; i += 2) {
            fields.put((String) overrides[i], overrides[i + 1]);
        }
        return JSON.writeValueAsString(fields);
    }

    private static void assertInvalid(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(TranslationException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROVIDER_INVALID_RESPONSE));
    }

    @Test
    void parsesNoun() {
        TranslationResponse response = parser.parse(wordJson(
                "lemma", "Haus", "partOfSpeech", "NOUN", "article", "das", "plural", "Häuser",
                "grammaticalCase", "DATIVE", "number", "PLURAL",
                "translation", "evler", "sentenceTranslation", "Çocuklar evlerin arasında oynuyor."), WORD_REQUEST);

        assertThat(response.originalText()).isEqualTo("Häusern");
        assertThat(response.translation()).isEqualTo("evler");
        assertThat(response.lemma()).isEqualTo("Haus");
        assertThat(response.partOfSpeech()).isEqualTo(PartOfSpeech.NOUN);
        assertThat(response.grammar()).isEqualTo(
                new NounGrammar(Article.DAS, "Häuser", GrammaticalCase.DATIVE, GrammaticalNumber.PLURAL));
        assertThat(response.sentenceTranslation()).isEqualTo("Çocuklar evlerin arasında oynuyor.");
        assertThat(response.targetLanguage()).isEqualTo(TargetLanguage.TR);
    }

    @Test
    void parsesVerb() {
        TranslationResponse response = parser.parse(wordJson(
                "lemma", "gehen", "partOfSpeech", "VERB", "tense", "PERFECT", "person", "1st person singular",
                "pastParticiple", "gegangen", "auxiliary", "sein"), WORD_REQUEST);

        assertThat(response.grammar()).isEqualTo(
                new VerbGrammar(Tense.PERFECT, "1st person singular", "gegangen", Auxiliary.SEIN, null));
    }

    @Test
    void parsesOtherWordClassWithDetails() {
        TranslationResponse response = parser.parse(wordJson(
                "lemma", "wegen", "partOfSpeech", "PREPOSITION", "grammarDetails", "takes the genitive"), WORD_REQUEST);

        assertThat(response.grammar()).isEqualTo(new OtherGrammar("takes the genitive"));
    }

    @Test
    void ignoresGrammarFieldsThatDoNotBelongToTheWordClass() {
        // The model claims a verb has an article; the noun-only field must not leak into VerbGrammar.
        TranslationResponse response = parser.parse(wordJson(
                "partOfSpeech", "VERB", "article", "der", "tense", "PRESENT"), WORD_REQUEST);

        assertThat(response.grammar()).isEqualTo(new VerbGrammar(Tense.PRESENT, null, null, null, null));
    }

    @Test
    void dropsUnknownEnumValuesInsteadOfInventing() {
        TranslationResponse response = parser.parse(wordJson(
                "partOfSpeech", "NOUN", "article", "dem", "grammaticalCase", "ABLATIVE", "plural", "Häuser"),
                WORD_REQUEST);

        assertThat(response.grammar()).isEqualTo(new NounGrammar(null, "Häuser", null, null));
    }

    @Test
    void returnsNullGrammarWhenNothingIsKnown() {
        TranslationResponse response = parser.parse(wordJson("partOfSpeech", "NOUN", "plural", "  "), WORD_REQUEST);

        assertThat(response.grammar()).isNull();
    }

    @Test
    void unknownPartOfSpeechMeansNoGrammar() {
        TranslationResponse response = parser.parse(wordJson("partOfSpeech", "GERUND", "article", "das"),
                WORD_REQUEST);

        assertThat(response.partOfSpeech()).isNull();
        assertThat(response.grammar()).isNull();
    }

    @Test
    void toleratesUnexpectedExtraFields() {
        TranslationResponse response = parser.parse(wordJson("confidence", 0.9), WORD_REQUEST);

        assertThat(response.translation()).isEqualTo("x");
    }

    @Test
    void parsesSentence() {
        TranslationResponse response = parser.parse("{\"translation\": \"I am reading.\"}", SENTENCE_REQUEST);

        assertThat(response.translation()).isEqualTo("I am reading.");
        assertThat(response.sentenceTranslation()).isEqualTo("I am reading.");
        assertThat(response.translationType()).isEqualTo(TranslationType.SENTENCE);
        assertThat(response.grammar()).isNull();
    }

    @Test
    void stripsMarkdownCodeFence() {
        TranslationResponse response = parser.parse("```json\n{\"translation\": \"I am reading.\"}\n```",
                SENTENCE_REQUEST);

        assertThat(response.translation()).isEqualTo("I am reading.");
    }

    @Test
    void rejectsInvalidJson() {
        assertInvalid(() -> parser.parse("Sure! Here is the translation: evler", WORD_REQUEST));
    }

    @Test
    void rejectsEmptyText() {
        assertInvalid(() -> parser.parse("  ", SENTENCE_REQUEST));
    }

    @Test
    void rejectsMissingTranslation() {
        assertInvalid(() -> parser.parse(wordJson("translation", null), WORD_REQUEST));
    }

    @Test
    void rejectsBlankSentenceTranslationForWord() {
        assertInvalid(() -> parser.parse(wordJson("sentenceTranslation", " "), WORD_REQUEST));
    }

    @Test
    void rejectsOversizedTranslation() {
        assertInvalid(() -> parser.parse(wordJson("translation", "a".repeat(301)), WORD_REQUEST));
    }
}
