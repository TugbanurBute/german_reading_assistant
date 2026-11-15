package com.germanreader;

import static org.assertj.core.api.Assertions.assertThat;

import com.germanreader.entity.ProcessingStatus;
import com.germanreader.entity.ReadingDocument;
import com.germanreader.processing.DocumentProcessingConfig;
import com.germanreader.repository.ReadingDocumentRepository;
import com.germanreader.security.TestAuth;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import com.germanreader.processing.DocumentProcessingRequested;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@EnabledIf("rabbitMqIsReachable")
@SpringBootTest(properties = {
        "translation.provider=mock",
        "spring.flyway.schemas=" + TranslationCacheIT.SCHEMA,
        "spring.datasource.hikari.schema=" + TranslationCacheIT.SCHEMA,
        "app.document-processing.enabled=true",
        "app.document-processing.relay-interval=PT0.2S",
        "spring.rabbitmq.listener.simple.concurrency=4",
        "app.document-processing.lease=PT6S"
})
@ActiveProfiles("local")
class RabbitMqDocumentProcessingIT {

    private static final String HOST = System.getenv().getOrDefault("RABBITMQ_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("RABBITMQ_PORT", "5672"));

    @Autowired
    private ReadingDocumentRepository documents;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private AmqpAdmin admin;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RabbitListenerEndpointRegistry listeners;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Autowired
    private JsonMapper jsonMapper;

    static boolean rabbitMqIsReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @BeforeEach
    void setUp() {
        TestAuth.ensureUser(jdbc, TestAuth.USER_ID);
        admin.purgeQueue(DocumentProcessingConfig.DEAD_LETTER_QUEUE, false);
    }

    private ReadingDocument awaitStatus(UUID id, ProcessingStatus expected) throws InterruptedException {
        for (int i = 0; i < 250; i++) { // up to 25 s: a lease has to expire in some tests
            ReadingDocument row = documents.findById(id).orElseThrow();
            if (row.getProcessingStatus() == expected) {
                return row;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Document " + id + " did not reach " + expected);
    }

    @Test
    void aSavedDocumentIsPublishedConsumedAndCompleted() throws Exception {
        UUID id = documents.saveAndFlush(new ReadingDocument(TestAuth.USER_ID, "Im Park",
                "Am Morgen sitzt meine Großmutter auf der Bank im Park.\n\nSie füttert die Tauben.")).getId();

        ReadingDocument done = awaitStatus(id, ProcessingStatus.COMPLETED);

        assertThat(done.getWordCount()).isEqualTo(14);
        assertThat(done.getParagraphCount()).isEqualTo(2);
        assertThat(done.getProcessingQueuedAt()).isNotNull();
    }

    @Test
    void aMessageThatKeepsFailingEndsInTheDeadLetterQueue() {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(DocumentProcessingConfig.EXCHANGE, DocumentProcessingConfig.ROUTING_KEY,
                new Message("not a valid message".getBytes(StandardCharsets.UTF_8), properties));

        Message dead = rabbit.receive(DocumentProcessingConfig.DEAD_LETTER_QUEUE, 15_000);

        assertThat(dead).isNotNull();
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).isEqualTo("not a valid message");
    }

    @Test
    void duplicateDeliveriesToConcurrentConsumersCompleteTheDocumentOnce() throws Exception {
        UUID id = documents.saveAndFlush(new ReadingDocument(TestAuth.USER_ID, "Doppelt", "Ein Satz.\n\nNoch einer.")).getId();
        for (int i = 0; i < 10; i++) {
            rabbit.convertAndSend(DocumentProcessingConfig.EXCHANGE, DocumentProcessingConfig.ROUTING_KEY,
                    new DocumentProcessingRequested(id, 1));
        }

        ReadingDocument done = awaitStatus(id, ProcessingStatus.COMPLETED);
        Thread.sleep(500); // let the remaining duplicates arrive

        assertThat(documents.findById(id).orElseThrow().getProcessedAt()).isEqualTo(done.getProcessedAt());
        assertThat(rabbit.receive(DocumentProcessingConfig.DEAD_LETTER_QUEUE, 200)).isNull();
    }

    @Test
    void aDocumentLeftProcessingByACrashedConsumerIsRecovered() throws Exception {
        UUID id = documents.saveAndFlush(new ReadingDocument(TestAuth.USER_ID, "Abgestürzt", "Ein Satz.")).getId();
        jdbc.update("""
                UPDATE reading_document SET processing_status = 'PROCESSING', processing_queued_at = now() - interval '1 hour',
                    processing_started_at = now() - interval '1 hour' WHERE id = ?
                """, id);

        ReadingDocument done = awaitStatus(id, ProcessingStatus.COMPLETED);

        assertThat(done.getWordCount()).isEqualTo(2);
    }

    @Test
    void anUnroutableMessageIsReportedBackToThePublisher() throws Exception {
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());

        rabbit.convertAndSend(DocumentProcessingConfig.EXCHANGE, "no.such.route",
                new DocumentProcessingRequested(UUID.randomUUID(), 1), correlation);

        assertThat(correlation.getFuture().get(5, TimeUnit.SECONDS).ack()).isTrue();
        assertThat(correlation.getReturned()).isNotNull();
        assertThat(correlation.getReturned().getReplyText()).isEqualTo("NO_ROUTE");
    }

    @Test
    void aConsumerThatCrashesBeforeAcknowledgingDoesNotLoseTheDocument() throws Exception {
        listeners.stop();
        UUID id;
        try {
            id = documents.saveAndFlush(new ReadingDocument(TestAuth.USER_ID, "Absturz vor Ack", "Ein Satz.\n\nNoch einer.")).getId();
            awaitQueued(id);

            // The "consumer": takes the message without acknowledging it, claims the document, then dies.
            try (com.rabbitmq.client.Connection connection =
                         ((CachingConnectionFactory) connectionFactory).getRabbitConnectionFactory().newConnection()) {
                Channel channel = connection.createChannel();
                takeUnacknowledged(channel, id);
                Instant claimedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
                assertThat(documents.startProcessing(id, 1, claimedAt, claimedAt.minus(Duration.ofSeconds(6)))).isEqualTo(1);
                channel.close(); // unacknowledged: RabbitMQ redelivers it
            }
        } finally {
            listeners.start();
        }

        // The redelivery finds a live claim and is skipped; after the lease the relay re-queues a new version.
        ReadingDocument done = awaitStatus(id, ProcessingStatus.COMPLETED);
        assertThat(done.getProcessingVersion()).isEqualTo(2);
        assertThat(done.getWordCount()).isEqualTo(4);
    }

    @Test
    void aDocumentThatKeepsFailingEndsFailedWithItsMessageDeadLettered() throws Exception {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fail_dlq_test_completion() RETURNS trigger AS $$
                BEGIN
                    IF NEW.title = 'Scheitert immer' AND NEW.processing_status = 'COMPLETED' THEN
                        RAISE EXCEPTION 'simulated processing failure';
                    END IF;
                    RETURN NEW;
                END $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_dlq_test_completion BEFORE UPDATE ON reading_document
                FOR EACH ROW EXECUTE FUNCTION fail_dlq_test_completion()
                """);
        try {
            UUID id = documents.saveAndFlush(new ReadingDocument(TestAuth.USER_ID, "Scheitert immer", "Ein Satz.")).getId();

            awaitStatus(id, ProcessingStatus.FAILED);
            Message dead = rabbit.receive(DocumentProcessingConfig.DEAD_LETTER_QUEUE, 10_000);
            assertThat(dead).isNotNull();
            assertThat(jsonMapper.readValue(dead.getBody(), DocumentProcessingRequested.class))
                    .isEqualTo(new DocumentProcessingRequested(id, 1));

            // A delayed duplicate does not retry it.
            rabbit.convertAndSend(DocumentProcessingConfig.EXCHANGE, DocumentProcessingConfig.ROUTING_KEY,
                    new DocumentProcessingRequested(id, 1));
            Thread.sleep(1000);
            assertThat(documents.findById(id).orElseThrow().getProcessingStatus()).isEqualTo(ProcessingStatus.FAILED);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_dlq_test_completion ON reading_document");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_dlq_test_completion()");
        }
    }

    private void awaitQueued(UUID id) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (documents.findById(id).orElseThrow().getProcessingQueuedAt() != null) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Document " + id + " was not published");
    }

    /** Gets this document's message without acknowledging it; other messages go back to the queue. */
    private void takeUnacknowledged(Channel channel, UUID id) throws Exception {
        // Other messages are held until ours arrives: requeued at once, they would come back first every time.
        List<Long> others = new ArrayList<>();
        try {
            for (int i = 0; i < 200; i++) {
                GetResponse response = channel.basicGet(DocumentProcessingConfig.QUEUE, false);
                if (response == null) {
                    Thread.sleep(50);
                    continue;
                }
                if (jsonMapper.readValue(response.getBody(), DocumentProcessingRequested.class).documentId().equals(id)) {
                    return;
                }
                others.add(response.getEnvelope().getDeliveryTag());
            }
            throw new AssertionError("No message for document " + id);
        } finally {
            for (long tag : others) {
                channel.basicNack(tag, false, true);
            }
        }
    }
}
