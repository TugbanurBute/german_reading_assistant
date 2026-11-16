package com.germanreader.service;

import com.germanreader.dto.document.DocumentLimits;
import com.germanreader.dto.document.DocumentResponse;
import com.germanreader.dto.document.DocumentSummary;
import com.germanreader.entity.ReadingDocument;
import com.germanreader.exception.DocumentException;
import com.germanreader.exception.ErrorCode;
import com.germanreader.repository.ReadingDocumentRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;


@Slf4j
@Service
public class DocumentService {

    static final String UNTITLED = "Untitled";

    private final ReadingDocumentRepository repository;

    public DocumentService(ReadingDocumentRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public DocumentResponse create(UUID ownerId, String title, String content) {
        ReadingDocument saved = repository.save(new ReadingDocument(ownerId, title.strip(), normalize(content)));
        log.debug("Created document {} ({} characters)", saved.getId(), saved.getContent().length());
        return DocumentResponse.from(saved);
    }

    public DocumentResponse upload(UUID ownerId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalidUpload("No file or an empty file");
        }
        String filename = StringUtils.getFilename(file.getOriginalFilename());
        String lowerName = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String content;
        if (lowerName.endsWith(".txt")) {
            if (file.getSize() > DocumentLimits.MAX_TXT_UPLOAD_BYTES) {
                throw new DocumentException(ErrorCode.UPLOAD_TOO_LARGE, "Text upload has " + file.getSize() + " bytes");
            }
            content = normalize(decodeUtf8(file));
        } else if (lowerName.endsWith(".pdf")) {
            content = extractPdf(file);
        } else {
            throw invalidUpload("Not a .txt or .pdf file name");
        }

        if (content.isEmpty()) {
            throw invalidUpload("The file contains only whitespace");
        }
        if (content.length() > DocumentLimits.MAX_CONTENT_LENGTH) {
            throw new DocumentException(ErrorCode.UPLOAD_TOO_LARGE, "Upload has " + content.length() + " characters");
        }
        return create(ownerId, titleFrom(filename), content);
    }

    @Transactional(readOnly = true)
    public List<DocumentSummary> list(UUID ownerId) {
        return repository.findAllSummaries(ownerId);
    }

    @Transactional(readOnly = true)
    public DocumentResponse get(UUID id, UUID ownerId) {
        return repository.findByIdAndOwnerId(id, ownerId)
                .map(DocumentResponse::from)
                .orElseThrow(() -> notFound(id));
    }


    @Transactional
    public DocumentSummary rename(UUID id, UUID ownerId, String title) {
        // PostgreSQL stores microseconds; truncating keeps the returned value equal to the stored one.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (repository.rename(id, ownerId, title.strip(), now) == 0) {
            throw notFound(id);
        }
        log.debug("Renamed document {}", id);
        return repository.findSummaryById(id, ownerId).orElseThrow(() -> notFound(id));
    }

    @Transactional
    public void delete(UUID id, UUID ownerId) {
        if (repository.deleteDocumentById(id, ownerId) == 0) {
            throw notFound(id);
        }
        log.debug("Deleted document {}", id);
    }


    private static String decodeUtf8(MultipartFile file) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(file.getBytes()))
                    .toString();
        } catch (CharacterCodingException e) {
            throw invalidUpload("The file is not valid UTF-8");
        } catch (IOException e) {
            throw invalidUpload("The upload could not be read: " + e.getClass().getSimpleName());
        }
        if (text.startsWith("﻿")) { // byte order mark, written by some Windows editors
            text = text.substring(1);
        }
        if (text.chars().anyMatch(DocumentService::isForbiddenControl)) {
            throw invalidUpload("The file contains control characters");
        }
        return text;
    }

    private static String extractPdf(MultipartFile file) {
        if (file.getSize() > DocumentLimits.MAX_PDF_UPLOAD_BYTES) {
            throw new DocumentException(ErrorCode.UPLOAD_TOO_LARGE, "PDF upload has " + file.getSize() + " bytes");
        }
        try (InputStream input = file.getInputStream()) {
            return PdfTextExtractor.extract(input, DocumentLimits.MAX_CONTENT_LENGTH);
        } catch (IOException e) {
            throw invalidUpload("The upload could not be read: " + e.getClass().getSimpleName());
        }
    }

    private static boolean isForbiddenControl(int c) {
        return (c < 0x20 && c != '\t' && c != '\n' && c != '\r') || c == 0x7F;
    }

    private static String normalize(String content) {
        return content.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private static String titleFrom(String filename) {
        String title = StringUtils.stripFilenameExtension(filename).strip();
        if (title.length() > DocumentLimits.MAX_TITLE_LENGTH) {
            title = title.substring(0, DocumentLimits.MAX_TITLE_LENGTH).strip();
        }
        return title.isEmpty() ? UNTITLED : title;
    }

    private static DocumentException invalidUpload(String reason) {
        return new DocumentException(ErrorCode.INVALID_UPLOAD, reason);
    }

    private static DocumentException notFound(UUID id) {
        return new DocumentException(ErrorCode.DOCUMENT_NOT_FOUND, "No document " + id);
    }
}
