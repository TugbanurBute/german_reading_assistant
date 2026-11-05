package com.germanreader.dto.vocabulary;

import com.germanreader.dto.PartOfSpeech;
import com.germanreader.dto.TargetLanguage;
import com.germanreader.dto.grammar.Article;
import java.time.Instant;
import java.util.UUID;

public record VocabularyResponse(
        UUID id,
        String originalText,
        String lemma,
        Article article,
        PartOfSpeech partOfSpeech,
        String translation,
        TargetLanguage targetLanguage,
        String sourceSentence,
        UUID sourceDocumentId,
        String sourceDocumentTitle,
        Instant createdAt
) {
}
