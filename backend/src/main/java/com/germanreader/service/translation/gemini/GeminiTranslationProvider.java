package com.germanreader.service.translation.gemini;

import com.germanreader.dto.TranslationRequest;
import com.germanreader.dto.TranslationResponse;
import com.germanreader.exception.ErrorCode;
import com.germanreader.exception.TranslationException;
import com.germanreader.service.translation.TranslationProvider;
import com.google.genai.Models;
import com.google.genai.errors.ApiException;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
public class GeminiTranslationProvider implements TranslationProvider {

    static final Duration BASE_BACKOFF = Duration.ofMillis(500);
    static final Duration MAX_RATE_LIMIT_WAIT = Duration.ofSeconds(3);

    private static final Pattern RETRY_HINT = Pattern.compile("retry in (\\d+(?:\\.\\d+)?)\\s*s", Pattern.CASE_INSENSITIVE);

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    public static final String BUDGET_NAME = "gemini";

    @FunctionalInterface
    public interface CallBudget {
        void acquire();
    }

    private final Models models;
    private final String model;
    private final JsonMapper jsonMapper;
    private final GeminiResponseParser parser;
    private final int maxAttempts;
    private final CallBudget budget;
    private final Sleeper sleeper;
    private final RandomGenerator random;

    public GeminiTranslationProvider(Models models, String model, JsonMapper jsonMapper, int maxAttempts, CallBudget budget) {
        this(models, model, jsonMapper, maxAttempts, budget, duration -> Thread.sleep(duration), ThreadLocalRandom.current());
    }

    GeminiTranslationProvider(Models models, String model, JsonMapper jsonMapper, int maxAttempts, CallBudget budget,
                              Sleeper sleeper, RandomGenerator random) {
        this.models = models;
        this.model = model;
        this.jsonMapper = jsonMapper;
        this.parser = new GeminiResponseParser(jsonMapper);
        this.maxAttempts = maxAttempts;
        this.budget = budget;
        this.sleeper = sleeper;
        this.random = random;
    }

    @Override
    public String cacheNamespace() {
        return "gemini:" + model;
    }

    @Override
    public String promptVersion() {
        return GeminiPrompts.PROMPT_VERSION;
    }

    @Override
    public TranslationResponse translate(TranslationRequest request) {
        GenerateContentConfig config = GenerateContentConfig.builder()
                .systemInstruction(Content.fromParts(Part.fromText(GeminiPrompts.instructionFor(request.translationType()))))
                .responseMimeType("application/json")
                .responseSchema(GeminiPrompts.schemaFor(request.translationType()))
                .build();
        String userMessage = GeminiPrompts.userMessage(request, jsonMapper);

        GenerateContentResponse response = callWithRetry(userMessage, config);
        String text = response == null ? null : response.text();
        if (text == null || text.isBlank()) {
            throw new TranslationException(ErrorCode.PROVIDER_INVALID_RESPONSE,
                    "Gemini returned no text (finish reason: " + finishReason(response) + ")");
        }
        return parser.parse(text, request);
    }

    private GenerateContentResponse callWithRetry(String userMessage, GenerateContentConfig config) {
        for (int attempt = 1; ; attempt++) {
            budget.acquire();
            long start = System.nanoTime();
            try {
                GenerateContentResponse response = models.generateContent(model, userMessage, config);
                log.debug("Gemini call to {} took {} ms (attempt {})", model, (System.nanoTime() - start) / 1_000_000, attempt);
                return response;
            } catch (RuntimeException e) {
                Failure failure = classify(e);
                if (!failure.retryable() || attempt >= maxAttempts) {
                    String attempts = attempt > 1 ? " (after " + attempt + " attempts)" : "";
                    throw new TranslationException(failure.code(), failure.description() + attempts, e);
                }
                Duration wait = waitBefore(attempt + 1, failure.retryAfter());
                log.info("{}; retrying in {} ms", failure.description(), wait.toMillis());
                pause(wait, e);
            }
        }
    }

    record Failure(ErrorCode code, String description, boolean retryable, Duration retryAfter) {
    }

    static Failure classify(RuntimeException e) {
        if (e instanceof ApiException api) {
            String description = "Gemini answered HTTP " + api.code() + " " + api.status();
            return switch (api.code()) {
                case 429 -> {
                    Duration hint = retryHint(api.message());
                    boolean worthWaiting = hint == null || hint.compareTo(MAX_RATE_LIMIT_WAIT) <= 0;
                    yield new Failure(ErrorCode.PROVIDER_RATE_LIMITED, description, worthWaiting, hint);
                }
                case 404 -> new Failure(ErrorCode.PROVIDER_MODEL_UNAVAILABLE, description, false, null);
                case 500, 502, 503 -> new Failure(ErrorCode.PROVIDER_UNAVAILABLE, description, true, null);
                case 504 -> new Failure(ErrorCode.PROVIDER_TIMEOUT, description, true, null);
                default -> api.code() >= 400 && api.code() < 500
                        ? new Failure(ErrorCode.PROVIDER_REJECTED, description, false, null)
                        : new Failure(ErrorCode.PROVIDER_UNAVAILABLE, description, false, null);
            };
        }
        String description = "Gemini call failed: " + e.getClass().getSimpleName();
        if (hasCause(e, InterruptedIOException.class)) {
            return new Failure(ErrorCode.PROVIDER_TIMEOUT, description, false, null);
        }
        boolean network = hasCause(e, IOException.class);
        return new Failure(ErrorCode.PROVIDER_UNAVAILABLE, description, network, null);
    }

    Duration waitBefore(int nextAttempt, Duration retryAfter) {
        if (retryAfter != null) {
            return retryAfter.plusMillis(random.nextLong(100));
        }
        long backoff = BASE_BACKOFF.toMillis() << (nextAttempt - 2);
        return Duration.ofMillis(backoff / 2 + random.nextLong(backoff / 2 + 1));
    }

    static Duration retryHint(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = RETRY_HINT.matcher(message);
        return matcher.find() ? Duration.ofMillis(Math.round(Double.parseDouble(matcher.group(1)) * 1000)) : null;
    }

    private void pause(Duration wait, RuntimeException cause) {
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new TranslationException(ErrorCode.PROVIDER_UNAVAILABLE, "Interrupted while waiting to retry Gemini", cause);
        }
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    private static String finishReason(GenerateContentResponse response) {
        try {
            return response == null || response.finishReason() == null ? "unknown" : response.finishReason().toString();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }
}
