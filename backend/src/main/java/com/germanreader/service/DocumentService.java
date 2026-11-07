package com.germanreader.service;

import com.germanreader.dto.document.DocumentLimits;
import com.germanreader.dto.document.DocumentResponse;
import com.germanreader.dto.document.DocumentSummary;
import com.germanreader.entity.ReadingDocument;
import com.germanreader.exception.DocumentException;
import com.germanreader.exception.ErrorCode;
import com.germanreader.repository.ReadingDocumentRepository;
import java.io.IOException;
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

/** The reading library. Logs ids and sizes only, never titles or content. */
@Slf4j
@Service
public class DocumentService {

    static final String UNTITLED = "Untitled";

    private final ReadingDocumentRepository repository;

    public DocumentService(ReadingDocumentRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public DocumentResponse create(String title, String content) {
        ReadingDocument saved = repository.save(new ReadingDocument(title.strip(), normalize(content)));
        log.debug("Created document {} ({} characters)", saved.getId(), saved.getContent().length());
        return DocumentResponse.from(saved);
    }

    /**
     * Stores a UTF-8 .txt upload, titled after the file name. Not transactional itself: the file is
     * checked first, and only the single insert at the end needs a transaction (create() has one).
     */
    public DocumentResponse upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalidUpload("No file or an empty file");
        }
        String filename = StringUtils.getFilename(file.getOriginalFilename());
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".txt")) {
            throw invalidUpload("Not a .txt file name");
        }

        String content = normalize(decodeUtf8(file));
        if (content.isEmpty()) {
            throw invalidUpload("The file contains only whitespace");
        }
        if (content.length() > DocumentLimits.MAX_CONTENT_LENGTH) {
            throw new DocumentException(ErrorCode.UPLOAD_TOO_LARGE, "Upload has " + content.length() + " characters");
        }
        return create(titleFrom(filename), content);
    }

    @Transactional(readOnly = true)
    public List<DocumentSummary> list() {
        return repository.findAllSummaries();
    }

    @Transactional(readOnly = true)
    public DocumentResponse get(UUID id) {
        return repository.findById(id)
                .map(DocumentResponse::from)
                .orElseThrow(() -> notFound(id));
    }

    /**
     * Changes the title only. The id stays the same, so saved words keep their link to the text.
     * Returns the summary: the client already has the content and nothing in it changed.
     */
    @Transactional
    public DocumentSummary rename(UUID id, String title) {
        // PostgreSQL stores microseconds; truncating keeps the returned value equal to the stored one.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (repository.rename(id, title.strip(), now) == 0) {
            throw notFound(id);
        }
        log.debug("Renamed document {}", id);
        return repository.findSummaryById(id).orElseThrow(() -> notFound(id));
    }

    @Transactional
    public void delete(UUID id) {
        if (repository.deleteDocumentById(id) == 0) {
            throw notFound(id);
        }
        log.debug("Deleted document {}", id);
    }

    /**
     * Strict decoding: malformed bytes are an error instead of being silently replaced with U+FFFD.
     * Control characters other than tab and line breaks mean the file is binary, even if it decodes.
     */
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

    private static boolean isForbiddenControl(int c) {
        return (c < 0x20 && c != '\t' && c != '\n' && c != '\r') || c == 0x7F;
    }

    /** One line-break style and no surrounding whitespace, whatever editor or OS the text came from. */
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
