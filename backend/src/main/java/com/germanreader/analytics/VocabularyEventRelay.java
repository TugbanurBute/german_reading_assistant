package com.germanreader.analytics;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.KafkaException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
public class VocabularyEventRelay {

    static final int BATCH_SIZE = 50;
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transaction;
    private final JsonMapper jsonMapper;
    private final boolean enabled;
    private final AtomicBoolean kafkaDown = new AtomicBoolean(false);

    public VocabularyEventRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate transaction,
                                JsonMapper jsonMapper, @Value("${app.vocabulary-events.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.transaction = transaction;
        this.jsonMapper = jsonMapper;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.vocabulary-events.relay-interval:PT2S}")
    public void run() {
        if (enabled) {
            publishPending();
        }
    }

    public int publishPending() {
        Integer published = transaction.execute(status -> {
            List<VocabularyWordSaved> pending = jdbc.query("""
                    SELECT saved_event_id, id, owner_id, created_at FROM vocabulary_entry
                    WHERE event_published_at IS NULL
                    ORDER BY created_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                    """, (row, i) -> new VocabularyWordSaved(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                    row.getObject(3, UUID.class), row.getObject(4, Timestamp.class).toInstant()), BATCH_SIZE);
            int count = 0;
            for (VocabularyWordSaved event : pending) {
                if (!send(event)) {
                    break;
                }
                jdbc.update("UPDATE vocabulary_entry SET event_published_at = now() WHERE id = ?", event.entryId());
                count++;
            }
            return count;
        });
        return published == null ? 0 : published;
    }

    private boolean send(VocabularyWordSaved event) {
        try {
            kafka.send(AnalyticsConfig.TOPIC, event.userId().toString(), jsonMapper.writeValueAsString(event))
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (kafkaDown.getAndSet(false)) {
                log.info("Kafka is reachable again; publishing vocabulary events");
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException | KafkaException e) {
            if (!kafkaDown.getAndSet(true)) {
                log.warn("Kafka is unreachable ({}); vocabulary events wait in PostgreSQL", e.getClass().getSimpleName());
            }
            return false;
        }
    }
}
