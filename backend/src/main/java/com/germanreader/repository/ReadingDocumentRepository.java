package com.germanreader.repository;

import com.germanreader.dto.document.DocumentSummary;
import com.germanreader.entity.ReadingDocument;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;


public interface ReadingDocumentRepository extends JpaRepository<ReadingDocument, UUID> {

    String SELECT_SUMMARY = """
            SELECT new com.germanreader.dto.document.DocumentSummary(d.id, d.title, d.createdAt, d.updatedAt,
                d.processingStatus, d.wordCount, d.characterCount, d.paragraphCount)
            FROM ReadingDocument d
            """;

    @Query(SELECT_SUMMARY + " WHERE d.ownerId = :ownerId ORDER BY d.createdAt DESC, d.id")
    List<DocumentSummary> findAllSummaries(@Param("ownerId") UUID ownerId);

    @Query(SELECT_SUMMARY + " WHERE d.id = :id AND d.ownerId = :ownerId")
    Optional<DocumentSummary> findSummaryById(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

    Optional<ReadingDocument> findByIdAndOwnerId(UUID id, UUID ownerId);

    boolean existsByIdAndOwnerId(UUID id, UUID ownerId);

    @Modifying
    @Query("""
            UPDATE ReadingDocument d SET d.title = :title, d.updatedAt = :updatedAt
            WHERE d.id = :id AND d.ownerId = :ownerId
            """)
    int rename(@Param("id") UUID id, @Param("ownerId") UUID ownerId,
               @Param("title") String title, @Param("updatedAt") Instant updatedAt);

    @Modifying
    @Query("DELETE FROM ReadingDocument d WHERE d.id = :id AND d.ownerId = :ownerId")
    int deleteDocumentById(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

    @Transactional
    @Modifying
    @Query("""
            UPDATE ReadingDocument d SET d.processingStatus = com.germanreader.entity.ProcessingStatus.PROCESSING
            WHERE d.id = :id AND d.processingVersion = :version
              AND d.processingStatus <> com.germanreader.entity.ProcessingStatus.COMPLETED
            """)
    int startProcessing(@Param("id") UUID id, @Param("version") int version);

    @Query("SELECT d.content FROM ReadingDocument d WHERE d.id = :id")
    Optional<String> findContentById(@Param("id") UUID id);

    @Transactional
    @Modifying
    @Query("""
            UPDATE ReadingDocument d SET d.processingStatus = com.germanreader.entity.ProcessingStatus.COMPLETED,
                d.wordCount = :words, d.characterCount = :characters, d.paragraphCount = :paragraphs,
                d.processedAt = :processedAt
            WHERE d.id = :id AND d.processingVersion = :version
            """)
    int completeProcessing(@Param("id") UUID id, @Param("version") int version, @Param("words") int words,
                           @Param("characters") int characters, @Param("paragraphs") int paragraphs,
                           @Param("processedAt") Instant processedAt);

    @Transactional
    @Modifying
    @Query("""
            UPDATE ReadingDocument d SET d.processingStatus = com.germanreader.entity.ProcessingStatus.FAILED
            WHERE d.id = :id AND d.processingVersion = :version
              AND d.processingStatus <> com.germanreader.entity.ProcessingStatus.COMPLETED
            """)
    int failProcessing(@Param("id") UUID id, @Param("version") int version);
}
