package com.germanreader.dto;

import com.germanreader.dto.grammar.Grammar;

/**
 * Result of a translation. Both providers (mock and Gemini) produce exactly this shape.
 *
 * @param originalText        the text that was translated, as sent by the client
 * @param translation         meaning of the word in this context (WORD) or the translated sentence (SENTENCE)
 * @param lemma               dictionary form: noun singular, verb infinitive, adjective base form
 * @param partOfSpeech        word class, or {@code null} if unknown
 * @param grammar             word-class specific details, or {@code null} if none are known
 * @param sentenceTranslation natural translation of the whole sentence
 */
public record TranslationResponse(
        String originalText,
        String translation,
        String lemma,
        PartOfSpeech partOfSpeech,
        Grammar grammar,
        String sentenceTranslation,
        SourceLanguage sourceLanguage,
        TargetLanguage targetLanguage,
        TranslationType translationType
) {

    public TranslationResponse withOriginalText(String text) {
        return new TranslationResponse(text, translation, lemma, partOfSpeech, grammar, sentenceTranslation,
                sourceLanguage, targetLanguage, translationType);
    }
}
