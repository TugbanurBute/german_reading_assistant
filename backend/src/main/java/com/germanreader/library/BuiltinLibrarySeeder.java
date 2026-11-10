package com.germanreader.library;

import com.germanreader.library.BuiltinCatalog.BuiltinText;
import com.germanreader.processing.DocumentStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class BuiltinLibrarySeeder implements ApplicationRunner {

    private static final String UPSERT = """
            INSERT INTO reading_document (id, owner_id, title, content, created_at, updated_at,
                                          processing_status, processing_version, processing_queued_at, processed_at,
                                          word_count, character_count, paragraph_count,
                                          builtin_key, cefr_level, description)
            VALUES (gen_random_uuid(), NULL, ?, ?, now(), now(), 'COMPLETED', 1, now(), now(), ?, ?, ?, ?, ?, ?)
            ON CONFLICT (builtin_key) WHERE builtin_key IS NOT NULL DO UPDATE SET
                title = EXCLUDED.title, content = EXCLUDED.content, description = EXCLUDED.description,
                cefr_level = EXCLUDED.cefr_level, word_count = EXCLUDED.word_count,
                character_count = EXCLUDED.character_count, paragraph_count = EXCLUDED.paragraph_count,
                processing_version = reading_document.processing_version + 1, updated_at = now(), processed_at = now()
            WHERE (reading_document.title, reading_document.content, reading_document.description, reading_document.cefr_level)
                IS DISTINCT FROM (EXCLUDED.title, EXCLUDED.content, EXCLUDED.description, EXCLUDED.cefr_level)
            """;

    private final BuiltinCatalog catalog;
    private final JdbcTemplate jdbc;

    public BuiltinLibrarySeeder(BuiltinCatalog catalog, JdbcTemplate jdbc) {
        this.catalog = catalog;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        int changed = seed();
        if (changed > 0) {
            log.info("Built-in library: {} of {} texts added or updated", changed, catalog.texts().size());
        }
    }

    public int seed() {
        int changed = 0;
        for (BuiltinText text : catalog.texts()) {
            DocumentStats stats = DocumentStats.of(text.content());
            changed += jdbc.update(UPSERT, text.title(), text.content(), stats.words(), stats.characters(),
                    stats.paragraphs(), text.key(), text.level().name(), text.description());
        }
        return changed;
    }
}
