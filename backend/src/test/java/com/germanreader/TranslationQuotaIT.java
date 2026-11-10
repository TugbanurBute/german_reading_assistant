package com.germanreader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.germanreader.exception.ErrorCode;
import com.germanreader.ratelimit.ProviderCallBudget;
import com.germanreader.ratelimit.RateLimitExceededException;
import com.germanreader.security.TestAuth;
import com.germanreader.service.translation.TranslationProvider;
import com.germanreader.service.translation.gemini.GeminiTranslationProvider;
import com.google.genai.Models;
import com.google.genai.errors.ServerException;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
        "spring.datasource.hikari.schema=" + TranslationCacheIT.SCHEMA,
        "app.rate-limit.translations.enabled=false",
        "app.quota.enabled=true",
        "app.quota.translations-per-user-per-day=4",
        "app.quota.provider-calls-per-day=3"
})
@ActiveProfiles("local")
@AutoConfigureMockMvc
class TranslationQuotaIT {

    private static final String ANSWER = """
            {"lemma": "Bank", "partOfSpeech": "NOUN", "article": "die", "plural": "Banken",
             "grammaticalCase": "DATIVE", "number": "SINGULAR", "translation": "bank",
             "sentenceTranslation": "Afterwards she takes her money to the bank."}
            """;
    /** A model name of its own: nothing from earlier runs is in the cache for it. */
    private static final String MODEL = "quota-it-" + UUID.randomUUID().toString().substring(0, 8);
    private static final Models MODELS = mock(Models.class);
    /** The budget the provider uses; a new one per test. */
    private static volatile String budgetName;

    @TestConfiguration(proxyBeanMethods = false)
    static class GeminiWithMockedClient {

        @Bean
        @Primary
        TranslationProvider quotaTestProvider(JsonMapper jsonMapper, ProviderCallBudget budget) {
            return new GeminiTranslationProvider(MODELS, MODEL, jsonMapper, 2, () -> budget.acquire(budgetName));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ProviderCallBudget budget;

    private UUID user;
    private String sentence;

    @BeforeEach
    void freshBudgetUserAndSentence() {
        budgetName = "it-" + UUID.randomUUID().toString().substring(0, 12);
        user = UUID.randomUUID();
        // A different sentence per test, so each test starts with an empty cache.
        sentence = "Danach bringt sie ihr Geld zur Bank, Tag " + UUID.randomUUID().toString().substring(0, 6) + ".";
        reset(MODELS);
        GenerateContentResponse response = mock(GenerateContentResponse.class);
        given(response.text()).willReturn(ANSWER);
        given(MODELS.generateContent(anyString(), anyString(), any(GenerateContentConfig.class))).willReturn(response);
    }

    private ResultActions translate(String word) throws Exception {
        return mockMvc.perform(post("/api/v1/translations").with(TestAuth.as(user)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"selectedText": "%s", "context": "%s", "sourceLanguage": "DE", "targetLanguage": "EN",
                         "translationType": "WORD"}
                        """.formatted(word, sentence)));
    }

    private Integer callsToday() {
        return jdbc.query("SELECT calls FROM provider_daily_usage WHERE provider = ? AND day = ?",
                rs -> rs.next() ? rs.getInt(1) : 0, budgetName, Date.valueOf(LocalDate.now(ZoneOffset.UTC)));
    }

    private void verifyGeminiCalls(int expected) {
        verify(MODELS, times(expected)).generateContent(anyString(), anyString(), any(GenerateContentConfig.class));
    }

    @Test
    void cachedAnswersDoNotUseTheGlobalBudget() throws Exception {
        translate("Bank").andExpect(status().isOk());
        translate("Bank").andExpect(status().isOk());
        translate("Bank").andExpect(status().isOk());

        verifyGeminiCalls(1);
        assertThat(callsToday()).isEqualTo(1);
    }

    @Test
    void retriesCountAgainstTheGlobalBudget() throws Exception {
        GenerateContentResponse response = mock(GenerateContentResponse.class);
        given(response.text()).willReturn(ANSWER);
        given(MODELS.generateContent(anyString(), anyString(), any(GenerateContentConfig.class)))
                .willThrow(new ServerException(503, "UNAVAILABLE", "overloaded"))
                .willReturn(response);

        translate("Bank").andExpect(status().isOk());

        verifyGeminiCalls(2);
        assertThat(callsToday()).isEqualTo(2);
    }

    @Test
    void aUsedUpBudgetAnswers503ButCachedTranslationsStillWork() throws Exception {
        translate("Bank").andExpect(status().isOk()); // cached from now on
        jdbc.update("UPDATE provider_daily_usage SET calls = 3 WHERE provider = ?", budgetName);

        translate("Geld")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TRANSLATION_BUDGET_EXHAUSTED"))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());
        translate("Bank").andExpect(status().isOk());

        verifyGeminiCalls(1);
        assertThat(callsToday()).isEqualTo(3);
    }

    @Test
    void concurrentCallsNeverExceedTheBudget() throws Exception {
        int threads = 20;
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    start.await();
                    try {
                        budget.acquire(budgetName);
                        allowed.incrementAndGet();
                    } catch (RateLimitExceededException e) {
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TRANSLATION_BUDGET_EXHAUSTED);
                        refused.incrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(allowed).hasValue(3);
        assertThat(refused).hasValue(threads - 3);
        assertThat(callsToday()).isEqualTo(3);
    }

    /** The per-user quota lives in Redis; without one running it fails open, so this needs a real Redis. */
    @Test
    @EnabledIf("redisIsReachable")
    void aUserGetsTheirDailyQuotaCachedAnswersIncludedThen429() throws Exception {
        for (int i = 0; i < 4; i++) {
            translate("Bank").andExpect(status().isOk());
        }

        translate("Bank")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TRANSLATION_DAILY_LIMIT_REACHED"))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
        // Another user is not affected.
        user = UUID.randomUUID();
        translate("Bank").andExpect(status().isOk());
    }

    static boolean redisIsReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(System.getenv().getOrDefault("REDIS_HOST", "localhost"),
                    Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"))), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
