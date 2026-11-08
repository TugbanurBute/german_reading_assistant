-- Accounts, and an owner for every text and saved word.

CREATE TABLE app_user (
    id            UUID          PRIMARY KEY,
    -- Stored trimmed and lower-case, so "Anna@Example.com" and "anna@example.com" are one account.
    email         VARCHAR(254)  NOT NULL,
    -- BCrypt hash ($2a$12$…, 60 characters); the password itself is never stored.
    password_hash VARCHAR(100)  NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL,

    CONSTRAINT ck_app_user_email_normalized CHECK (email <> '' AND email = lower(btrim(email)))
);

CREATE UNIQUE INDEX ux_app_user_email ON app_user (email);

-- owner_id stays NULL for rows created before accounts existed. Nobody can see those rows, and they are
-- not deleted: they are handed to the account named by LEGACY_DATA_OWNER_EMAIL when it registers or logs in.
ALTER TABLE reading_document ADD COLUMN owner_id UUID REFERENCES app_user (id) ON DELETE CASCADE;
DROP INDEX ix_reading_document_created_at;
CREATE INDEX ix_reading_document_owner_created_at ON reading_document (owner_id, created_at DESC);

ALTER TABLE vocabulary_entry ADD COLUMN owner_id UUID REFERENCES app_user (id) ON DELETE CASCADE;
DROP INDEX ix_vocabulary_entry_created_at;
CREATE INDEX ix_vocabulary_entry_owner_created_at ON vocabulary_entry (owner_id, created_at DESC);

-- One entry per meaning and per user (two users may both save "Bank / bench").
DROP INDEX ux_vocabulary_entry_meaning;
CREATE UNIQUE INDEX ux_vocabulary_entry_meaning
    ON vocabulary_entry (owner_id, lower(lemma), target_language, lower(translation));
