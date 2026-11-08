package com.germanreader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.germanreader.repository.ReadingDocumentRepository;
import com.germanreader.repository.VocabularyEntryRepository;
import com.germanreader.security.TestAuth;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/** Two users: neither can see, change or delete the other's texts and words, even knowing their ids. */
@SpringBootTest(properties = {
        "translation.provider=mock",
        "spring.flyway.schemas=" + TranslationCacheIT.SCHEMA,
        "spring.datasource.hikari.schema=" + TranslationCacheIT.SCHEMA
})
@ActiveProfiles("local")
@AutoConfigureMockMvc
class OwnershipIT {

    private static final UUID ANNA = UUID.fromString("0f3e2b4c-1d5a-4b6c-8d7e-9f0a1b2c3d4e");
    private static final UUID BEN = UUID.fromString("1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d");
    private static final String TEXT = "Am Morgen sitzt meine Großmutter auf der Bank im Park.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReadingDocumentRepository documents;

    @Autowired
    private VocabularyEntryRepository vocabulary;

    @Autowired
    private JsonMapper jsonMapper;

    @BeforeEach
    void twoUsersWithNothingSaved() {
        TestAuth.ensureUser(jdbc, ANNA);
        TestAuth.ensureUser(jdbc, BEN);
        jdbc.update("DELETE FROM vocabulary_entry WHERE owner_id IN (?, ?)", ANNA, BEN);
        jdbc.update("DELETE FROM reading_document WHERE owner_id IN (?, ?)", ANNA, BEN);
    }

    private ResultActions as(UUID user, AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
        return mockMvc.perform(request.with(TestAuth.as(user)).with(csrf()));
    }

    private UUID idOf(ResultActions result) throws Exception {
        String json = result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(jsonMapper.readTree(json).get("id").asString());
    }

    private UUID createText(UUID user, String title) throws Exception {
        return idOf(as(user, post("/api/v1/documents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\": \"" + title + "\", \"content\": \"" + TEXT + "\"}")).andExpect(status().isCreated()));
    }

    private ResultActions saveWord(UUID user, String translation, UUID sourceDocumentId) throws Exception {
        String source = sourceDocumentId == null ? "null" : "\"" + sourceDocumentId + "\"";
        return as(user, post("/api/v1/vocabulary").contentType(MediaType.APPLICATION_JSON).content("""
                {"originalText": "Bank", "lemma": "Bank", "translation": "%s", "targetLanguage": "EN",
                 "sourceSentence": "%s", "sourceDocumentId": %s}
                """.formatted(translation, TEXT, source)));
    }

    @Test
    void eachUserListsOnlyTheirOwnTexts() throws Exception {
        createText(ANNA, "Annas Text");
        createText(BEN, "Bens Text");

        as(ANNA, get("/api/v1/documents"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Annas Text"));
        as(BEN, get("/api/v1/documents"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Bens Text"));
    }

    @Test
    void anotherUsersTextLooksExactlyLikeAMissingOne() throws Exception {
        UUID annas = createText(ANNA, "Annas Text");

        as(BEN, get("/api/v1/documents/" + annas))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
        as(BEN, patch("/api/v1/documents/" + annas).contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"Gekapert\"}"))
                .andExpect(status().isNotFound());
        as(BEN, delete("/api/v1/documents/" + annas))
                .andExpect(status().isNotFound());

        assertThat(documents.findById(annas).orElseThrow().getTitle()).isEqualTo("Annas Text");
        as(ANNA, get("/api/v1/documents/" + annas)).andExpect(status().isOk());
    }

    @Test
    void uploadsBelongToTheUploader() throws Exception {
        UUID uploaded = idOf(as(ANNA, multipart("/api/v1/documents/upload")
                .file(new MockMultipartFile("file", "Park.txt", "text/plain", TEXT.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isCreated()));

        assertThat(documents.findById(uploaded).orElseThrow().getOwnerId()).isEqualTo(ANNA);
        as(BEN, get("/api/v1/documents/" + uploaded)).andExpect(status().isNotFound());
    }

    @Test
    void anotherUsersWordsCanNeitherBeSeenNorDeleted() throws Exception {
        UUID annasWord = idOf(saveWord(ANNA, "bench", null).andExpect(status().isCreated()));

        as(BEN, get("/api/v1/vocabulary")).andExpect(jsonPath("$.length()").value(0));
        as(BEN, delete("/api/v1/vocabulary/" + annasWord))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VOCABULARY_NOT_FOUND"));

        assertThat(vocabulary.existsById(annasWord)).isTrue();
        as(ANNA, get("/api/v1/vocabulary")).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void aWordCannotBeLinkedToAnotherUsersText() throws Exception {
        UUID annas = createText(ANNA, "Annas Text");

        UUID bensWord = idOf(saveWord(BEN, "bench", annas)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceDocumentId").isEmpty())
                .andExpect(jsonPath("$.sourceDocumentTitle").isEmpty()));

        assertThat(vocabulary.findById(bensWord).orElseThrow().getSourceDocumentId()).isNull();
    }

    @Test
    void twoUsersCanSaveTheSameMeaning() throws Exception {
        UUID annasWord = idOf(saveWord(ANNA, "bench", null).andExpect(status().isCreated()));
        UUID bensWord = idOf(saveWord(BEN, "bench", null).andExpect(status().isCreated()));

        assertThat(bensWord).isNotEqualTo(annasWord);
        // Saving it again is still a duplicate for the same user.
        assertThat(idOf(saveWord(BEN, "Bench", null).andExpect(status().isOk()))).isEqualTo(bensWord);
    }
}
