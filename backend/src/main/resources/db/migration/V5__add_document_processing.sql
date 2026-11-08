-- Asynchronous document analysis (RabbitMQ). The document row itself is the outbox: processing_queued_at IS NULL
-- means "a DocumentProcessingRequested message still has to be published". It is written in the same transaction
-- as the document, so no message is ever published for an uncommitted document, and none is lost if RabbitMQ
-- is down (DocumentProcessingRelay publishes it later).

ALTER TABLE reading_document
    ADD COLUMN processing_status    VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
    -- Messages carry this version; a message for an older version is stale and ignored.
    ADD COLUMN processing_version   INTEGER      NOT NULL DEFAULT 1,
    ADD COLUMN processing_queued_at TIMESTAMPTZ,
    ADD COLUMN processed_at         TIMESTAMPTZ,
    ADD COLUMN word_count           INTEGER,
    ADD COLUMN character_count      INTEGER,
    ADD COLUMN paragraph_count      INTEGER,
    ADD CONSTRAINT ck_reading_document_processing_status
        CHECK (processing_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'));

-- Existing documents start as PENDING with no message yet, so the relay queues them for analysis too.

-- The relay only looks at documents whose message is not yet published.
CREATE INDEX ix_reading_document_unqueued ON reading_document (created_at) WHERE processing_queued_at IS NULL;
