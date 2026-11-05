package com.germanreader.dto.document;

import java.time.Instant;
import java.util.UUID;

/** A library entry without the content. */
public record DocumentSummary(UUID id, String title, Instant createdAt, Instant updatedAt) {
}
