package com.germanreader.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@ValidTranslationRequest
public record TranslationRequest(
        @NotBlank
        @Size(max = TranslationRequest.MAX_SENTENCE_LENGTH)
        String selectedText,

        @Size(max = TranslationRequest.MAX_SENTENCE_LENGTH)
        String context,

        @NotNull
        SourceLanguage sourceLanguage,

        @NotNull
        TargetLanguage targetLanguage,

        @NotNull
        TranslationType translationType
) {
    public static final int MAX_WORD_LENGTH = 100;
    public static final int MAX_SENTENCE_LENGTH = 1000;
}
