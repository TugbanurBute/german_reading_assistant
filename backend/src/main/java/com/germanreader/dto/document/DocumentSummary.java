package com.germanreader.dto.document;

import com.germanreader.entity.ProcessingStatus;
import java.time.Instant;
import java.util.UUID;

public record DocumentSummary(
        UUID id,
        String title,
        Instant createdAt,
        Instant updatedAt,
        ProcessingStatus processingStatus,
        Integer wordCount,
        Integer characterCount,
        Integer paragraphCount
) {
}
