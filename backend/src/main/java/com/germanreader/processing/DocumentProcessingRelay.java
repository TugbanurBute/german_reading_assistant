package com.germanreader.processing;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Component
public class DocumentProcessingRelay {

    static final int BATCH_SIZE = 50;

    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate transaction;
    private final boolean enabled;
    private final Duration lease;
    private final Duration confirmTimeout;
    private final AtomicBoolean brokerDown = new AtomicBoolean(false);

    public DocumentProcessingRelay(JdbcTemplate jdbc, RabbitTemplate rabbit, TransactionTemplate transaction,
                                   @Value("${app.document-processing.enabled:true}") boolean enabled,
                                   @Value("${app.document-processing.lease:PT5M}") Duration lease,
                                   @Value("${app.document-processing.confirm-timeout:PT5S}") Duration confirmTimeout) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.transaction = transaction;
        this.enabled = enabled;
        this.lease = lease;
        this.confirmTimeout = confirmTimeout;
    }

    @Scheduled(fixedDelayString = "${app.document-processing.relay-interval:PT2S}")
    public void run() {
        if (enabled) {
            requeueAbandoned();
            publishPending();
        }
    }

    public int requeueAbandoned() {
        int requeued = jdbc.update("""
                UPDATE reading_document
                SET processing_status = 'PENDING', processing_started_at = NULL, processing_queued_at = NULL,
                    processing_version = processing_version + 1
                WHERE builtin_key IS NULL
                  AND ((processing_status = 'PROCESSING' AND (processing_started_at IS NULL OR processing_started_at < ?))
                       OR (processing_status = 'PENDING' AND processing_queued_at < ?))
                """, Timestamp.from(Instant.now().minus(lease)), Timestamp.from(Instant.now().minus(lease)));
        if (requeued > 0) {
            log.warn("Re-queued {} document(s) whose processing was abandoned or whose message was lost", requeued);
        }
        return requeued;
    }

    public int publishPending() {
        int count = 0;
        try {
            while (count < BATCH_SIZE && Boolean.TRUE.equals(transaction.execute(status -> publishNext()))) {
                count++;
            }
        } catch (DataAccessException | TransactionException e) {
            log.warn("Outbox update failed ({}); the document will be published again", e.getClass().getSimpleName());
        }
        return count;
    }

    private Boolean publishNext() {
        List<DocumentProcessingRequested> next = jdbc.query("""
                SELECT id, processing_version FROM reading_document
                WHERE processing_queued_at IS NULL AND processing_status = 'PENDING' AND builtin_key IS NULL
                ORDER BY created_at
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """, (row, i) -> new DocumentProcessingRequested(row.getObject(1, UUID.class), row.getInt(2)));
        if (next.isEmpty() || !publish(next.getFirst())) {
            return false;
        }
        jdbc.update("UPDATE reading_document SET processing_queued_at = now() WHERE id = ?", next.getFirst().documentId());
        return true;
    }

    private boolean publish(DocumentProcessingRequested message) {
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        try {
            rabbit.convertAndSend(DocumentProcessingConfig.EXCHANGE, DocumentProcessingConfig.ROUTING_KEY, message, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack()) {
                log.warn("RabbitMQ refused document {} ({}); it stays in the outbox", message.documentId(), confirm.reason());
                return false;
            }
            if (correlation.getReturned() != null) {
                log.warn("Document {} could not be routed to a queue ({}); it stays in the outbox",
                        message.documentId(), correlation.getReturned().getReplyText());
                return false;
            }
            if (brokerDown.getAndSet(false)) {
                log.info("RabbitMQ is reachable again; publishing document processing messages");
            }
            return true;
        } catch (AmqpException | ExecutionException | TimeoutException e) {
            if (!brokerDown.getAndSet(true)) {
                log.warn("RabbitMQ is unreachable or did not confirm ({}); document processing messages wait in PostgreSQL",
                        e.getClass().getSimpleName());
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
