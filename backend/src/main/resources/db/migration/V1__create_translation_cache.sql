-- Persistent cache of successful translations.
-- One row per unique (provider namespace, prompt version, type, languages, normalized text, normalized context).
-- Only validated, successful responses are stored; failures are never cached.

CREATE TABLE translation_cache (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- SHA-256 hex of all identity fields below. The UNIQUE constraint is what makes concurrent
    -- inserts for the same request safe: the second insert conflicts instead of duplicating.
    cache_key        CHAR(64)      NOT NULL,

    -- Identity fields, stored readably for debugging and future cleanup queries.
    -- provider is a namespace such as 'mock' or 'gemini:<model>', so mock rows never serve Gemini requests.
    provider         VARCHAR(100)  NOT NULL,
    prompt_version   VARCHAR(20)   NOT NULL,
    translation_type VARCHAR(20)   NOT NULL,
    source_language  VARCHAR(5)    NOT NULL,
    target_language  VARCHAR(5)    NOT NULL,
    selected_text    VARCHAR(1000) NOT NULL,
    context          VARCHAR(1000),

    -- The TranslationResponse exactly as returned to the client.
    response         JSONB         NOT NULL,

    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uq_translation_cache_key UNIQUE (cache_key),
    CONSTRAINT ck_translation_cache_type CHECK (translation_type IN ('WORD', 'SENTENCE'))
);
