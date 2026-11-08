package com.germanreader.processing;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;


@Slf4j
@Component
public class DocumentProcessingRelay {

    static final int BATCH_SIZE = 50;
    private static final long CONFIRM_TIMEOUT_MS = 5000;

    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate transaction;
    private final boolean enabled;
    /** Logs a RabbitMQ outage once instead of on every run. */
    private final AtomicBoolean brokerDown = new AtomicBoolean(false);

    public DocumentProcessingRelay(JdbcTemplate jdbc, RabbitTemplate rabbit, TransactionTemplate transaction,
                                   @Value("${app.document-processing.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.transaction = transaction;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.document-processing.relay-interval:PT2S}")
    public void run() {
        if (enabled) {
            publishPending();
        }
    }

    public int publishPending() {
        Integer published = transaction.execute(status -> {
            // SKIP LOCKED: several backend instances never publish the same document at the same time.
            List<DocumentProcessingRequested> pending = jdbc.query("""
                    SELECT id, processing_version FROM reading_document
                    WHERE processing_queued_at IS NULL
                    ORDER BY created_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                    """, (row, i) -> new DocumentProcessingRequested(row.getObject(1, UUID.class), row.getInt(2)), BATCH_SIZE);
            int count = 0;
            for (DocumentProcessingRequested message : pending) {
                if (!publish(message)) {
                    break;
                }
                jdbc.update("UPDATE reading_document SET processing_queued_at = now() WHERE id = ?", message.documentId());
                count++;
            }
            return count;
        });
        return published == null ? 0 : published;
    }

    private boolean publish(DocumentProcessingRequested message) {
        try {
            rabbit.invoke(operations -> {
                operations.convertAndSend(DocumentProcessingConfig.EXCHANGE, DocumentProcessingConfig.ROUTING_KEY, message);
                operations.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
                return null;
            });
            if (brokerDown.getAndSet(false)) {
                log.info("RabbitMQ is reachable again; publishing document processing messages");
            }
            return true;
        } catch (AmqpException e) {
            if (!brokerDown.getAndSet(true)) {
                log.warn("RabbitMQ is unreachable ({}); document processing messages wait in PostgreSQL",
                        e.getClass().getSimpleName());
            }
            return false;
        }
    }
}
