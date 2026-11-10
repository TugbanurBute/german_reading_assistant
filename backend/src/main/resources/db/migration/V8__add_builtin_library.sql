-- Built-in reading texts (A1-B1), stored once and shared by all users. They have no owner, so the personal
-- document endpoints (which always filter by owner_id) can never show, rename or delete them.
-- BuiltinLibrarySeeder inserts or updates them by builtin_key at startup.
ALTER TABLE reading_document
    ADD COLUMN builtin_key VARCHAR(80),
    ADD COLUMN cefr_level  VARCHAR(2),
    ADD COLUMN description VARCHAR(300),
    ADD CONSTRAINT ck_reading_document_builtin CHECK (
        (builtin_key IS NULL AND cefr_level IS NULL AND description IS NULL)
        OR (builtin_key IS NOT NULL AND owner_id IS NULL AND cefr_level IN ('A1', 'A2', 'B1')));

CREATE UNIQUE INDEX ux_reading_document_builtin_key ON reading_document (builtin_key) WHERE builtin_key IS NOT NULL;
