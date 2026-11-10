package com.germanreader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.germanreader.analytics.AnalyticsService;
import com.germanreader.analytics.VocabularyWordSaved;
import com.germanreader.processing.DocumentProcessingRequested;
import com.germanreader.processing.DocumentProcessor;
import com.germanreader.security.AuthTokens;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "translation.provider=mock",
        "spring.flyway.schemas=" + TranslationCacheIT.SCHEMA,
        "spring.datasource.hikari.schema=" + TranslationCacheIT.SCHEMA
})
@ActiveProfiles("local")
@AutoConfigureMockMvc
class AccountDeletionIT {

    private static final String PASSWORD = "richtig-geheim-42";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private AnalyticsService analytics;

    @Autowired
    private DocumentProcessor documentProcessor;

    private record Account(UUID id, String email, Cookie cookie, UUID documentId) {
    }

    private static final String XSRF_HEADER = "X-XSRF-TOKEN";

    private Account anna;
    private Account ben;

    private Cookie xsrf;

    @BeforeEach
    void twoAccountsWithData() throws Exception {
        xsrf = mockMvc.perform(get("/api/v1/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf).isNotNull();
        anna = accountWithData();
        ben = accountWithData();
    }

    private Account accountWithData() throws Exception {
        String email = "delete-" + UUID.randomUUID() + "@example.com";
        var result = mockMvc.perform(post("/api/v1/auth/register").cookie(xsrf).header(XSRF_HEADER, xsrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email + "\", \"password\": \"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        Cookie cookie = result.getResponse().getCookie(AuthTokens.COOKIE);
        UUID id = UUID.fromString(jsonMapper.readTree(result.getResponse().getContentAsString()).get("id").asString());

        String document = mockMvc.perform(post("/api/v1/documents").cookie(cookie).cookie(xsrf).header(XSRF_HEADER, xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Park\", \"content\": \"Am Morgen sitzt meine Großmutter auf der Bank im Park.\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        UUID documentId = UUID.fromString(jsonMapper.readTree(document).get("id").asString());
        mockMvc.perform(post("/api/v1/vocabulary").cookie(cookie).cookie(xsrf).header(XSRF_HEADER, xsrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originalText": "Bank", "lemma": "Bank", "translation": "bench", "targetLanguage": "EN",
                                 "sourceDocumentId": "%s"}
                                """.formatted(documentId)))
                .andExpect(status().isCreated());
        analytics.record(new VocabularyWordSaved(UUID.randomUUID(), UUID.randomUUID(), id, Instant.now()));
        return new Account(id, email, cookie, documentId);
    }

    private ResultActions deleteAccount(Account account, String password) throws Exception {
        return mockMvc.perform(delete("/api/v1/auth/me").cookie(account.cookie()).cookie(xsrf).header(XSRF_HEADER, xsrf.getValue())
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\": \"" + password + "\"}"));
    }

    private int rowsOf(UUID user) {
        Integer count = jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM app_user WHERE id = ?)
                     + (SELECT count(*) FROM reading_document WHERE owner_id = ?)
                     + (SELECT count(*) FROM vocabulary_entry WHERE owner_id = ?)
                     + (SELECT count(*) FROM vocabulary_daily_stats WHERE user_id = ?)
                """, Integer.class, user, user, user, user);
        return count == null ? 0 : count;
    }

    @Test
    void theRightPasswordDeletesTheAccountAndEverythingInIt() throws Exception {
        assertThat(rowsOf(anna.id())).isEqualTo(4);

        deleteAccount(anna, PASSWORD)
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, Matchers.allOf(
                        Matchers.startsWith(AuthTokens.COOKIE + "=;"), Matchers.containsString("Max-Age=0"))));

        assertThat(rowsOf(anna.id())).isZero();
        assertThat(rowsOf(ben.id())).isEqualTo(4);
    }

    @Test
    void aWrongPasswordDeletesNothing() throws Exception {
        deleteAccount(anna, "falsches-passwort")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WRONG_PASSWORD"));

        assertThat(rowsOf(anna.id())).isEqualTo(4);
    }

    @Test
    void anotherUsersPasswordDoesNotDeleteTheirAccountOrYours() throws Exception {
        // Ben's login with Anna's password: the endpoint only ever means the token's own account.
        mockMvc.perform(delete("/api/v1/auth/me").cookie(ben.cookie()).cookie(xsrf).header(XSRF_HEADER, xsrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\": \"wrong-for-ben-but-anything\"}"))
                .andExpect(status().isForbidden());

        assertThat(rowsOf(anna.id())).isEqualTo(4);
        assertThat(rowsOf(ben.id())).isEqualTo(4);
    }

    @Test
    void withoutALoginOrACsrfTokenNothingIsDeleted() throws Exception {
        mockMvc.perform(delete("/api/v1/auth/me").cookie(xsrf).header(XSRF_HEADER, xsrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\": \"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/auth/me").cookie(anna.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\": \"" + PASSWORD + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(rowsOf(anna.id())).isEqualTo(4);
    }

    @Test
    void theOldLoginStopsWorkingEverywhere() throws Exception {
        deleteAccount(anna, PASSWORD).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/documents").cookie(anna.cookie()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(post("/api/v1/documents").cookie(anna.cookie()).cookie(xsrf).header(XSRF_HEADER, xsrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"T\", \"content\": \"Text.\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login").cookie(xsrf).header(XSRF_HEADER, xsrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + anna.email() + "\", \"password\": \"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        assertThat(rowsOf(anna.id())).isZero();
    }

    @Test
    void lateMessagesForTheDeletedAccountRecreateNothing() throws Exception {
        deleteAccount(anna, PASSWORD).andExpect(status().isNoContent());

        assertThatCode(() -> analytics.record(
                new VocabularyWordSaved(UUID.randomUUID(), UUID.randomUUID(), anna.id(), Instant.now()))).doesNotThrowAnyException();
        assertThatCode(() -> documentProcessor.process(new DocumentProcessingRequested(anna.documentId(), 1)))
                .doesNotThrowAnyException();

        assertThat(rowsOf(anna.id())).isZero();
    }
}
