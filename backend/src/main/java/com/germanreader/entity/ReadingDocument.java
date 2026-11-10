package com.germanreader.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "reading_document")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // required by JPA, not for application code
public class ReadingDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The user the text belongs to; null only for texts from before accounts existed (see V4). */
    @Column(updatable = false)
    private UUID ownerId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ProcessingStatus processingStatus = ProcessingStatus.PENDING;

    @Column(nullable = false)
    private int processingVersion = 1;

    private Instant processingQueuedAt;

    private Instant processedAt;

    private Integer wordCount;

    private Integer characterCount;

    private Integer paragraphCount;

    @Column(insertable = false, updatable = false)
    private String builtinKey;

    public ReadingDocument(UUID ownerId, String title, String content) {
        this.ownerId = ownerId;
        this.title = title;
        this.content = content;
        this.createdAt = now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = now();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
