package com.germanreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.germanreader.dto.document.DocumentLimits;
import com.germanreader.dto.document.DocumentResponse;
import com.germanreader.dto.document.DocumentSummary;
import java.time.Instant;
import com.germanreader.entity.ReadingDocument;
import com.germanreader.exception.DocumentException;
import com.germanreader.exception.ErrorCode;
import com.germanreader.repository.ReadingDocumentRepository;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

class DocumentServiceTest {

    private static final UUID OWNER = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");

    private final ReadingDocumentRepository repository = mock(ReadingDocumentRepository.class);
    private final DocumentService service = new DocumentService(repository);

    @BeforeEach
    void saveReturnsTheEntity() {
        given(repository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
    }

    private ReadingDocument savedDocument() {
        ArgumentCaptor<ReadingDocument> captor = ArgumentCaptor.forClass(ReadingDocument.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private static MockMultipartFile txt(String filename, byte[] bytes) {
        return new MockMultipartFile("file", filename, "text/plain", bytes);
    }

    private static MockMultipartFile txt(String filename, String content) {
        return txt(filename, content.getBytes(StandardCharsets.UTF_8));
    }

    private void assertRejected(MockMultipartFile file, ErrorCode expected) {
        assertThatThrownBy(() -> service.upload(OWNER, file))
                .isInstanceOfSatisfying(DocumentException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
        verify(repository, never()).save(any());
    }

    @Test
    void createTrimsTheTitleAndNormalizesLineBreaks() {
        DocumentResponse response = service.create(OWNER, "  Märchen  ", "\r\nEs war einmal.\r\nEnde.\r\n");

        ReadingDocument saved = savedDocument();
        assertThat(saved.getTitle()).isEqualTo("Märchen");
        assertThat(saved.getContent()).isEqualTo("Es war einmal.\nEnde.");
        assertThat(saved.getCreatedAt()).isEqualTo(saved.getUpdatedAt()).isNotNull();
        assertThat(response.content()).isEqualTo("Es war einmal.\nEnde.");
    }

    @Test
    void getThrowsNotFoundForAnUnknownId() {
        UUID id = UUID.randomUUID();
        given(repository.findByIdAndOwnerId(id, OWNER)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id, OWNER))
                .isInstanceOfSatisfying(DocumentException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DOCUMENT_NOT_FOUND));
    }

    @Test
    void deleteThrowsNotFoundWhenNothingWasDeleted() {
        UUID id = UUID.randomUUID();
        given(repository.deleteDocumentById(id, OWNER)).willReturn(0);

        assertThatThrownBy(() -> service.delete(id, OWNER))
                .isInstanceOfSatisfying(DocumentException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DOCUMENT_NOT_FOUND));
    }

    @Test
    void renameStripsTheTitleAndSetsANewUpdatedAt() {
        UUID id = UUID.randomUUID();
        Instant before = Instant.now().minusSeconds(1);
        given(repository.rename(eq(id), eq(OWNER), any(), any())).willReturn(1);
        DocumentSummary summary = new DocumentSummary(id, "Im Garten", before, Instant.now());
        given(repository.findSummaryById(id, OWNER)).willReturn(Optional.of(summary));

        assertThat(service.rename(id, OWNER, "  Im Garten  ")).isEqualTo(summary);

        ArgumentCaptor<Instant> updatedAt = ArgumentCaptor.forClass(Instant.class);
        verify(repository).rename(eq(id), eq(OWNER), eq("Im Garten"), updatedAt.capture());
        assertThat(updatedAt.getValue()).isAfter(before);
        assertThat(updatedAt.getValue().getNano() % 1000).isZero(); // microseconds, as PostgreSQL stores them
    }

    @Test
    void renameThrowsNotFoundWhenNothingWasUpdated() {
        UUID id = UUID.randomUUID();
        given(repository.rename(eq(id), eq(OWNER), any(), any())).willReturn(0);

        assertThatThrownBy(() -> service.rename(id, OWNER, "Neu"))
                .isInstanceOfSatisfying(DocumentException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DOCUMENT_NOT_FOUND));
        verify(repository, never()).findSummaryById(any(), any());
    }

    @Nested
    class ValidUploads {

        @Test
        void storesTheTextTitledAfterTheFileName() {
            service.upload(OWNER, txt("Die Großmutter.txt", "Am Morgen sitzt meine Großmutter auf der Bank.\n"));

            ReadingDocument saved = savedDocument();
            assertThat(saved.getTitle()).isEqualTo("Die Großmutter");
            assertThat(saved.getContent()).isEqualTo("Am Morgen sitzt meine Großmutter auf der Bank.");
        }

        @Test
        void acceptsAnUppercaseExtensionAndStripsTheByteOrderMark() {
            service.upload(OWNER, txt("NOTES.TXT", "\uFEFFÜber Brücken."));

            ReadingDocument saved = savedDocument();
            assertThat(saved.getTitle()).isEqualTo("NOTES");
            assertThat(saved.getContent()).isEqualTo("Über Brücken.");
        }

        @Test
        void ignoresAPathInTheFileName() {
            service.upload(OWNER, txt("C:/Users/someone/texte/kapitel.txt", "Text."));

            assertThat(savedDocument().getTitle()).isEqualTo("kapitel");
        }

        @Test
        void usesAPlaceholderTitleWhenTheNameIsOnlyAnExtension() {
            service.upload(OWNER, txt(".txt", "Text."));

            assertThat(savedDocument().getTitle()).isEqualTo(DocumentService.UNTITLED);
        }

        @Test
        void shortensAVeryLongFileName() {
            service.upload(OWNER, txt("a".repeat(300) + ".txt", "Text."));

            assertThat(savedDocument().getTitle()).hasSize(DocumentLimits.MAX_TITLE_LENGTH);
        }
    }

    @Nested
    class InvalidUploads {

        @Test
        void rejectsAMissingOrEmptyFile() {
            assertRejected(null, ErrorCode.INVALID_UPLOAD);
            assertRejected(txt("leer.txt", new byte[0]), ErrorCode.INVALID_UPLOAD);
        }

        @Test
        void rejectsAFileWithOnlyWhitespace() {
            assertRejected(txt("leer.txt", " \n\t\r\n "), ErrorCode.INVALID_UPLOAD);
        }

        @Test
        void rejectsOtherExtensionsEvenWithTextContent() {
            assertRejected(txt("text.md", "Hallo."), ErrorCode.INVALID_UPLOAD);
            assertRejected(txt("text.txt.exe", "Hallo."), ErrorCode.INVALID_UPLOAD);
            assertRejected(txt(null, "Hallo."), ErrorCode.INVALID_UPLOAD);
        }

        @Test
        void rejectsInvalidUtf8() {
            // "Grüße" in Windows-1252: ü and ß are single bytes that are not valid UTF-8.
            byte[] latin1 = "Grüße".getBytes(StandardCharsets.ISO_8859_1);
            assertRejected(txt("brief.txt", latin1), ErrorCode.INVALID_UPLOAD);
        }

        @Test
        void rejectsBinaryContentBehindATxtName() {
            byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13};
            assertRejected(txt("bild.txt", png), ErrorCode.INVALID_UPLOAD);
            // Decodes as UTF-8, but NUL bytes mean it is not a text file.
            assertRejected(txt("daten.txt", "Hallo\u0000Welt"), ErrorCode.INVALID_UPLOAD);
        }

        @Test
        void rejectsTooManyCharacters() {
            String tooLong = "a".repeat(DocumentLimits.MAX_CONTENT_LENGTH + 1);
            assertRejected(txt("lang.txt", tooLong), ErrorCode.UPLOAD_TOO_LARGE);
        }
    }
}
