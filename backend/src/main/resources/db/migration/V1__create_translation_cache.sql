CREATE TABLE translation_cache (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cache_key        CHAR(64)      NOT NULL,
    provider         VARCHAR(100)  NOT NULL,
    prompt_version   VARCHAR(20)   NOT NULL,
    translation_type VARCHAR(20)   NOT NULL,
    source_language  VARCHAR(5)    NOT NULL,
    target_language  VARCHAR(5)    NOT NULL,
    selected_text    VARCHAR(1000) NOT NULL,
    context          VARCHAR(1000),
    response         JSONB         NOT NULL,

    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uq_translation_cache_key UNIQUE (cache_key),
    CONSTRAINT ck_translation_cache_type CHECK (translation_type IN ('WORD', 'SENTENCE'))
);
