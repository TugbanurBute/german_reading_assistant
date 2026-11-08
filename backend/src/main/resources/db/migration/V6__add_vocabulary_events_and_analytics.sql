-- Learning analytics over Kafka (topic vocabulary.events).
--
-- Outbox: like reading_document (V5), the vocabulary_entry row is its own outbox. A new entry gets an event id and
-- event_published_at = NULL in the same INSERT, so an event exists exactly when the entry was committed; the
-- VocabularyEventRelay publishes it later and sets event_published_at. Returning an existing duplicate inserts
-- nothing, so it produces no event.
ALTER TABLE vocabulary_entry
    ADD COLUMN saved_event_id     UUID        NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN event_published_at TIMESTAMPTZ;

-- Words saved before accounts existed have no user to count them for: never publish those.
UPDATE vocabulary_entry SET event_published_at = now() WHERE owner_id IS NULL;
-- All other existing words are published once, so the statistics include them from the start.

CREATE INDEX ix_vocabulary_entry_unpublished ON vocabulary_entry (created_at) WHERE event_published_at IS NULL;

-- Idempotent consumer: an event id is recorded in the same transaction that counts it, so a redelivered or
-- replayed event is counted at most once.
CREATE TABLE processed_vocabulary_event (
    event_id     UUID        PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);

-- Words saved per user and day. The day is the event time in the configured analytics time zone.
CREATE TABLE vocabulary_daily_stats (
    user_id     UUID    NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    day         DATE    NOT NULL,
    saved_count INTEGER NOT NULL CHECK (saved_count > 0),
    PRIMARY KEY (user_id, day)
);
