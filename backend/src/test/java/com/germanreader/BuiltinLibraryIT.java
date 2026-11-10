package com.germanreader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.germanreader.library.BuiltinLibrarySeeder;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "translation.provider=mock",
        "spring.flyway.schemas=" + TranslationCacheIT.SCHEMA,
        "spring.datasource.hikari.schema=" + TranslationCacheIT.SCHEMA
})
@ActiveProfiles("local")
@AutoConfigureMockMvc
class BuiltinLibraryIT {

    private static final UUID ANNA = UUID.fromString("5d1f3c2b-8a4e-4f6d-9b7c-0e1a2b3c4d5e");
    private static final UUID BEN = UUID.fromString("6e2a4d3c-9b5f-4a7e-8c8d-1f2b3c4d5e6f");
    private static final String KEY = "a1-02-meine-familie";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private BuiltinLibrarySeeder seeder;

    @BeforeEach
    void usersAndTexts() {
        seeder.seed();
        TestAuth.ensureUser(jdbc, ANNA);
        TestAuth.ensureUser(jdbc, BEN);
        jdbc.update("DELETE FROM vocabulary_entry WHERE owner_id IN (?, ?)", ANNA, BEN);
    }

    private ResultActions as(UUID user, AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
        return mockMvc.perform(request.with(TestAuth.as(user)).with(csrf()));
    }

    private UUID idOf(String key) {
        return jdbc.queryForObject("SELECT id FROM reading_document WHERE builtin_key = ?", UUID.class, key);
    }

    private int builtinRows() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM reading_document WHERE builtin_key IS NOT NULL", Integer.class);
        return count == null ? 0 : count;
    }

    @Test
    void everyUserSeesAllFifteenTextsInLevelOrder() throws Exception {
        as(ANNA, get("/api/v1/library"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(15))
                .andExpect(jsonPath("$[0].level").value("A1"))
                .andExpect(jsonPath("$[0].title").value("Mein Alltag"))
                .andExpect(jsonPath("$[0].readingMinutes").isNumber())
                .andExpect(jsonPath("$[0].description").isString())
                .andExpect(jsonPath("$[0].content").doesNotExist())
                .andExpect(jsonPath("$[14].level").value("B1"));
        as(BEN, get("/api/v1/library")).andExpect(jsonPath("$.length()").value(15));
    }

    @Test
    void filtersByLevel() throws Exception {
        for (String level : new String[] {"A1", "A2", "B1"}) {
            as(ANNA, get("/api/v1/library").param("level", level))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(5))
                    .andExpect(jsonPath("$[*].level").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(level))));
        }
        as(ANNA, get("/api/v1/library").param("level", "C2")).andExpect(status().isBadRequest());
    }

    @Test
    void opensATextWithItsContent() throws Exception {
        as(ANNA, get("/api/v1/library/{id}", idOf(KEY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Meine Familie"))
                .andExpect(jsonPath("$.level").value("A1"))
                .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.startsWith("Das ist meine Familie.")));
        as(ANNA, get("/api/v1/library/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void needsALogin() throws Exception {
        mockMvc.perform(get("/api/v1/library")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/library/{id}", idOf(KEY))).andExpect(status().isUnauthorized());
    }

    @Test
    void personalDocumentEndpointsCannotReadChangeOrDeleteBuiltinTexts() throws Exception {
        UUID id = idOf(KEY);
        String before = jdbc.queryForObject("SELECT title || content FROM reading_document WHERE id = ?", String.class, id);

        as(ANNA, get("/api/v1/documents/{id}", id)).andExpect(status().isNotFound());
        as(ANNA, patch("/api/v1/documents/{id}", id).contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"Meins\"}"))
                .andExpect(status().isNotFound());
        as(ANNA, delete("/api/v1/documents/{id}", id)).andExpect(status().isNotFound());
        as(ANNA, get("/api/v1/documents")).andExpect(jsonPath("$[?(@.id == '" + id + "')]").isEmpty());

        assertThat(jdbc.queryForObject("SELECT title || content FROM reading_document WHERE id = ?", String.class, id))
                .isEqualTo(before);
        // There is no endpoint that changes built-in texts at all.
        as(ANNA, delete("/api/v1/library/{id}", id)).andExpect(status().is4xxClientError());
        as(ANNA, patch("/api/v1/library/{id}", id).contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"x\"}"))
                .andExpect(status().is4xxClientError());
        assertThat(builtinRows()).isEqualTo(15);
    }

    @Test
    void seedingAgainCreatesNoDuplicatesAndChangesNothing() {
        UUID id = idOf(KEY);

        assertThat(seeder.seed()).isZero();
        assertThat(seeder.seed()).isZero();

        assertThat(builtinRows()).isEqualTo(15);
        assertThat(idOf(KEY)).isEqualTo(id);
    }

    @Test
    void aChangedTextIsUpdatedInPlaceKeepingItsId() {
        UUID id = idOf(KEY);
        jdbc.update("UPDATE reading_document SET content = 'Alter Text.', word_count = 2 WHERE id = ?", id);

        assertThat(seeder.seed()).isEqualTo(1);

        assertThat(idOf(KEY)).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT content FROM reading_document WHERE id = ?", String.class, id))
                .startsWith("Das ist meine Familie.");
        assertThat(jdbc.queryForObject("SELECT word_count FROM reading_document WHERE id = ?", Integer.class, id))
                .isGreaterThan(150);
        assertThat(builtinRows()).isEqualTo(15);
    }

    @Test
    void wordsSavedFromABuiltinTextKeepTheSourceAndBelongToEachUser() throws Exception {
        UUID id = idOf(KEY);
        String word = """
                {"originalText": "Großeltern", "lemma": "Großeltern", "translation": "grandparents", "targetLanguage": "EN",
                 "sourceSentence": "Meine Großeltern wohnen nicht in Leipzig.", "sourceDocumentId": "%s"}
                """.formatted(id);

        as(ANNA, post("/api/v1/vocabulary").contentType(MediaType.APPLICATION_JSON).content(word))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceDocumentId").value(id.toString()))
                .andExpect(jsonPath("$.sourceDocumentTitle").value("Meine Familie"));
        as(BEN, post("/api/v1/vocabulary").contentType(MediaType.APPLICATION_JSON).content(word))
                .andExpect(status().isCreated());

        String annasWords = as(ANNA, get("/api/v1/vocabulary")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(jsonMapper.readTree(annasWords).size()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vocabulary_entry WHERE source_document_id = ? AND owner_id IN (?, ?)",
                Integer.class, id, ANNA, BEN)).isEqualTo(2);
    }
}
