package com.germanreader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.germanreader.dto.SourceLanguage;
import com.germanreader.dto.TargetLanguage;
import com.germanreader.dto.TranslationRequest;
import com.germanreader.dto.TranslationResponse;
import com.germanreader.dto.TranslationType;
import com.germanreader.entity.TranslationCacheEntry;
import com.germanreader.repository.TranslationCacheRepository;
import com.germanreader.service.TranslationCacheKey;
import com.germanreader.service.TranslationService;
import com.germanreader.service.translation.TranslationProvider;
import com.germanreader.security.TestAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "translation.provider=mock",
        "spring.flyway.schemas=" + TranslationCacheIT.SCHEMA,
        "spring.datasource.hikari.schema=" + TranslationCacheIT.SCHEMA,
        "app.rate-limit.translations.enabled=false" // these tests are about the cache, not the limit
})
@ActiveProfiles("local")
@AutoConfigureMockMvc
@Import(TestAuth.AsDefaultUser.class)
class TranslationCacheIT {

    static final String SCHEMA = "it_test";

    private static final String BENCH_SENTENCE = "Am Morgen sitzt meine Großmutter auf der Bank im Park.";
    private static final String BANK_SENTENCE = "Danach bringt sie ihr Geld zur Bank.";

    @Autowired
    private TranslationService service;

    @Autowired
    private TranslationCacheRepository repository;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private TranslationProvider provider;

    @BeforeEach
    void emptyCache() {
        repository.deleteAllInBatch();
    }

    private static TranslationRequest word(String word, String sentence, TargetLanguage target) {
        return new TranslationRequest(word, sentence, SourceLanguage.DE, target, TranslationType.WORD);
    }

    @Test
    void firstRequestIsStoredAndSecondIsServedFromTheDatabase() {
        TranslationRequest request = word("Bank", BENCH_SENTENCE, TargetLanguage.TR);

        TranslationResponse first = service.translate(request);
        TranslationResponse second = service.translate(request);

        assertThat(second).isEqualTo(first);
        verify(provider, times(1)).translate(any());

        TranslationCacheEntry row = repository.findAll().getFirst();
        assertThat(repository.count()).isEqualTo(1);
        assertThat(row.getProvider()).isEqualTo("mock");
        assertThat(row.getTranslationType()).isEqualTo(TranslationType.WORD);
        assertThat(row.getContext()).isEqualTo(BENCH_SENTENCE);
        assertThat(row.getResponse()).contains("\"kind\"");
        assertThat(row.getCreatedAt()).isNotNull();
    }

    @Test
    void languagesAndContextsAreCachedSeparately() {
        TranslationResponse benchTr = service.translate(word("Bank", BENCH_SENTENCE, TargetLanguage.TR));
        TranslationResponse benchEn = service.translate(word("Bank", BENCH_SENTENCE, TargetLanguage.EN));
        TranslationResponse moneyTr = service.translate(word("Bank", BANK_SENTENCE, TargetLanguage.TR));

        assertThat(repository.count()).isEqualTo(3);
        assertThat(benchTr.translation()).isNotEqualTo(benchEn.translation());
        assertThat(benchTr.translation()).isNotEqualTo(moneyTr.translation());
    }

    @Test
    void sentenceTranslationIsCached() {
        TranslationRequest request = new TranslationRequest(BANK_SENTENCE, null, SourceLanguage.DE,
                TargetLanguage.EN, TranslationType.SENTENCE);

        service.translate(request);
        service.translate(request);

        verify(provider, times(1)).translate(any());
        assertThat(repository.findAll().getFirst().getContext()).isNull();
    }

    @Test
    void rowFromAnotherProviderIsNeverServedInMockMode() {
        TranslationRequest request = word("Bank", BENCH_SENTENCE, TargetLanguage.TR);
        TranslationResponse mockAnswer = service.translate(request);
        repository.deleteAllInBatch();
        // Simulates a row written earlier while TRANSLATION_PROVIDER=gemini.
        TranslationResponse geminiAnswer = new TranslationResponse("Bank", "GEMINI ROW", null, null, null,
                "GEMINI ROW", SourceLanguage.DE, TargetLanguage.TR, TranslationType.WORD);
        repository.insertIfAbsent(TranslationCacheKey.of("gemini:some-model", "v1", request)
                .toEntry(jsonMapper.writeValueAsString(geminiAnswer)));

        TranslationResponse response = service.translate(request);

        assertThat(response.translation()).isEqualTo(mockAnswer.translation()).isNotEqualTo("GEMINI ROW");
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void insertIfAbsentIgnoresADuplicateKey() {
        TranslationCacheKey key = TranslationCacheKey.of("mock", "mock-v1",
                word("Bank", BENCH_SENTENCE, TargetLanguage.TR));

        int first = repository.insertIfAbsent(key.toEntry("{\"translation\":\"first\"}"));
        int second = repository.insertIfAbsent(key.toEntry("{\"translation\":\"second\"}"));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(repository.findByCacheKey(key.hash())).get()
                .extracting(TranslationCacheEntry::getResponse).asString().contains("first");
    }

    @Test
    void unknownMockTextReturns404AndIsNotCached() throws Exception {
        mockMvc.perform(post("/api/v1/translations").contentType(MediaType.APPLICATION_JSON).content("""
                        {"selectedText": "Hund", "context": "Der Hund schläft.", "sourceLanguage": "DE",
                         "targetLanguage": "TR", "translationType": "WORD"}
                        """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MOCK_TRANSLATION_NOT_FOUND"));

        assertThat(repository.count()).isZero();
    }

    @Test
    void endpointReturnsTheTranslationAndCachesIt() throws Exception {
        String body = """
                {"selectedText": "Bank", "context": "%s", "sourceLanguage": "DE",
                 "targetLanguage": "EN", "translationType": "WORD"}
                """.formatted(BANK_SENTENCE);

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/translations").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lemma").value("Bank"))
                    .andExpect(jsonPath("$.grammar.kind").value("NOUN"));
        }

        verify(provider, times(1)).translate(any());
        verify(provider, never()).translate(word("Bank", BENCH_SENTENCE, TargetLanguage.EN));
        assertThat(repository.count()).isEqualTo(1);
    }
}
