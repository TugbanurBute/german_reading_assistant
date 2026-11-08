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

/**
 * Every query that the API uses takes the owner: another user's text is indistinguishable from a missing one.
 * (The inherited findById/existsById ignore the owner and are only for tests.)
 */
public interface ReadingDocumentRepository extends JpaRepository<ReadingDocument, UUID> {

    /** Metadata only: the content column is never read for the library list. */
    @Query("""
            SELECT new com.germanreader.dto.document.DocumentSummary(d.id, d.title, d.createdAt, d.updatedAt)
            FROM ReadingDocument d
            WHERE d.ownerId = :ownerId
            ORDER BY d.createdAt DESC, d.id
            """)
    List<DocumentSummary> findAllSummaries(@Param("ownerId") UUID ownerId);

    @Query("""
            SELECT new com.germanreader.dto.document.DocumentSummary(d.id, d.title, d.createdAt, d.updatedAt)
            FROM ReadingDocument d
            WHERE d.id = :id AND d.ownerId = :ownerId
            """)
    Optional<DocumentSummary> findSummaryById(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

    Optional<ReadingDocument> findByIdAndOwnerId(UUID id, UUID ownerId);

    boolean existsByIdAndOwnerId(UUID id, UUID ownerId);

    /** One UPDATE statement that never reads the content; returns 0 when the owner has no such document. */
    @Modifying
    @Query("""
            UPDATE ReadingDocument d SET d.title = :title, d.updatedAt = :updatedAt
            WHERE d.id = :id AND d.ownerId = :ownerId
            """)
    int rename(@Param("id") UUID id, @Param("ownerId") UUID ownerId,
               @Param("title") String title, @Param("updatedAt") Instant updatedAt);

    /** One DELETE statement; returns 0 when the owner has no such document. */
    @Modifying
    @Query("DELETE FROM ReadingDocument d WHERE d.id = :id AND d.ownerId = :ownerId")
    int deleteDocumentById(@Param("id") UUID id, @Param("ownerId") UUID ownerId);
}
