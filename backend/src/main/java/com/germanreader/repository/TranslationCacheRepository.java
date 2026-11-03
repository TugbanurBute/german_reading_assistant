package com.germanreader.repository;

import com.germanreader.entity.TranslationCacheEntry;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface TranslationCacheRepository extends JpaRepository<TranslationCacheEntry, Long> {

    Optional<TranslationCacheEntry> findByCacheKey(String cacheKey);

    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO translation_cache (cache_key, provider, prompt_version, translation_type,
                                           source_language, target_language, selected_text, context, response)
            VALUES (:#{#entry.cacheKey}, :#{#entry.provider}, :#{#entry.promptVersion},
                    :#{#entry.translationType.name()}, :#{#entry.sourceLanguage.name()},
                    :#{#entry.targetLanguage.name()}, :#{#entry.selectedText}, :#{#entry.context},
                    CAST(:#{#entry.response} AS jsonb))
            ON CONFLICT (cache_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("entry") TranslationCacheEntry entry);
}
