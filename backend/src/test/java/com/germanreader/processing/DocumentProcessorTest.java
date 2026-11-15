package com.germanreader.processing;

import static org.assertj.core.api.Assertions.assertThat;
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

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

class DocumentProcessorTest {

    private static final UUID ID = UUID.fromString("0b6f1c1e-3c55-4c43-9a43-6f1f3c1b2a10");
    private static final String TEXT = "Eins zwei drei.\n\nVier.";

    private final ReadingDocumentRepository documents = mock(ReadingDocumentRepository.class);
    private final DocumentProcessor processor = new DocumentProcessor(documents, JsonMapper.builder().build(), Duration.ofMinutes(5));

    @Test
    void claimsTheDocumentCalculatesAndCompletes() {
        given(documents.startProcessing(eq(ID), eq(1), any(), any())).willReturn(1);
        given(documents.findContentById(ID)).willReturn(Optional.of(TEXT));

        processor.process(new DocumentProcessingRequested(ID, 1));

        verify(documents).completeProcessing(eq(ID), eq(1), any(), eq(4), eq(TEXT.length()), eq(2), any());
    }

    @Test
    void skipsWhenTheDocumentCannotBeClaimed() {
        // Deleted, a stale version, or already COMPLETED (duplicate delivery): acknowledged without any work.
        given(documents.startProcessing(eq(ID), eq(1), any(), any())).willReturn(0);

        processor.process(new DocumentProcessingRequested(ID, 1));

        verify(documents).startProcessing(eq(ID), eq(1), any(), any());
        verifyNoMoreInteractions(documents);
    }

    @Test
    void aDocumentDeletedDuringProcessingIsSkipped() {
        given(documents.startProcessing(eq(ID), eq(1), any(), any())).willReturn(1);
        given(documents.findContentById(ID)).willReturn(Optional.empty());

        assertThatCode(() -> processor.process(new DocumentProcessingRequested(ID, 1))).doesNotThrowAnyException();

        verify(documents, never()).completeProcessing(any(), anyInt(), any(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void aDatabaseFailureIsThrownSoTheMessageIsRetried() {
        given(documents.startProcessing(eq(ID), eq(1), any(), any())).willThrow(new DataAccessResourceFailureException("database down"));

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

    @Test
    void claimsWithALeaseStartingNow() {
        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> staleBefore = ArgumentCaptor.forClass(Instant.class);
        given(documents.startProcessing(eq(ID), eq(1), any(), any())).willReturn(0);

        processor.process(new DocumentProcessingRequested(ID, 1));

        verify(documents).startProcessing(eq(ID), eq(1), now.capture(), staleBefore.capture());
        assertThat(Duration.between(staleBefore.getValue(), now.getValue())).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void aFailureAfterClaimingGivesTheClaimBackAndIsThrownForTheRetry() {
        given(documents.startProcessing(eq(ID), eq(1), any(), any())).willReturn(1);
        given(documents.findContentById(ID)).willThrow(new DataAccessResourceFailureException("database down"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> processor.process(new DocumentProcessingRequested(ID, 1)))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(documents).releaseProcessing(eq(ID), eq(1), any());
    }

    @Test
    void aFailingReleaseDoesNotHideTheOriginalError() {
        given(documents.startProcessing(eq(ID), eq(1), any(), any())).willReturn(1);
        given(documents.findContentById(ID)).willThrow(new DataAccessResourceFailureException("database down"));
        given(documents.releaseProcessing(eq(ID), eq(1), any())).willThrow(new DataAccessResourceFailureException("still down"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> processor.process(new DocumentProcessingRequested(ID, 1)))
                .hasMessage("database down");
    }

    @Test
    void completesAndReleasesWithTheTokenOfItsOwnClaim() {
        ArgumentCaptor<Instant> claimedAt = ArgumentCaptor.forClass(Instant.class);
        given(documents.startProcessing(eq(ID), eq(1), claimedAt.capture(), any())).willReturn(1);
        given(documents.findContentById(ID)).willReturn(Optional.of(TEXT));

        processor.process(new DocumentProcessingRequested(ID, 1));

        verify(documents).completeProcessing(eq(ID), eq(1), eq(claimedAt.getValue()), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void aWorkerWhoseClaimWasTakenOverDiscardsItsResultWithoutError() {
        given(documents.startProcessing(eq(ID), eq(1), any(), any())).willReturn(1);
        given(documents.findContentById(ID)).willReturn(Optional.of(TEXT));
        given(documents.completeProcessing(eq(ID), eq(1), any(), anyInt(), anyInt(), anyInt(), any())).willReturn(0);

        assertThatCode(() -> processor.process(new DocumentProcessingRequested(ID, 1))).doesNotThrowAnyException();

        verify(documents, never()).releaseProcessing(any(), anyInt(), any());
    }
}
