package com.germanreader.repository;

import com.germanreader.dto.TargetLanguage;
import com.germanreader.dto.vocabulary.VocabularyResponse;
import com.germanreader.entity.VocabularyEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Every query that the API uses takes the owner, like ReadingDocumentRepository. */
public interface VocabularyEntryRepository extends JpaRepository<VocabularyEntry, UUID> {

    /** The source title only comes from the same owner's text. */
    String SELECT_RESPONSE = """
            SELECT new com.germanreader.dto.vocabulary.VocabularyResponse(
                v.id, v.originalText, v.lemma, v.article, v.partOfSpeech, v.translation, v.targetLanguage,
                v.sourceSentence, v.sourceDocumentId, d.title, v.createdAt)
            FROM VocabularyEntry v
            LEFT JOIN ReadingDocument d ON d.id = v.sourceDocumentId AND d.ownerId = v.ownerId
            """;

    @Query(SELECT_RESPONSE + " WHERE v.ownerId = :ownerId ORDER BY v.createdAt DESC, v.id")
    List<VocabularyResponse> findAllResponses(@Param("ownerId") UUID ownerId);

    @Query(SELECT_RESPONSE + " WHERE v.id = :id AND v.ownerId = :ownerId")
    Optional<VocabularyResponse> findResponseById(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

    /** The owner's entry for this meaning, compared the same way as the unique index ux_vocabulary_entry_meaning. */
    @Query("""
            SELECT v.id FROM VocabularyEntry v
            WHERE v.ownerId = :ownerId
              AND lower(v.lemma) = lower(:lemma)
              AND v.targetLanguage = :targetLanguage
              AND lower(v.translation) = lower(:translation)
            """)
    Optional<UUID> findMeaning(@Param("ownerId") UUID ownerId,
                               @Param("lemma") String lemma,
                               @Param("targetLanguage") TargetLanguage targetLanguage,
                               @Param("translation") String translation);

    /** One DELETE statement; returns 0 when the owner has no such entry. */
    @Modifying
    @Query("DELETE FROM VocabularyEntry v WHERE v.id = :id AND v.ownerId = :ownerId")
    int deleteEntryById(@Param("id") UUID id, @Param("ownerId") UUID ownerId);
}
