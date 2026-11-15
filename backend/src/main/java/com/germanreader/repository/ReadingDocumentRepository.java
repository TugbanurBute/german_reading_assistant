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

    boolean existsByIdAndBuiltinKeyIsNotNull(UUID id);

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

    /*
     * Processing. A claim stores its start time in processing_started_at; that value is the claim's fencing token.
     * Completing or releasing needs the token, so a worker whose claim expired and was taken over writes nothing.
     */

    /** Claims a PENDING document, or one whose claim has expired. Never FAILED ones (see requeueFailed). */
    @Transactional
    @Modifying
    @Query("""
            UPDATE ReadingDocument d SET d.processingStatus = com.germanreader.entity.ProcessingStatus.PROCESSING,
                d.processingStartedAt = :claimedAt
            WHERE d.id = :id AND d.processingVersion = :version
              AND (d.processingStatus = com.germanreader.entity.ProcessingStatus.PENDING
                   OR (d.processingStatus = com.germanreader.entity.ProcessingStatus.PROCESSING
                       AND (d.processingStartedAt IS NULL OR d.processingStartedAt < :staleBefore)))
            """)
    int startProcessing(@Param("id") UUID id, @Param("version") int version, @Param("claimedAt") Instant claimedAt,
                        @Param("staleBefore") Instant staleBefore);

    @Query("SELECT d.content FROM ReadingDocument d WHERE d.id = :id")
    Optional<String> findContentById(@Param("id") UUID id);

    @Transactional
    @Modifying
    @Query("""
            UPDATE ReadingDocument d SET d.processingStatus = com.germanreader.entity.ProcessingStatus.COMPLETED,
                d.wordCount = :words, d.characterCount = :characters, d.paragraphCount = :paragraphs,
                d.processedAt = :processedAt
            WHERE d.id = :id AND d.processingVersion = :version
              AND d.processingStatus = com.germanreader.entity.ProcessingStatus.PROCESSING
              AND d.processingStartedAt = :claimedAt
            """)
    int completeProcessing(@Param("id") UUID id, @Param("version") int version, @Param("claimedAt") Instant claimedAt,
                           @Param("words") int words, @Param("characters") int characters,
                           @Param("paragraphs") int paragraphs, @Param("processedAt") Instant processedAt);

    /** Gives the claim back after an error, so the retry of the same message can claim it again. */
    @Transactional
    @Modifying
    @Query("""
            UPDATE ReadingDocument d SET d.processingStatus = com.germanreader.entity.ProcessingStatus.PENDING,
                d.processingStartedAt = NULL
            WHERE d.id = :id AND d.processingVersion = :version
              AND d.processingStatus = com.germanreader.entity.ProcessingStatus.PROCESSING
              AND d.processingStartedAt = :claimedAt
            """)
    int releaseProcessing(@Param("id") UUID id, @Param("version") int version, @Param("claimedAt") Instant claimedAt);

    /**
     * After the retries are used up. Only an unclaimed document: another worker's live claim is left alone, and a
     * claim that could not be released expires and is re-queued by the relay.
     */
    @Transactional
    @Modifying
    @Query("""
            UPDATE ReadingDocument d SET d.processingStatus = com.germanreader.entity.ProcessingStatus.FAILED
            WHERE d.id = :id AND d.processingVersion = :version
              AND d.processingStatus = com.germanreader.entity.ProcessingStatus.PENDING
            """)
    int failProcessing(@Param("id") UUID id, @Param("version") int version);

    /**
     * The explicit way back from FAILED: a new version, so delayed copies of the old messages are ignored, and back
     * into the outbox for the relay.
     */
    @Transactional
    @Modifying
    @Query("""
            UPDATE ReadingDocument d SET d.processingStatus = com.germanreader.entity.ProcessingStatus.PENDING,
                d.processingVersion = d.processingVersion + 1, d.processingQueuedAt = NULL, d.processingStartedAt = NULL
            WHERE d.id = :id AND d.processingStatus = com.germanreader.entity.ProcessingStatus.FAILED
            """)
    int requeueFailed(@Param("id") UUID id);
}
