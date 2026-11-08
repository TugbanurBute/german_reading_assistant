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
import java.util.UUID;
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
        "app.document-processing.relay-interval=PT0.2S"
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
        for (int i = 0; i < 100; i++) {
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
}
