ALTER TABLE reading_document ADD COLUMN processing_started_at TIMESTAMPTZ;

CREATE INDEX ix_reading_document_processing ON reading_document (processing_started_at)
    WHERE processing_status = 'PROCESSING';
