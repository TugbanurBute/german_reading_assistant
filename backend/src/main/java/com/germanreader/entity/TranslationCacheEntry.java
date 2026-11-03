package com.germanreader.entity;

import com.germanreader.dto.SourceLanguage;
import com.germanreader.dto.TargetLanguage;
import com.germanreader.dto.TranslationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "translation_cache")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // required by JPA, not for application code
public class TranslationCacheEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR) // the column is CHAR(64), not VARCHAR
    @Column(length = 64, nullable = false, updatable = false)
    private String cacheKey;

    @Column(nullable = false, updatable = false)
    private String provider;

    @Column(nullable = false, updatable = false)
    private String promptVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private TranslationType translationType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private SourceLanguage sourceLanguage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private TargetLanguage targetLanguage;

    @Column(nullable = false, updatable = false)
    private String selectedText;

    @Column(updatable = false)
    private String context;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private String response;

    @Column(insertable = false, updatable = false) // filled by the database default now()
    private Instant createdAt;

    public TranslationCacheEntry(String cacheKey, String provider, String promptVersion,
                                 TranslationType translationType, SourceLanguage sourceLanguage,
                                 TargetLanguage targetLanguage, String selectedText, String context,
                                 String response) {
        this.cacheKey = cacheKey;
        this.provider = provider;
        this.promptVersion = promptVersion;
        this.translationType = translationType;
        this.sourceLanguage = sourceLanguage;
        this.targetLanguage = targetLanguage;
        this.selectedText = selectedText;
        this.context = context;
        this.response = response;
    }
}
