CREATE TABLE app_user (
    id            UUID          PRIMARY KEY,
    email         VARCHAR(254)  NOT NULL,
    password_hash VARCHAR(100)  NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL,

    CONSTRAINT ck_app_user_email_normalized CHECK (email <> '' AND email = lower(btrim(email)))
);

CREATE UNIQUE INDEX ux_app_user_email ON app_user (email);

ALTER TABLE reading_document ADD COLUMN owner_id UUID REFERENCES app_user (id) ON DELETE CASCADE;
DROP INDEX ix_reading_document_created_at;
CREATE INDEX ix_reading_document_owner_created_at ON reading_document (owner_id, created_at DESC);

ALTER TABLE vocabulary_entry ADD COLUMN owner_id UUID REFERENCES app_user (id) ON DELETE CASCADE;
DROP INDEX ix_vocabulary_entry_created_at;
CREATE INDEX ix_vocabulary_entry_owner_created_at ON vocabulary_entry (owner_id, created_at DESC);

DROP INDEX ux_vocabulary_entry_meaning;
CREATE UNIQUE INDEX ux_vocabulary_entry_meaning
    ON vocabulary_entry (owner_id, lower(lemma), target_language, lower(translation));
