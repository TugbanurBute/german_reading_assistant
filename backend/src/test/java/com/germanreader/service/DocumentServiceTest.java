package com.germanreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.germanreader.dto.document.DocumentLimits;
import com.germanreader.dto.document.DocumentResponse;
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
        assertThatThrownBy(() -> service.upload(file))
                .isInstanceOfSatisfying(DocumentException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
        verify(repository, never()).save(any());
    }

    @Test
    void createTrimsTheTitleAndNormalizesLineBreaks() {
        DocumentResponse response = service.create("  Märchen  ", "\r\nEs war einmal.\r\nEnde.\r\n");

        ReadingDocument saved = savedDocument();
        assertThat(saved.getTitle()).isEqualTo("Märchen");
        assertThat(saved.getContent()).isEqualTo("Es war einmal.\nEnde.");
        assertThat(saved.getCreatedAt()).isEqualTo(saved.getUpdatedAt()).isNotNull();
        assertThat(response.content()).isEqualTo("Es war einmal.\nEnde.");
    }

    @Test
    void getThrowsNotFoundForAnUnknownId() {
        UUID id = UUID.randomUUID();
        given(repository.findById(id)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id))
                .isInstanceOfSatisfying(DocumentException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DOCUMENT_NOT_FOUND));
    }

    @Test
    void deleteThrowsNotFoundWhenNothingWasDeleted() {
        UUID id = UUID.randomUUID();
        given(repository.deleteDocumentById(id)).willReturn(0);

        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOfSatisfying(DocumentException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DOCUMENT_NOT_FOUND));
    }

    @Nested
    class ValidUploads {

        @Test
        void storesTheTextTitledAfterTheFileName() {
            service.upload(txt("Die Großmutter.txt", "Am Morgen sitzt meine Großmutter auf der Bank.\n"));

            ReadingDocument saved = savedDocument();
            assertThat(saved.getTitle()).isEqualTo("Die Großmutter");
            assertThat(saved.getContent()).isEqualTo("Am Morgen sitzt meine Großmutter auf der Bank.");
        }

        @Test
        void acceptsAnUppercaseExtensionAndStripsTheByteOrderMark() {
            service.upload(txt("NOTES.TXT", "\uFEFFÜber Brücken."));

            ReadingDocument saved = savedDocument();
            assertThat(saved.getTitle()).isEqualTo("NOTES");
            assertThat(saved.getContent()).isEqualTo("Über Brücken.");
        }

        @Test
        void ignoresAPathInTheFileName() {
            service.upload(txt("C:/Users/someone/texte/kapitel.txt", "Text."));

            assertThat(savedDocument().getTitle()).isEqualTo("kapitel");
        }

        @Test
        void usesAPlaceholderTitleWhenTheNameIsOnlyAnExtension() {
            service.upload(txt(".txt", "Text."));

            assertThat(savedDocument().getTitle()).isEqualTo(DocumentService.UNTITLED);
        }

        @Test
        void shortensAVeryLongFileName() {
            service.upload(txt("a".repeat(300) + ".txt", "Text."));

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
