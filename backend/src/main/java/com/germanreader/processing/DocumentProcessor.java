package com.germanreader.processing;

import com.germanreader.repository.ReadingDocumentRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
public class DocumentProcessor {

    private final ReadingDocumentRepository documents;
    private final JsonMapper jsonMapper;

    public DocumentProcessor(ReadingDocumentRepository documents, JsonMapper jsonMapper) {
        this.documents = documents;
        this.jsonMapper = jsonMapper;
    }

    @RabbitListener(queues = DocumentProcessingConfig.QUEUE, autoStartup = "${app.document-processing.enabled:true}")
    public void process(DocumentProcessingRequested message) {
        if (documents.startProcessing(message.documentId(), message.version()) == 0) {
            log.debug("Skipping document {} v{}: deleted, stale or already processed", message.documentId(), message.version());
            return;
        }
        String content = documents.findContentById(message.documentId()).orElse(null);
        if (content == null) {
            log.debug("Document {} was deleted during processing", message.documentId());
            return;
        }
        DocumentStats stats = DocumentStats.of(content);
        documents.completeProcessing(message.documentId(), message.version(), stats.words(), stats.characters(),
                stats.paragraphs(), Instant.now().truncatedTo(ChronoUnit.MICROS));
        log.debug("Processed document {} v{}", message.documentId(), message.version());
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
