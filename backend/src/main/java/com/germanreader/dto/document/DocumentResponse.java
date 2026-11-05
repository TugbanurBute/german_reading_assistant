package com.germanreader.dto.document;

import com.germanreader.entity.ReadingDocument;
import java.time.Instant;
import java.util.UUID;

public record DocumentResponse(UUID id, String title, String content, Instant createdAt, Instant updatedAt) {

    public static DocumentResponse from(ReadingDocument document) {
        return new DocumentResponse(document.getId(), document.getTitle(), document.getContent(),
                document.getCreatedAt(), document.getUpdatedAt());
    }
}
