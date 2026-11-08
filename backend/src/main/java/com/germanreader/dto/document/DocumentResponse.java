package com.germanreader.dto.document;

import com.germanreader.entity.ProcessingStatus;
import com.germanreader.entity.ReadingDocument;
import java.time.Instant;
import java.util.UUID;

public record DocumentResponse(
        UUID id,
        String title,
        String content,
        Instant createdAt,
        Instant updatedAt,
        ProcessingStatus processingStatus,
        Integer wordCount,
        Integer characterCount,
        Integer paragraphCount
) {

    public static DocumentResponse from(ReadingDocument document) {
        return new DocumentResponse(document.getId(), document.getTitle(), document.getContent(),
                document.getCreatedAt(), document.getUpdatedAt(), document.getProcessingStatus(),
                document.getWordCount(), document.getCharacterCount(), document.getParagraphCount());
    }
}
