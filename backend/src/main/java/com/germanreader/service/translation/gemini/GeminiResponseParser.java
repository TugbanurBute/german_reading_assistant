package com.germanreader.service.translation.gemini;

import com.germanreader.dto.PartOfSpeech;
import com.germanreader.dto.TranslationRequest;
import com.germanreader.dto.TranslationResponse;
import com.germanreader.dto.TranslationType;
import com.germanreader.dto.grammar.Article;
import com.germanreader.dto.grammar.Auxiliary;
import com.germanreader.dto.grammar.Grammar;
import com.germanreader.dto.grammar.GrammaticalCase;
import com.germanreader.dto.grammar.GrammaticalNumber;
import com.germanreader.dto.grammar.NounGrammar;
import com.germanreader.dto.grammar.OtherGrammar;
import com.germanreader.dto.grammar.Tense;
import com.germanreader.dto.grammar.VerbGrammar;
import com.germanreader.exception.ErrorCode;
import com.germanreader.exception.TranslationException;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

final class GeminiResponseParser {

    private static final int MAX_TRANSLATION_LENGTH = 300;
    private static final int MAX_SENTENCE_LENGTH = 2000;
    private static final int MAX_FIELD_LENGTH = 200;

    record WordResult(String lemma, String partOfSpeech, String article, String plural, String grammaticalCase,
                      String number, String tense, String person, String pastParticiple, String auxiliary,
                      String separablePrefix, String grammarDetails, String translation, String sentenceTranslation) {
    }

    record SentenceResult(String translation) {
    }

    private final JsonMapper jsonMapper;

    GeminiResponseParser(JsonMapper jsonMapper) {
        // Extra fields from a future model version should not break parsing.
        this.jsonMapper = jsonMapper.rebuild()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    TranslationResponse parse(String json, TranslationRequest request) {
        return request.translationType() == TranslationType.WORD
                ? parseWord(json, request)
                : parseSentence(json, request);
    }

    private TranslationResponse parseSentence(String json, TranslationRequest request) {
        SentenceResult result = read(json, SentenceResult.class);
        String translation = required(result.translation(), MAX_SENTENCE_LENGTH, "translation");
        return new TranslationResponse(request.selectedText(), translation, null, null, null, translation,
                request.sourceLanguage(), request.targetLanguage(), TranslationType.SENTENCE);
    }

    private TranslationResponse parseWord(String json, TranslationRequest request) {
        WordResult result = read(json, WordResult.class);
        String translation = required(result.translation(), MAX_TRANSLATION_LENGTH, "translation");
        String sentenceTranslation = required(result.sentenceTranslation(), MAX_SENTENCE_LENGTH, "sentenceTranslation");
        PartOfSpeech partOfSpeech = enumValue(PartOfSpeech.class, result.partOfSpeech());

        return new TranslationResponse(request.selectedText(), translation, optional(result.lemma()), partOfSpeech,
                grammar(partOfSpeech, result), sentenceTranslation,
                request.sourceLanguage(), request.targetLanguage(), TranslationType.WORD);
    }

    private static Grammar grammar(PartOfSpeech partOfSpeech, WordResult r) {
        if (partOfSpeech == null) {
            return null;
        }
        return switch (partOfSpeech) {
            case NOUN -> nullIfEmpty(new NounGrammar(
                    byValue(Article.values(), Article::value, r.article()),
                    optional(r.plural()),
                    enumValue(GrammaticalCase.class, r.grammaticalCase()),
                    enumValue(GrammaticalNumber.class, r.number())));
            case VERB -> nullIfEmpty(new VerbGrammar(
                    enumValue(Tense.class, r.tense()),
                    optional(r.person()),
                    optional(r.pastParticiple()),
                    byValue(Auxiliary.values(), Auxiliary::value, r.auxiliary()),
                    optional(r.separablePrefix())));
            default -> {
                String details = optional(r.grammarDetails());
                yield details == null ? null : new OtherGrammar(details);
            }
        };
    }

    private static Grammar nullIfEmpty(Grammar grammar) {
        boolean empty = switch (grammar) {
            case NounGrammar n -> allNull(n.article(), n.plural(), n.grammaticalCase(), n.number());
            case VerbGrammar v -> allNull(v.tense(), v.person(), v.pastParticiple(), v.auxiliary(), v.separablePrefix());
            case OtherGrammar o -> o.details() == null;
        };
        return empty ? null : grammar;
    }

    private static boolean allNull(Object... values) {
        return Stream.of(values).allMatch(Objects::isNull);
    }

    private <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            throw invalid("Empty response text");
        }
        try {
            T result = jsonMapper.readValue(stripCodeFence(json), type);
            if (result == null) {
                throw invalid("Response JSON was null");
            }
            return result;
        } catch (JacksonException e) {
            throw new TranslationException(ErrorCode.PROVIDER_INVALID_RESPONSE,
                    "Gemini response is not valid JSON for " + type.getSimpleName(), e);
        }
    }

    private static String stripCodeFence(String text) {
        String trimmed = text.strip();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "");
        }
        return trimmed;
    }

    private static String required(String value, int maxLength, String field) {
        String cleaned = value == null ? null : value.strip();
        if (cleaned == null || cleaned.isEmpty()) {
            throw invalid("Missing required field: " + field);
        }
        if (cleaned.length() > maxLength) {
            throw invalid("Field too long: " + field);
        }
        return cleaned;
    }

    private static String optional(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.strip();
        return cleaned.isEmpty() || cleaned.length() > MAX_FIELD_LENGTH ? null : cleaned;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return byValue(type.getEnumConstants(), Enum::name, value);
    }

    private static <E> E byValue(E[] constants, Function<E, String> label, String value) {
        if (value == null) {
            return null;
        }
        String wanted = value.strip();
        return Arrays.stream(constants)
                .filter(constant -> label.apply(constant).equalsIgnoreCase(wanted))
                .findFirst()
                .orElse(null);
    }

    private static TranslationException invalid(String message) {
        return new TranslationException(ErrorCode.PROVIDER_INVALID_RESPONSE, message);
    }
}
