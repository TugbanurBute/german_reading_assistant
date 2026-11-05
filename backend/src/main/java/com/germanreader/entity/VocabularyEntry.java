package com.germanreader.entity;

import com.germanreader.dto.PartOfSpeech;
import com.germanreader.dto.TargetLanguage;
import com.germanreader.dto.grammar.Article;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "vocabulary_entry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // required by JPA, not for application code
public class VocabularyEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false, length = 100)
    private String originalText;

    @Column(nullable = false, updatable = false, length = 100)
    private String lemma;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false, length = 3)
    private Article article;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false, length = 20)
    private PartOfSpeech partOfSpeech;

    @Column(nullable = false, updatable = false, length = 300)
    private String translation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 2)
    private TargetLanguage targetLanguage;

    @Column(updatable = false, length = 1000)
    private String sourceSentence;

    /** A plain id, not a JPA relation: the database clears it when the text is deleted. */
    @Column(updatable = false)
    private UUID sourceDocumentId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public VocabularyEntry(String originalText, String lemma, Article article, PartOfSpeech partOfSpeech,
                           String translation, TargetLanguage targetLanguage, String sourceSentence,
                           UUID sourceDocumentId) {
        this.originalText = originalText;
        this.lemma = lemma;
        this.article = article;
        this.partOfSpeech = partOfSpeech;
        this.translation = translation;
        this.targetLanguage = targetLanguage;
        this.sourceSentence = sourceSentence;
        this.sourceDocumentId = sourceDocumentId;
        // PostgreSQL stores microseconds; truncating keeps the returned value equal to the stored one.
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
