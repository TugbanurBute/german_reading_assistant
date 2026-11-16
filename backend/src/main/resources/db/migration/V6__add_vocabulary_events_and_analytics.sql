ALTER TABLE vocabulary_entry
    ADD COLUMN saved_event_id     UUID        NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN event_published_at TIMESTAMPTZ;

UPDATE vocabulary_entry SET event_published_at = now() WHERE owner_id IS NULL;

CREATE INDEX ix_vocabulary_entry_unpublished ON vocabulary_entry (created_at) WHERE event_published_at IS NULL;

CREATE TABLE processed_vocabulary_event (
    event_id     UUID        PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE vocabulary_daily_stats (
    user_id     UUID    NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    day         DATE    NOT NULL,
    saved_count INTEGER NOT NULL CHECK (saved_count > 0),
    PRIMARY KEY (user_id, day)
);
