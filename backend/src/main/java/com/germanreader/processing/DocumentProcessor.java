package com.germanreader.processing;

import com.germanreader.repository.ReadingDocumentRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
public class DocumentProcessor {

    private final ReadingDocumentRepository documents;
    private final JsonMapper jsonMapper;
    private final Duration lease;

    public DocumentProcessor(ReadingDocumentRepository documents, JsonMapper jsonMapper,
                             @Value("${app.document-processing.lease:PT5M}") Duration lease) {
        this.documents = documents;
        this.jsonMapper = jsonMapper;
        this.lease = lease;
    }

    @RabbitListener(queues = DocumentProcessingConfig.QUEUE, autoStartup = "${app.document-processing.enabled:true}")
    public void process(DocumentProcessingRequested message) {
        // The claim's start time is also its fencing token for completing or releasing it.
        Instant claimedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (documents.startProcessing(message.documentId(), message.version(), claimedAt, claimedAt.minus(lease)) == 0) {
            log.debug("Skipping document {} v{}: deleted, stale, done, failed or being processed",
                    message.documentId(), message.version());
            return;
        }
        try {
            String content = documents.findContentById(message.documentId()).orElse(null);
            if (content == null) {
                log.debug("Document {} was deleted during processing", message.documentId());
                return;
            }
            DocumentStats stats = DocumentStats.of(content);
            int completed = documents.completeProcessing(message.documentId(), message.version(), claimedAt, stats.words(),
                    stats.characters(), stats.paragraphs(), Instant.now().truncatedTo(ChronoUnit.MICROS));
            if (completed == 0) {
                log.warn("Claim on document {} v{} expired and was taken over; result discarded",
                        message.documentId(), message.version());
                return;
            }
            log.debug("Processed document {} v{}", message.documentId(), message.version());
        } catch (RuntimeException e) {
            release(message, claimedAt);
            throw e; // retried, then dead-lettered
        }
    }

    private void release(DocumentProcessingRequested message, Instant claimedAt) {
        try {
            documents.releaseProcessing(message.documentId(), message.version(), claimedAt);
        } catch (RuntimeException e) {
            log.debug("Could not release document {}; it is re-queued after the lease", message.documentId());
        }
    }

    public void markFailed(Message message, Throwable cause) {
        try {
            DocumentProcessingRequested request = jsonMapper.readValue(message.getBody(), DocumentProcessingRequested.class);
            documents.failProcessing(request.documentId(), request.version());
            log.warn("Processing document {} failed after retries ({}); message dead-lettered",
                    request.documentId(), cause.getClass().getSimpleName());
        } catch (RuntimeException e) {
            log.warn("Dead-lettering a document processing message ({}); its status could not be updated",
                    cause.getClass().getSimpleName());
        }
    }
}
