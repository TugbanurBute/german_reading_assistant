ALTER TABLE reading_document
    ADD COLUMN processing_status    VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
    ADD COLUMN processing_version   INTEGER      NOT NULL DEFAULT 1,
    ADD COLUMN processing_queued_at TIMESTAMPTZ,
    ADD COLUMN processed_at         TIMESTAMPTZ,
    ADD COLUMN word_count           INTEGER,
    ADD COLUMN character_count      INTEGER,
    ADD COLUMN paragraph_count      INTEGER,
    ADD CONSTRAINT ck_reading_document_processing_status
        CHECK (processing_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'));

CREATE INDEX ix_reading_document_unqueued ON reading_document (created_at) WHERE processing_queued_at IS NULL;
