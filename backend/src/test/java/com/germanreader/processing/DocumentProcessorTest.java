package com.germanreader.processing;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.germanreader.repository.ReadingDocumentRepository;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

class DocumentProcessorTest {

    private static final UUID ID = UUID.fromString("0b6f1c1e-3c55-4c43-9a43-6f1f3c1b2a10");
    private static final String TEXT = "Eins zwei drei.\n\nVier.";

    private final ReadingDocumentRepository documents = mock(ReadingDocumentRepository.class);
    private final DocumentProcessor processor = new DocumentProcessor(documents, JsonMapper.builder().build());

    @Test
    void claimsTheDocumentCalculatesAndCompletes() {
        given(documents.startProcessing(ID, 1)).willReturn(1);
        given(documents.findContentById(ID)).willReturn(Optional.of(TEXT));

        processor.process(new DocumentProcessingRequested(ID, 1));

        verify(documents).completeProcessing(eq(ID), eq(1), eq(4), eq(TEXT.length()), eq(2), any());
    }

    @Test
    void skipsWhenTheDocumentCannotBeClaimed() {
        // Deleted, a stale version, or already COMPLETED (duplicate delivery): acknowledged without any work.
        given(documents.startProcessing(ID, 1)).willReturn(0);

        processor.process(new DocumentProcessingRequested(ID, 1));

        verify(documents).startProcessing(ID, 1);
        verifyNoMoreInteractions(documents);
    }

    @Test
    void aDocumentDeletedDuringProcessingIsSkipped() {
        given(documents.startProcessing(ID, 1)).willReturn(1);
        given(documents.findContentById(ID)).willReturn(Optional.empty());

        assertThatCode(() -> processor.process(new DocumentProcessingRequested(ID, 1))).doesNotThrowAnyException();

        verify(documents, never()).completeProcessing(any(), anyInt(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void aDatabaseFailureIsThrownSoTheMessageIsRetried() {
        given(documents.startProcessing(ID, 1)).willThrow(new DataAccessResourceFailureException("database down"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> processor.process(new DocumentProcessingRequested(ID, 1)))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void markFailedSetsTheStatusOfTheMessagesDocument() {
        Message message = new Message(("{\"documentId\":\"" + ID + "\",\"version\":3}").getBytes(StandardCharsets.UTF_8),
                new MessageProperties());

        processor.markFailed(message, new IllegalStateException("boom"));

        verify(documents).failProcessing(ID, 3);
    }

    @Test
    void markFailedToleratesAnUnreadableMessage() {
        Message garbage = new Message("not json".getBytes(StandardCharsets.UTF_8), new MessageProperties());

        assertThatCode(() -> processor.markFailed(garbage, new IllegalStateException("boom"))).doesNotThrowAnyException();
        verifyNoMoreInteractions(documents);
    }
}
