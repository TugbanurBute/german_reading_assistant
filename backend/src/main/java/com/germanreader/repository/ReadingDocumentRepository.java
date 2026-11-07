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

public interface ReadingDocumentRepository extends JpaRepository<ReadingDocument, UUID> {

    /** Metadata only: the content column is never read for the library list. */
    @Query("""
            SELECT new com.germanreader.dto.document.DocumentSummary(d.id, d.title, d.createdAt, d.updatedAt)
            FROM ReadingDocument d
            ORDER BY d.createdAt DESC, d.id
            """)
    List<DocumentSummary> findAllSummaries();

    @Query("""
            SELECT new com.germanreader.dto.document.DocumentSummary(d.id, d.title, d.createdAt, d.updatedAt)
            FROM ReadingDocument d
            WHERE d.id = :id
            """)
    Optional<DocumentSummary> findSummaryById(@Param("id") UUID id);

    /** One UPDATE statement that never reads the content; returns 0 when no such document exists. */
    @Modifying
    @Query("UPDATE ReadingDocument d SET d.title = :title, d.updatedAt = :updatedAt WHERE d.id = :id")
    int rename(@Param("id") UUID id, @Param("title") String title, @Param("updatedAt") Instant updatedAt);

    /** One DELETE statement; returns 0 when no such document exists. */
    @Modifying
    @Query("DELETE FROM ReadingDocument d WHERE d.id = :id")
    int deleteDocumentById(@Param("id") UUID id);
}
