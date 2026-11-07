package com.germanreader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.germanreader.dto.TargetLanguage;
import com.germanreader.dto.document.DocumentLimits;
import com.germanreader.entity.ReadingDocument;
import com.germanreader.entity.VocabularyEntry;
import com.germanreader.repository.ReadingDocumentRepository;
import com.germanreader.repository.VocabularyEntryRepository;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The reading library end to end: HTTP, validation, Flyway schema and PostgreSQL. */
@SpringBootTest(properties = {
        "translation.provider=mock",
        "spring.flyway.schemas=" + TranslationCacheIT.SCHEMA,
        "spring.datasource.hikari.schema=" + TranslationCacheIT.SCHEMA
})
@ActiveProfiles("local")
@AutoConfigureMockMvc
class ReadingDocumentIT {

    private static final String URL = "/api/v1/documents";
    private static final String TEXT = "Am Morgen sitzt meine Großmutter auf der Bank im Park.\nSie füttert die Tauben.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReadingDocumentRepository repository;

    @Autowired
    private VocabularyEntryRepository vocabularyRepository;

    @Autowired
    private JsonMapper jsonMapper;

    @BeforeEach
    void emptyLibrary() {
        vocabularyRepository.deleteAllInBatch();
        repository.deleteAllInBatch();
    }

    private UUID createDocument(String title, String content) throws Exception {
        String body = jsonMapper.writeValueAsString(Map.of("title", title, "content", content));
        String response = mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode json = jsonMapper.readTree(response);
        return UUID.fromString(json.get("id").asString());
    }

    @Test
    void createdDocumentIsStoredAndCanBeRead() throws Exception {
        UUID id = createDocument("  Im Park  ", TEXT);

        ReadingDocument row = repository.findById(id).orElseThrow();
        assertThat(row.getTitle()).isEqualTo("Im Park");
        assertThat(row.getContent()).isEqualTo(TEXT);
        assertThat(row.getCreatedAt()).isNotNull().isEqualTo(row.getUpdatedAt());

        mockMvc.perform(get(URL + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Im Park"))
                .andExpect(jsonPath("$.content").value(TEXT))
                .andExpect(jsonPath("$.createdAt").value(row.getCreatedAt().toString()));
    }

    @Test
    void theLargestAllowedDocumentFitsIntoTheTextColumn() throws Exception {
        String content = "ä".repeat(DocumentLimits.MAX_CONTENT_LENGTH);

        UUID id = createDocument("Groß", content);

        assertThat(repository.findById(id).orElseThrow().getContent()).isEqualTo(content);
    }

    @Test
    void listReturnsNewestFirstWithoutContent() throws Exception {
        createDocument("Erster Text", TEXT);
        createDocument("Zweiter Text", TEXT);

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("Zweiter Text"))
                .andExpect(jsonPath("$[1].title").value("Erster Text"))
                .andExpect(jsonPath("$[0].content").doesNotExist())
                .andExpect(jsonPath("$[0].createdAt").exists());
    }

    @Test
    void deleteRemovesTheRowAndASecondDeleteIsNotFound() throws Exception {
        UUID id = createDocument("Im Park", TEXT);

        mockMvc.perform(delete(URL + "/" + id)).andExpect(status().isNoContent());

        assertThat(repository.existsById(id)).isFalse();
        mockMvc.perform(delete(URL + "/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
        mockMvc.perform(get(URL + "/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void renameChangesOnlyTheTitleAndUpdatedAt() throws Exception {
        UUID id = createDocument("Im Park", TEXT);
        ReadingDocument before = repository.findById(id).orElseThrow();
        VocabularyEntry word = vocabularyRepository.saveAndFlush(new VocabularyEntry(
                "Bank", "Bank", null, null, "bench", TargetLanguage.EN, null, id));

        mockMvc.perform(patch(URL + "/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"  Großmutters Bank  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.title").value("Großmutters Bank"))
                .andExpect(jsonPath("$.content").doesNotExist());

        ReadingDocument after = repository.findById(id).orElseThrow();
        assertThat(after.getTitle()).isEqualTo("Großmutters Bank");
        assertThat(after.getContent()).isEqualTo(TEXT);
        assertThat(after.getCreatedAt()).isEqualTo(before.getCreatedAt());
        assertThat(after.getUpdatedAt()).isAfter(before.getUpdatedAt());
        // Same id, so saved words still point to the text and show its new title.
        assertThat(vocabularyRepository.findById(word.getId()).orElseThrow().getSourceDocumentId()).isEqualTo(id);
        mockMvc.perform(get("/api/v1/vocabulary"))
                .andExpect(jsonPath("$[0].sourceDocumentTitle").value("Großmutters Bank"));
        mockMvc.perform(get(URL + "/" + id))
                .andExpect(jsonPath("$.updatedAt").value(after.getUpdatedAt().toString()));
    }

    @Test
    void renamingIsValidatedAndMissingDocumentsAreNotFound() throws Exception {
        UUID id = createDocument("Im Park", TEXT);

        mockMvc.perform(patch(URL + "/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"title\": \" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(patch(URL + "/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"" + "a".repeat(DocumentLimits.MAX_TITLE_LENGTH + 1) + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(repository.findById(id).orElseThrow().getTitle()).isEqualTo("Im Park");

        mockMvc.perform(patch(URL + "/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Neu\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void invalidDocumentIsNotStored() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"title": "Leer", "content": "   "}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(repository.count()).isZero();
    }

    @Test
    void uploadedUtf8FileIsStoredWithUmlautsIntact() throws Exception {
        byte[] withBomAndCrlf = ("﻿" + TEXT.replace("\n", "\r\n") + "\r\n").getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "Großmutter.txt", "text/plain", withBomAndCrlf);

        mockMvc.perform(multipart(URL + "/upload").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Großmutter"));

        ReadingDocument row = repository.findAll().getFirst();
        assertThat(row.getTitle()).isEqualTo("Großmutter");
        assertThat(row.getContent()).isEqualTo(TEXT);
    }

    @Test
    void invalidUploadsAreRejectedAndNotStored() throws Exception {
        MockMultipartFile latin1 = new MockMultipartFile("file", "brief.txt", "text/plain",
                "Grüße".getBytes(StandardCharsets.ISO_8859_1));
        MockMultipartFile pdf = new MockMultipartFile("file", "buch.pdf", "application/pdf",
                "%PDF-1.7".getBytes(StandardCharsets.US_ASCII));
        // The MIME type claims text, but the bytes are binary.
        MockMultipartFile binary = new MockMultipartFile("file", "bild.txt", "text/plain",
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 13});

        for (MockMultipartFile file : new MockMultipartFile[]{latin1, pdf, binary}) {
            mockMvc.perform(multipart(URL + "/upload").file(file))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_UPLOAD"));
        }
        mockMvc.perform(multipart(URL + "/upload"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_UPLOAD"));

        assertThat(repository.count()).isZero();
    }

    @Test
    void uploadWithTooManyCharactersIsRejected() throws Exception {
        byte[] tooLong = "a".repeat(DocumentLimits.MAX_CONTENT_LENGTH + 1).getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(multipart(URL + "/upload").file(new MockMultipartFile("file", "lang.txt", "text/plain", tooLong)))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("UPLOAD_TOO_LARGE"));

        assertThat(repository.count()).isZero();
    }
}
