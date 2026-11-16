CREATE TABLE vocabulary_entry (
    id                 UUID           PRIMARY KEY,
    original_text      VARCHAR(100)   NOT NULL,
    lemma              VARCHAR(100)   NOT NULL,
    article            VARCHAR(3),
    part_of_speech     VARCHAR(20),
    translation        VARCHAR(300)   NOT NULL,
    target_language    VARCHAR(2)     NOT NULL,
    source_sentence    VARCHAR(1000),
    source_document_id UUID           REFERENCES reading_document (id) ON DELETE SET NULL,
    created_at         TIMESTAMPTZ    NOT NULL,

    CONSTRAINT ck_vocabulary_entry_original_text_not_blank CHECK (btrim(original_text) <> ''),
    CONSTRAINT ck_vocabulary_entry_lemma_not_blank CHECK (btrim(lemma) <> ''),
    CONSTRAINT ck_vocabulary_entry_translation_not_blank CHECK (btrim(translation) <> ''),
    CONSTRAINT ck_vocabulary_entry_article CHECK (article IN ('DER', 'DIE', 'DAS')),
    CONSTRAINT ck_vocabulary_entry_target_language CHECK (target_language IN ('TR', 'EN'))
);

CREATE UNIQUE INDEX ux_vocabulary_entry_meaning
    ON vocabulary_entry (lower(lemma), target_language, lower(translation));

CREATE INDEX ix_vocabulary_entry_created_at ON vocabulary_entry (created_at DESC);
CREATE INDEX ix_vocabulary_entry_source_document_id ON vocabulary_entry (source_document_id);
