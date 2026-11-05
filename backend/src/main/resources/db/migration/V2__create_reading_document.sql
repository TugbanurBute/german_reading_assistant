-- Texts saved to the personal reading library (local, single-user; no owner column yet).

CREATE TABLE reading_document (
    id         UUID          PRIMARY KEY,
    title      VARCHAR(200)  NOT NULL,
    content    TEXT          NOT NULL,
    created_at TIMESTAMPTZ   NOT NULL,
    updated_at TIMESTAMPTZ   NOT NULL,

    CONSTRAINT ck_reading_document_title_not_blank CHECK (btrim(title) <> ''),
    CONSTRAINT ck_reading_document_content_not_blank CHECK (btrim(content) <> '')
);

-- The library lists newest first.
CREATE INDEX ix_reading_document_created_at ON reading_document (created_at DESC);
