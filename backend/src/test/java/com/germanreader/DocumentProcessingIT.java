package com.germanreader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.germanreader.entity.ProcessingStatus;
import com.germanreader.entity.ReadingDocument;
import com.germanreader.processing.DocumentProcessingConfig;
import com.germanreader.processing.DocumentProcessingRelay;
import com.germanreader.processing.DocumentProcessingRequested;
import com.germanreader.processing.DocumentProcessor;
import com.germanreader.repository.ReadingDocumentRepository;
import com.germanreader.security.TestAuth;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "translation.provider=mock",
        "spring.flyway.schemas=" + TranslationCacheIT.SCHEMA,
        "spring.datasource.hikari.schema=" + TranslationCacheIT.SCHEMA,
        "app.document-processing.confirm-timeout=PT0.3S"
})
@ActiveProfiles("local")
@AutoConfigureMockMvc
@Import(TestAuth.AsDefaultUser.class)
class DocumentProcessingIT {

    private static final String TEXT = "Am Morgen sitzt meine Großmutter auf der Bank im Park.\n\nSie füttert die Tauben.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReadingDocumentRepository documents;

    @Autowired
    private DocumentProcessingRelay relay;

    @Autowired
    private DocumentProcessor processor;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoBean
    private RabbitTemplate rabbit;

    private final List<DocumentProcessingRequested> published = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TestAuth.ensureUser(jdbc, TestAuth.USER_ID);
        jdbc.update("UPDATE reading_document SET processing_queued_at = now() WHERE processing_queued_at IS NULL");
    }

    private void broker(BiConsumer<DocumentProcessingRequested, CorrelationData> answer) {
        doAnswer(invocation -> {
            answer.accept(invocation.getArgument(2), invocation.getArgument(3));
            return null;
        }).when(rabbit).convertAndSend(eq(DocumentProcessingConfig.EXCHANGE), eq(DocumentProcessingConfig.ROUTING_KEY),
                any(Object.class), any(CorrelationData.class));
    }

    private void brokerAccepts() {
        broker((message, correlation) -> {
            published.add(message);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
        });
    }

    private UUID createDocumentWithoutApi(String title) {
        return documents.saveAndFlush(new ReadingDocument(TestAuth.USER_ID, title, TEXT)).getId();
    }

    private String processingStatus(UUID id) {
        return jdbc.queryForObject("SELECT processing_status FROM reading_document WHERE id = ?", String.class, id);
    }

    private void claimedSince(UUID id, String startedAt) {
        jdbc.update("UPDATE reading_document SET processing_status = 'PROCESSING', processing_queued_at = now(), "
                + "processing_started_at = " + startedAt + " WHERE id = ?", id);
    }

    private UUID createDocument() throws Exception {
        String body = jsonMapper.writeValueAsString(java.util.Map.of("title", "Im Park", "content", TEXT));
        String json = mockMvc.perform(post("/api/v1/documents").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.processingStatus").value("PENDING"))
                .andExpect(jsonPath("$.wordCount").isEmpty())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(jsonMapper.readTree(json).get("id").asString());
    }

    private ReadingDocument row(UUID id) {
        return documents.findById(id).orElseThrow();
    }

    @Test
    void creatingADocumentPublishesNothingDuringTheRequestAndLeavesItReadable() throws Exception {
        UUID id = createDocument();

        verifyNoInteractions(rabbit);
        assertThat(row(id).getProcessingStatus()).isEqualTo(ProcessingStatus.PENDING);
        assertThat(row(id).getProcessingQueuedAt()).isNull();
        mockMvc.perform(get("/api/v1/documents/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value(TEXT))
                .andExpect(jsonPath("$.processingStatus").value("PENDING"));
    }

    @Test
    void theRelayPublishesTheCommittedDocumentOnceWithItsIdOnly() throws Exception {
        UUID id = createDocument();
        brokerAccepts();

        assertThat(relay.publishPending()).isEqualTo(1);
        assertThat(relay.publishPending()).isZero();

        assertThat(published).containsExactly(new DocumentProcessingRequested(id, 1));
        assertThat(row(id).getProcessingQueuedAt()).isNotNull();
    }

    @Test
    void whenRabbitMqIsDownTheMessageWaitsAndIsPublishedLater() throws Exception {
        UUID id = createDocument();
        doThrow(new AmqpConnectException(new java.net.ConnectException("refused"))).when(rabbit)
                .convertAndSend(any(String.class), any(String.class), any(Object.class), any(CorrelationData.class));

        assertThat(relay.publishPending()).isZero();
        assertThat(row(id).getProcessingQueuedAt()).isNull();

        brokerAccepts();
        assertThat(relay.publishPending()).isEqualTo(1);
        assertThat(published).containsExactly(new DocumentProcessingRequested(id, 1));
    }

    @Test
    void aRefusedPublishIsNotMarkedAsQueued() throws Exception {
        UUID id = createDocument();
        broker((message, correlation) -> correlation.getFuture().complete(new CorrelationData.Confirm(false, "nack")));

        assertThat(relay.publishPending()).isZero();
        assertThat(row(id).getProcessingQueuedAt()).isNull();
    }

    @Test
    void aPublishWithoutAConfirmInTimeIsNotMarkedAsQueued() throws Exception {
        UUID id = createDocument();
        broker((message, correlation) -> { }); // the confirm never arrives

        assertThat(relay.publishPending()).isZero();
        assertThat(row(id).getProcessingQueuedAt()).isNull();
    }

    @Test
    void anUnroutableMessageIsNotMarkedAsQueuedAlthoughTheBrokerConfirmsIt() throws Exception {
        UUID id = createDocument();
        broker((message, correlation) -> {
            correlation.setReturned(new ReturnedMessage(new org.springframework.amqp.core.Message(new byte[0]),
                    312, "NO_ROUTE", DocumentProcessingConfig.EXCHANGE, DocumentProcessingConfig.ROUTING_KEY));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
        });

        assertThat(relay.publishPending()).isZero();
        assertThat(row(id).getProcessingQueuedAt()).isNull();
    }

    @Test
    void aBrokerFailureInTheMiddleOfABatchKeepsTheRestForTheNextRun() {
        UUID first = createDocumentWithoutApi("Eins");
        UUID second = createDocumentWithoutApi("Zwei");
        UUID third = createDocumentWithoutApi("Drei");
        AtomicInteger calls = new AtomicInteger();
        broker((message, correlation) -> {
            if (calls.incrementAndGet() == 2) {
                throw new AmqpConnectException(new java.net.ConnectException("connection lost"));
            }
            published.add(message);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
        });

        assertThat(relay.publishPending()).isEqualTo(1);
        assertThat(row(first).getProcessingQueuedAt()).isNotNull();
        assertThat(row(second).getProcessingQueuedAt()).isNull();
        assertThat(row(third).getProcessingQueuedAt()).isNull();

        assertThat(relay.publishPending()).isEqualTo(2);
        assertThat(published).extracting(DocumentProcessingRequested::documentId).containsExactly(first, second, third);
    }

    @Test
    void consumingTheMessageStoresTheMetadata() throws Exception {
        UUID id = createDocument();

        processor.process(new DocumentProcessingRequested(id, 1));

        ReadingDocument done = row(id);
        assertThat(done.getProcessingStatus()).isEqualTo(ProcessingStatus.COMPLETED);
        assertThat(done.getWordCount()).isEqualTo(14);
        assertThat(done.getCharacterCount()).isEqualTo(TEXT.length());
        assertThat(done.getParagraphCount()).isEqualTo(2);
        assertThat(done.getProcessedAt()).isNotNull();
        assertThat(done.getContent()).isEqualTo(TEXT);
        mockMvc.perform(get("/api/v1/documents"))
                .andExpect(jsonPath("$[0].processingStatus").value("COMPLETED"))
                .andExpect(jsonPath("$[0].wordCount").value(14))
                .andExpect(jsonPath("$[0].paragraphCount").value(2));
    }

    @Test
    void aDuplicateMessageChangesNothing() throws Exception {
        UUID id = createDocument();
        processor.process(new DocumentProcessingRequested(id, 1));
        ReadingDocument first = row(id);

        processor.process(new DocumentProcessingRequested(id, 1));

        ReadingDocument second = row(id);
        assertThat(second.getProcessingStatus()).isEqualTo(ProcessingStatus.COMPLETED);
        assertThat(second.getProcessedAt()).isEqualTo(first.getProcessedAt());
        assertThat(second.getWordCount()).isEqualTo(first.getWordCount());
    }

    @Test
    void aStaleMessageDoesNotOverwriteTheDocument() throws Exception {
        UUID id = createDocument();
        jdbc.update("UPDATE reading_document SET processing_version = 2 WHERE id = ?", id);

        processor.process(new DocumentProcessingRequested(id, 1));

        assertThat(row(id).getProcessingStatus()).isEqualTo(ProcessingStatus.PENDING);
        assertThat(row(id).getWordCount()).isNull();
    }

    @Test
    void aMessageForADeletedDocumentIsAcknowledgedWithoutError() throws Exception {
        UUID id = createDocument();
        mockMvc.perform(delete("/api/v1/documents/" + id)).andExpect(status().isNoContent());

        assertThatCode(() -> processor.process(new DocumentProcessingRequested(id, 1))).doesNotThrowAnyException();
        assertThat(documents.existsById(id)).isFalse();
    }

    @Test
    void retryExhaustionMarksTheDocumentFailedButKeepsItReadable() throws Exception {
        UUID id = createDocument();
        org.springframework.amqp.core.Message message = new org.springframework.amqp.core.Message(
                jsonMapper.writeValueAsBytes(new DocumentProcessingRequested(id, 1)),
                new org.springframework.amqp.core.MessageProperties());

        // The last attempt released its claim (PENDING), then the recoverer runs.
        processor.markFailed(message, new IllegalStateException("boom"));

        assertThat(row(id).getProcessingStatus()).isEqualTo(ProcessingStatus.FAILED);
        mockMvc.perform(get("/api/v1/documents/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value(TEXT))
                .andExpect(jsonPath("$.processingStatus").value("FAILED"));
    }

    @Test
    void aDelayedDuplicateDoesNotRetryAFailedDocumentButTheExplicitRequeueDoes() {
        UUID id = createDocumentWithoutApi("Fehlgeschlagen");
        jdbc.update("UPDATE reading_document SET processing_status = 'FAILED', processing_queued_at = now() WHERE id = ?", id);

        processor.process(new DocumentProcessingRequested(id, 1));
        assertThat(processingStatus(id)).isEqualTo("FAILED");

        assertThat(documents.requeueFailed(id)).isEqualTo(1);
        assertThat(processingStatus(id)).isEqualTo("PENDING");
        assertThat(row(id).getProcessingVersion()).isEqualTo(2);
        brokerAccepts();
        relay.publishPending();
        assertThat(published).containsExactly(new DocumentProcessingRequested(id, 2));

        processor.process(new DocumentProcessingRequested(id, 1)); // another late copy of the old message
        assertThat(processingStatus(id)).isEqualTo("PENDING");
        processor.process(new DocumentProcessingRequested(id, 2));
        assertThat(processingStatus(id)).isEqualTo("COMPLETED");
        assertThat(documents.requeueFailed(id)).isZero(); // only FAILED documents
    }

    @Test
    void retryExhaustionDoesNotOverrideAnotherWorkersLiveClaim() throws Exception {
        UUID id = createDocumentWithoutApi("Besetzt");
        claimedSince(id, "now()");
        org.springframework.amqp.core.Message message = new org.springframework.amqp.core.Message(
                jsonMapper.writeValueAsBytes(new DocumentProcessingRequested(id, 1)),
                new org.springframework.amqp.core.MessageProperties());

        processor.markFailed(message, new IllegalStateException("boom"));

        assertThat(processingStatus(id)).isEqualTo("PROCESSING");
    }

    @Test
    void aWorkerWhoseLeaseExpiredCannotOverwriteTheNewerAttempt() {
        UUID id = createDocumentWithoutApi("Überholt");
        Instant oldClaim = Instant.now().minus(Duration.ofMinutes(10)).truncatedTo(ChronoUnit.MICROS);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        assertThat(documents.startProcessing(id, 1, oldClaim, oldClaim.minus(Duration.ofMinutes(5)))).isEqualTo(1);
        // Worker A paused past its lease; worker B takes over.
        assertThat(documents.startProcessing(id, 1, now, now.minus(Duration.ofMinutes(5)))).isEqualTo(1);

        assertThat(documents.completeProcessing(id, 1, oldClaim, 999, 999, 9, Instant.now())).isZero();
        assertThat(documents.releaseProcessing(id, 1, oldClaim)).isZero();
        assertThat(processingStatus(id)).isEqualTo("PROCESSING");
        assertThat(row(id).getWordCount()).isNull();

        assertThat(documents.completeProcessing(id, 1, now, 14, TEXT.length(), 2, Instant.now())).isEqualTo(1);
        assertThat(documents.completeProcessing(id, 1, oldClaim, 999, 999, 9, Instant.now())).isZero();
        assertThat(row(id).getWordCount()).isEqualTo(14);
    }

    @Test
    void theRelayPublishesOnlyPendingUserDocuments() {
        UUID pending = createDocumentWithoutApi("Wartet");
        UUID completed = createDocumentWithoutApi("Fertig ohne Nachricht");
        UUID failed = createDocumentWithoutApi("Fehler ohne Nachricht");
        jdbc.update("UPDATE reading_document SET processing_status = 'COMPLETED' WHERE id = ?", completed);
        jdbc.update("UPDATE reading_document SET processing_status = 'FAILED' WHERE id = ?", failed);
        brokerAccepts();

        relay.publishPending();

        assertThat(published).extracting(DocumentProcessingRequested::documentId).containsExactly(pending);
        assertThat(row(completed).getProcessingQueuedAt()).isNull();
    }

    @Test
    void whenTheOutboxUpdateFailsAfterTheConfirmTheDocumentIsPublishedAgainAndProcessedOnce() {
        UUID id = createDocumentWithoutApi("Zweimal gesendet");
        AtomicInteger publishes = new AtomicInteger();
        broker((message, correlation) -> {
            published.add(message);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            if (publishes.incrementAndGet() == 1) {
                // Break the relay's transaction after the confirm: its UPDATE (and the commit) will fail.
                try {
                    jdbc.execute("SELECT 1 / 0");
                } catch (org.springframework.dao.DataAccessException expected) {
                    // the transaction is now aborted
                }
            }
        });

        assertThat(relay.publishPending()).isZero();
        assertThat(row(id).getProcessingQueuedAt()).isNull();
        assertThat(relay.publishPending()).isEqualTo(1);
        assertThat(published).containsExactly(new DocumentProcessingRequested(id, 1), new DocumentProcessingRequested(id, 1));

        processor.process(published.get(0));
        Instant processedAt = row(id).getProcessedAt();
        processor.process(published.get(1));

        assertThat(processingStatus(id)).isEqualTo("COMPLETED");
        assertThat(row(id).getProcessedAt()).isEqualTo(processedAt);
    }

    @Test
    void aDuplicateArrivingWhileTheDocumentIsBeingProcessedIsSkipped() {
        UUID id = createDocumentWithoutApi("Doppelt");
        claimedSince(id, "now()");

        processor.process(new DocumentProcessingRequested(id, 1));

        assertThat(processingStatus(id)).isEqualTo("PROCESSING");
        assertThat(row(id).getWordCount()).isNull();
    }

    @Test
    void concurrentConsumersClaimADocumentOnlyOnce() throws Exception {
        UUID id = createDocumentWithoutApi("Gleichzeitig");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> claims = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            claims.add(pool.submit(() -> {
                start.await();
                return documents.startProcessing(id, 1, now, now.minus(Duration.ofMinutes(5)));
            }));
        }
        start.countDown();
        int claimed = 0;
        for (Future<Integer> claim : claims) {
            claimed += claim.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(claimed).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateDeliveriesEndCompletedWithoutErrors() throws Exception {
        UUID id = createDocumentWithoutApi("Viele");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> deliveries = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            deliveries.add(pool.submit(() -> {
                start.await();
                processor.process(new DocumentProcessingRequested(id, 1));
                return null;
            }));
        }
        start.countDown();
        for (Future<?> delivery : deliveries) {
            delivery.get(10, TimeUnit.SECONDS); // rethrows any failure
        }
        pool.shutdown();

        assertThat(processingStatus(id)).isEqualTo("COMPLETED");
        assertThat(row(id).getWordCount()).isEqualTo(14);
    }

    @Test
    void theRedeliveryTakesOverAClaimAbandonedByACrashedConsumer() {
        UUID id = createDocumentWithoutApi("Abgestürzt");
        claimedSince(id, "now() - interval '10 minutes'");

        processor.process(new DocumentProcessingRequested(id, 1));

        assertThat(processingStatus(id)).isEqualTo("COMPLETED");
    }

    @Test
    void aClaimFromBeforeTheLeaseColumnCountsAsAbandoned() {
        UUID id = createDocumentWithoutApi("Alt");
        claimedSince(id, "NULL");

        processor.process(new DocumentProcessingRequested(id, 1));

        assertThat(processingStatus(id)).isEqualTo("COMPLETED");
    }

    @Test
    void aReleasedClaimCanBeTakenAgainByTheRetry() {
        UUID id = createDocumentWithoutApi("Nochmal");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        assertThat(documents.startProcessing(id, 1, now, now.minus(Duration.ofMinutes(5)))).isEqualTo(1);

        assertThat(documents.releaseProcessing(id, 1, now)).isEqualTo(1);
        processor.process(new DocumentProcessingRequested(id, 1));

        assertThat(processingStatus(id)).isEqualTo("COMPLETED");
    }

    @Test
    void theRelayRequeuesAnAbandonedClaimAndTheNewMessageCompletesIt() {
        UUID id = createDocumentWithoutApi("Wiederaufnahme");
        claimedSince(id, "now() - interval '10 minutes'");
        brokerAccepts();

        assertThat(relay.requeueAbandoned()).isPositive();
        assertThat(processingStatus(id)).isEqualTo("PENDING");
        assertThat(row(id).getProcessingQueuedAt()).isNull();

        assertThat(relay.publishPending()).isPositive();
        // A new version: the crashed worker's message, if it is ever redelivered, is ignored.
        assertThat(published).contains(new DocumentProcessingRequested(id, 2));
        processor.process(new DocumentProcessingRequested(id, 1));
        assertThat(processingStatus(id)).isEqualTo("PENDING");
        processor.process(new DocumentProcessingRequested(id, 2));
        assertThat(processingStatus(id)).isEqualTo("COMPLETED");
    }

    @Test
    void theRelayLeavesFreshClaimsAndFinishedDocumentsAlone() {
        UUID busy = createDocumentWithoutApi("Beschäftigt");
        claimedSince(busy, "now()");
        UUID done = createDocumentWithoutApi("Fertig");
        processor.process(new DocumentProcessingRequested(done, 1));

        relay.requeueAbandoned();

        assertThat(processingStatus(busy)).isEqualTo("PROCESSING");
        assertThat(row(busy).getProcessingQueuedAt()).isNotNull();
        assertThat(processingStatus(done)).isEqualTo("COMPLETED");
    }

    @Test
    void theRelayRequeuesADocumentWhoseMessageWasLost() {
        UUID id = createDocumentWithoutApi("Verloren");
        jdbc.update("UPDATE reading_document SET processing_queued_at = now() - interval '1 hour' WHERE id = ?", id);

        relay.requeueAbandoned();

        assertThat(row(id).getProcessingQueuedAt()).isNull();
        assertThat(processingStatus(id)).isEqualTo("PENDING");
    }

    @Test
    void renamingKeepsTheMetadata() throws Exception {
        UUID id = createDocument();
        processor.process(new DocumentProcessingRequested(id, 1));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/documents/" + id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"Neu\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processingStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.wordCount").value(14));
    }
}
