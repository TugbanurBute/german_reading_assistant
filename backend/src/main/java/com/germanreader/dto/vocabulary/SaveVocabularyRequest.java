package com.germanreader.dto.vocabulary;

import com.germanreader.dto.PartOfSpeech;
import com.germanreader.dto.TargetLanguage;
import com.germanreader.dto.TranslationRequest;
import com.germanreader.dto.grammar.Article;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record SaveVocabularyRequest(
        @NotBlank @Size(max = TranslationRequest.MAX_WORD_LENGTH) String originalText,
        @Size(max = TranslationRequest.MAX_WORD_LENGTH) String lemma,
        Article article,
        PartOfSpeech partOfSpeech,
        @NotBlank @Size(max = SaveVocabularyRequest.MAX_TRANSLATION_LENGTH) String translation,
        @NotNull TargetLanguage targetLanguage,
        @Size(max = TranslationRequest.MAX_SENTENCE_LENGTH) String sourceSentence,
        UUID sourceDocumentId
) {
    public static final int MAX_TRANSLATION_LENGTH = 300;
}
