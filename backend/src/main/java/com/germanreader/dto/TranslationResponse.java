package com.germanreader.dto;

import com.germanreader.dto.grammar.Grammar;

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
