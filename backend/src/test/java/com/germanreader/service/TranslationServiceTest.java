package com.germanreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.germanreader.dto.PartOfSpeech;
import com.germanreader.dto.SourceLanguage;
import com.germanreader.dto.TargetLanguage;
import com.germanreader.dto.TranslationRequest;
import com.germanreader.dto.TranslationResponse;
import com.germanreader.dto.TranslationType;
import com.germanreader.dto.grammar.Article;
import com.germanreader.dto.grammar.GrammaticalCase;
import com.germanreader.dto.grammar.GrammaticalNumber;
import com.germanreader.dto.grammar.NounGrammar;
import com.germanreader.entity.TranslationCacheEntry;
import com.germanreader.exception.ErrorCode;
import com.germanreader.exception.TranslationException;
import com.germanreader.repository.TranslationCacheRepository;
import com.germanreader.service.translation.TranslationProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

class TranslationServiceTest {

    private static final String SENTENCE = "Am Morgen sitzt meine Großmutter auf der Bank im Park.";
    private static final TranslationRequest REQUEST =
            new TranslationRequest("Bank", SENTENCE, SourceLanguage.DE, TargetLanguage.TR, TranslationType.WORD);
    private static final TranslationResponse RESPONSE = new TranslationResponse("Bank", "bank (oturak)", "Bank",
            PartOfSpeech.NOUN, new NounGrammar(Article.DIE, "Bänke", GrammaticalCase.DATIVE, GrammaticalNumber.SINGULAR),
            "Sabahları büyükannem parktaki bankta oturur.", SourceLanguage.DE, TargetLanguage.TR, TranslationType.WORD);
    private static final int THREADS = 8;

    private final TranslationProvider provider = mock(TranslationProvider.class);
    private final TranslationCacheRepository repository = mock(TranslationCacheRepository.class);
    private final Map<String, TranslationCacheEntry> rows = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    private TranslationService service;

    @BeforeEach
    void setUp() {
        given(provider.cacheNamespace()).willReturn("mock");
        given(provider.promptVersion()).willReturn("mock-v1");
        given(repository.findByCacheKey(anyString()))
                .willAnswer(call -> Optional.ofNullable(rows.get(call.<String>getArgument(0))));
        given(repository.insertIfAbsent(any())).willAnswer(call -> {
            TranslationCacheEntry entry = call.getArgument(0);
            return rows.putIfAbsent(entry.getCacheKey(), entry) == null ? 1 : 0;
        });
        service = new TranslationService(provider, repository, JsonMapper.builder().build());
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void cacheMissCallsProviderAndStoresTheResult() {
        given(provider.translate(REQUEST)).willReturn(RESPONSE);

        assertThat(service.translate(REQUEST)).isEqualTo(RESPONSE);

        verify(provider).translate(REQUEST);
        assertThat(rows).hasSize(1);
        TranslationCacheEntry row = rows.values().iterator().next();
        assertThat(row.getProvider()).isEqualTo("mock");
        assertThat(row.getPromptVersion()).isEqualTo("mock-v1");
        assertThat(row.getSelectedText()).isEqualTo("Bank");
        assertThat(row.getContext()).isEqualTo(SENTENCE);
        assertThat(row.getResponse()).contains("\"kind\":\"NOUN\"");
    }

    @Test
    void cacheHitReturnsStoredResultWithoutCallingProvider() {
        given(provider.translate(REQUEST)).willReturn(RESPONSE);
        service.translate(REQUEST);

        TranslationResponse second = service.translate(REQUEST);

        assertThat(second).isEqualTo(RESPONSE); // including the polymorphic grammar after the JSON round trip
        verify(provider, times(1)).translate(any());
    }

    @Test
    void cacheHitReturnsTheCallersOwnOriginalText() {
        given(provider.translate(REQUEST)).willReturn(RESPONSE);
        service.translate(REQUEST);

        TranslationRequest spaced = new TranslationRequest(" Bank ", SENTENCE, SourceLanguage.DE, TargetLanguage.TR,
                TranslationType.WORD);
        TranslationResponse response = service.translate(spaced);

        assertThat(response.originalText()).isEqualTo(" Bank ");
        assertThat(response.translation()).isEqualTo(RESPONSE.translation());
        verify(provider, times(1)).translate(any());
    }

    @Test
    void differentTargetLanguageIsACacheMiss() {
        TranslationRequest english = new TranslationRequest("Bank", SENTENCE, SourceLanguage.DE, TargetLanguage.EN,
                TranslationType.WORD);
        given(provider.translate(any())).willReturn(RESPONSE);

        service.translate(REQUEST);
        service.translate(english);

        verify(provider, times(2)).translate(any());
        assertThat(rows).hasSize(2);
    }

    @Test
    void cachedResultOfAnotherProviderIsNotUsed() {
        given(provider.translate(REQUEST)).willReturn(RESPONSE);
        service.translate(REQUEST);

        TranslationProvider gemini = mock(TranslationProvider.class);
        given(gemini.cacheNamespace()).willReturn("gemini:some-model");
        given(gemini.promptVersion()).willReturn("v1");
        given(gemini.translate(REQUEST)).willReturn(RESPONSE);
        new TranslationService(gemini, repository, JsonMapper.builder().build()).translate(REQUEST);

        verify(gemini).translate(REQUEST);
        assertThat(rows.values()).extracting(TranslationCacheEntry::getProvider)
                .containsExactlyInAnyOrder("mock", "gemini:some-model");
    }

    @Test
    void failuresAreNotCachedAndTheNextRequestRetries() {
        TranslationException timeout = new TranslationException(ErrorCode.PROVIDER_TIMEOUT, "test timeout");
        given(provider.translate(REQUEST)).willThrow(timeout).willReturn(RESPONSE);

        assertThatThrownBy(() -> service.translate(REQUEST)).isSameAs(timeout);
        verify(repository, never()).insertIfAbsent(any());

        assertThat(service.translate(REQUEST)).isEqualTo(RESPONSE);
        verify(provider, times(2)).translate(REQUEST);
        assertThat(rows).hasSize(1);
    }

    @Test
    void cacheWriteFailureStillReturnsTheTranslation() {
        given(provider.translate(REQUEST)).willReturn(RESPONSE);
        willThrow(new DataAccessResourceFailureException("database down")).given(repository).insertIfAbsent(any());

        assertThat(service.translate(REQUEST)).isEqualTo(RESPONSE);
    }

    @Test
    void concurrentIdenticalRequestsShareOneProviderCall() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        given(provider.translate(REQUEST)).willAnswer(call -> {
            release.await(5, TimeUnit.SECONDS);
            return RESPONSE;
        });

        List<Future<TranslationResponse>> results = startConcurrentRequests();
        waitUntilAllRequestsAreBehindTheFirstOne();
        release.countDown();

        for (Future<TranslationResponse> result : results) {
            assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(RESPONSE);
        }
        verify(provider, times(1)).translate(any());
        verify(repository, times(1)).insertIfAbsent(any());
    }

    @Test
    void concurrentRequestsShareAFailureAndTheNextRequestRecovers() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        TranslationException unavailable = new TranslationException(ErrorCode.PROVIDER_UNAVAILABLE, "test outage");
        given(provider.translate(REQUEST))
                .willAnswer(call -> {
                    release.await(5, TimeUnit.SECONDS);
                    throw unavailable;
                })
                .willReturn(RESPONSE);

        List<Future<TranslationResponse>> results = startConcurrentRequests();
        waitUntilAllRequestsAreBehindTheFirstOne();
        release.countDown();

        for (Future<TranslationResponse> result : results) {
            assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause().isSameAs(unavailable);
        }
        verify(provider, times(1)).translate(any());
        assertThat(rows).isEmpty();

        // The failed call left the in-flight map, so the next request tries again.
        assertThat(service.translate(REQUEST)).isEqualTo(RESPONSE);
        verify(provider, times(2)).translate(any());
    }

    private List<Future<TranslationResponse>> startConcurrentRequests() {
        List<Future<TranslationResponse>> results = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            results.add(pool.submit(() -> service.translate(REQUEST)));
        }
        return results;
    }

    private void waitUntilAllRequestsAreBehindTheFirstOne() {
        verify(provider, timeout(5000)).translate(any());
        verify(repository, timeout(5000).times(THREADS + 1)).findByCacheKey(anyString());
    }
}
