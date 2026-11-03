package com.germanreader.service.translation.mock;

import com.germanreader.dto.PartOfSpeech;
import com.germanreader.dto.TargetLanguage;
import com.germanreader.dto.TranslationRequest;
import com.germanreader.dto.TranslationResponse;
import com.germanreader.dto.TranslationType;
import com.germanreader.dto.grammar.Grammar;
import com.germanreader.exception.ErrorCode;
import com.germanreader.exception.TranslationException;
import com.germanreader.service.translation.TranslationProvider;
import com.germanreader.util.TextNormalizer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

public class MockTranslationProvider implements TranslationProvider {

    static final String DATA_FILE = "mock/mock-translations.json";

    private final Map<String, MockSentence> sentences = new HashMap<>();
    private final Map<WordKey, MockWord> words = new HashMap<>();

    /** One sentence of the sample text with its translations and the words that can be looked up. */
    record MockSentence(String sentence, Map<TargetLanguage, String> translations, List<MockWord> words) {
    }

    record MockWord(String text, String lemma, PartOfSpeech partOfSpeech, Grammar grammar,
                    Map<TargetLanguage, String> translations) {
    }

    private record WordKey(String sentence, String word) {
    }

    public MockTranslationProvider(JsonMapper jsonMapper) {
        for (MockSentence entry : load(jsonMapper)) {
            String sentenceKey = TextNormalizer.normalize(entry.sentence());
            requireAllLanguages(entry.translations(), entry.sentence());
            if (sentences.put(sentenceKey, entry) != null) {
                throw new IllegalStateException("Duplicate mock sentence: " + entry.sentence());
            }
            for (MockWord word : entry.words()) {
                requireAllLanguages(word.translations(), word.text());
                if (!sentenceKey.contains(word.text())) {
                    throw new IllegalStateException("Mock word '%s' does not occur in: %s"
                            .formatted(word.text(), entry.sentence()));
                }
                words.put(new WordKey(sentenceKey, word.text()), word);
            }
        }
    }

    @Override
    public String cacheNamespace() {
        return "mock";
    }

    @Override
    public String promptVersion() {
        return "mock-v1";
    }

    @Override
    public TranslationResponse translate(TranslationRequest request) {
        TargetLanguage target = request.targetLanguage();
        boolean isWord = request.translationType() == TranslationType.WORD;
        String sentenceText = isWord ? request.context() : request.selectedText();

        MockSentence sentence = sentences.get(TextNormalizer.normalize(sentenceText));
        if (sentence == null) {
            throw notFound(request);
        }
        String sentenceTranslation = sentence.translations().get(target);

        if (!isWord) {
            return new TranslationResponse(request.selectedText(), sentenceTranslation, null, null, null,
                    sentenceTranslation, request.sourceLanguage(), target, TranslationType.SENTENCE);
        }

        MockWord word = words.get(new WordKey(TextNormalizer.normalize(sentence.sentence()),
                TextNormalizer.normalize(request.selectedText())));
        if (word == null) {
            throw notFound(request);
        }
        return new TranslationResponse(request.selectedText(), word.translations().get(target), word.lemma(),
                word.partOfSpeech(), word.grammar(), sentenceTranslation,
                request.sourceLanguage(), target, TranslationType.WORD);
    }

    private static TranslationException notFound(TranslationRequest request) {
        // Deliberately no user text in the message: it is logged.
        return new TranslationException(ErrorCode.MOCK_TRANSLATION_NOT_FOUND,
                "No mock entry for " + request.translationType() + " request");
    }

    private static void requireAllLanguages(Map<TargetLanguage, String> translations, String owner) {
        EnumSet<TargetLanguage> missing = EnumSet.allOf(TargetLanguage.class);
        if (translations != null) {
            translations.forEach((language, text) -> {
                if (text != null && !text.isBlank()) {
                    missing.remove(language);
                }
            });
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Mock entry '%s' is missing translations for %s".formatted(owner, missing));
        }
    }

    private static List<MockSentence> load(JsonMapper jsonMapper) {
        try (InputStream in = MockTranslationProvider.class.getClassLoader().getResourceAsStream(DATA_FILE)) {
            if (in == null) {
                throw new IllegalStateException("Mock data file not found on classpath: " + DATA_FILE);
            }
            return jsonMapper.readValue(in, new TypeReference<List<MockSentence>>() { });
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + DATA_FILE, e);
        }
    }
}
