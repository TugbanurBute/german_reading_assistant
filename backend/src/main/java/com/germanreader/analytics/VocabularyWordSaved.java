package com.germanreader.analytics;

import java.time.Instant;
import java.util.UUID;

public record VocabularyWordSaved(UUID eventId, String eventType, UUID entryId, UUID userId, Instant occurredAt) {

    public static final String TYPE = "VocabularyWordSaved";

    public VocabularyWordSaved(UUID eventId, UUID entryId, UUID userId, Instant occurredAt) {
        this(eventId, TYPE, entryId, userId, occurredAt);
    }
}
