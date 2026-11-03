package com.germanreader.service;

import com.germanreader.dto.TranslationRequest;
import com.germanreader.dto.TranslationResponse;
import com.germanreader.repository.TranslationCacheRepository;
import com.germanreader.service.translation.TranslationProvider;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cache-first translation: database lookup, then at most one provider call per identical request, then save.
 *
 * <p>Deliberately not {@code @Transactional}: each repository call runs in its own short transaction,
 * so no database connection is held while waiting for the provider.
 */
@Slf4j
@Service
public class TranslationService {

    private final TranslationProvider provider;
    private final TranslationCacheRepository cacheRepository;
    private final JsonMapper jsonMapper;

    /** Provider calls in progress, by cache key. Identical concurrent requests wait for the same call. */
    private final ConcurrentMap<String, CompletableFuture<TranslationResponse>> inFlight = new ConcurrentHashMap<>();

    public TranslationService(TranslationProvider provider, TranslationCacheRepository cacheRepository,
                              JsonMapper jsonMapper) {
        this.provider = provider;
        this.cacheRepository = cacheRepository;
        this.jsonMapper = jsonMapper;
    }

    public TranslationResponse translate(TranslationRequest request) {
        TranslationCacheKey key = TranslationCacheKey.of(provider.cacheNamespace(), provider.promptVersion(), request);
        TranslationResponse response = findCached(key).orElseGet(() -> translateOnce(key, request));
        // A cached answer may come from a request that differed only in whitespace.
        return response.withOriginalText(request.selectedText());
    }

    /** Single-flight: the first caller for a key calls the provider, later callers wait for its result. */
    private TranslationResponse translateOnce(TranslationCacheKey key, TranslationRequest request) {
        CompletableFuture<TranslationResponse> ownCall = new CompletableFuture<>();
        CompletableFuture<TranslationResponse> runningCall = inFlight.putIfAbsent(key.hash(), ownCall);
        if (runningCall != null) {
            log.debug("Waiting for an identical translation already in progress");
            return await(runningCall);
        }

        try {
            // A previous call may have cached the answer between our lookup and putIfAbsent.
            TranslationResponse response = findCached(key).orElseGet(() -> translateAndCache(key, request));
            ownCall.complete(response);
            return response;
        } catch (Throwable e) {
            // Waiting callers get the same error; nothing is cached.
            ownCall.completeExceptionally(e);
            throw e;
        } finally {
            // Always removed, so the next request after a failure calls the provider again.
            inFlight.remove(key.hash(), ownCall);
        }
    }

    private TranslationResponse translateAndCache(TranslationCacheKey key, TranslationRequest request) {
        log.debug("Translation cache miss, calling provider {}", key.provider());
        TranslationResponse response = provider.translate(request);
        save(key, response);
        return response;
    }

    private Optional<TranslationResponse> findCached(TranslationCacheKey key) {
        return cacheRepository.findByCacheKey(key.hash())
                .map(entry -> jsonMapper.readValue(entry.getResponse(), TranslationResponse.class));
    }

    private void save(TranslationCacheKey key, TranslationResponse response) {
        try {
            int inserted = cacheRepository.insertIfAbsent(key.toEntry(jsonMapper.writeValueAsString(response)));
            if (inserted == 0) {
                log.debug("Translation was already cached by another request");
            }
        } catch (DataAccessException e) {
            // The translation succeeded; a failed cache write must not cost the user the answer.
            // Only the exception type is logged: database messages can contain the request text.
            log.warn("Could not cache translation: {}", e.getClass().getSimpleName());
        }
    }

    private static TranslationResponse await(CompletableFuture<TranslationResponse> call) {
        try {
            return call.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }
}
