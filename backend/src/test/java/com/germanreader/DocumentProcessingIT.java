package com.germanreader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitOperations;
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
        "spring.datasource.hikari.schema=" + TranslationCacheIT.SCHEMA
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

    private void brokerAccepts() {
        RabbitOperations operations = mock(RabbitOperations.class);
        org.mockito.Mockito.doAnswer(invocation -> published.add(invocation.getArgument(2)))
                .when(operations).convertAndSend(eq(DocumentProcessingConfig.EXCHANGE), eq(DocumentProcessingConfig.ROUTING_KEY),
                        any(Object.class));
        org.mockito.Mockito.doAnswer(invocation ->
                invocation.<RabbitOperations.OperationsCallback<?>>getArgument(0).doInRabbit(operations))
                .when(rabbit).invoke(any());
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
        given(rabbit.invoke(any())).willThrow(new AmqpConnectException(new java.net.ConnectException("refused")));

        assertThat(relay.publishPending()).isZero();
        assertThat(row(id).getProcessingQueuedAt()).isNull();

        brokerAccepts();
        assertThat(relay.publishPending()).isEqualTo(1);
        assertThat(published).containsExactly(new DocumentProcessingRequested(id, 1));
    }

    @Test
    void anUnconfirmedPublishIsNotMarkedAsQueued() throws Exception {
        UUID id = createDocument();
        RabbitOperations operations = mock(RabbitOperations.class);
        org.mockito.Mockito.doThrow(new org.springframework.amqp.AmqpTimeoutException("no confirm"))
                .when(operations).waitForConfirmsOrDie(anyLong());
        given(rabbit.invoke(any())).willAnswer(invocation ->
                invocation.<RabbitOperations.OperationsCallback<?>>getArgument(0).doInRabbit(operations));

        assertThat(relay.publishPending()).isZero();
        assertThat(row(id).getProcessingQueuedAt()).isNull();
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

        jdbc.update("UPDATE reading_document SET processing_status = 'PROCESSING' WHERE id = ?", id);

        processor.markFailed(message, new IllegalStateException("boom"));

        assertThat(row(id).getProcessingStatus()).isEqualTo(ProcessingStatus.FAILED);
        mockMvc.perform(get("/api/v1/documents/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value(TEXT))
                .andExpect(jsonPath("$.processingStatus").value("FAILED"));

        processor.process(new DocumentProcessingRequested(id, 1));
        assertThat(row(id).getProcessingStatus()).isEqualTo(ProcessingStatus.COMPLETED);
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
