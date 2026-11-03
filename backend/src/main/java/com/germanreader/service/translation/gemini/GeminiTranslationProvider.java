package com.germanreader.service.translation.gemini;

import com.germanreader.dto.TranslationRequest;
import com.germanreader.dto.TranslationResponse;
import com.germanreader.exception.ErrorCode;
import com.germanreader.exception.TranslationException;
import com.germanreader.service.translation.TranslationProvider;
import com.google.genai.Models;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.ClientException;
import com.google.genai.errors.ServerException;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import java.io.InterruptedIOException;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
public class GeminiTranslationProvider implements TranslationProvider {

    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_GATEWAY_TIMEOUT = 504;

    private final Models models;
    private final String model;
    private final JsonMapper jsonMapper;
    private final GeminiResponseParser parser;

    public GeminiTranslationProvider(Models models, String model, JsonMapper jsonMapper) {
        this.models = models;
        this.model = model;
        this.jsonMapper = jsonMapper;
        this.parser = new GeminiResponseParser(jsonMapper);
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

        GenerateContentResponse response = call(userMessage, config);
        String text = response == null ? null : response.text();
        if (text == null || text.isBlank()) {
            throw new TranslationException(ErrorCode.PROVIDER_INVALID_RESPONSE,
                    "Gemini returned no text (finish reason: " + finishReason(response) + ")");
        }
        return parser.parse(text, request);
    }

    private GenerateContentResponse call(String userMessage, GenerateContentConfig config) {
        long start = System.nanoTime();
        try {
            GenerateContentResponse response = models.generateContent(model, userMessage, config);
            log.debug("Gemini call to {} took {} ms", model, (System.nanoTime() - start) / 1_000_000);
            return response;
        } catch (ClientException e) {
            // 4xx: our request or account is the problem (bad key, unknown model, quota exhausted).
            ErrorCode code = e.code() == HTTP_TOO_MANY_REQUESTS ? ErrorCode.PROVIDER_RATE_LIMITED : ErrorCode.PROVIDER_UNAVAILABLE;
            throw failure(code, "Gemini rejected the request: HTTP " + e.code() + " " + e.status(), e);
        } catch (ServerException e) {
            ErrorCode code = e.code() == HTTP_GATEWAY_TIMEOUT ? ErrorCode.PROVIDER_TIMEOUT : ErrorCode.PROVIDER_UNAVAILABLE;
            throw failure(code, "Gemini server error: HTTP " + e.code() + " " + e.status(), e);
        } catch (ApiException e) {
            throw failure(ErrorCode.PROVIDER_UNAVAILABLE, "Gemini API error: HTTP " + e.code(), e);
        } catch (RuntimeException e) {
            // Network problems arrive as GenAiIOException wrapping an IOException; timeouts are a subtype.
            ErrorCode code = isTimeout(e) ? ErrorCode.PROVIDER_TIMEOUT : ErrorCode.PROVIDER_UNAVAILABLE;
            throw failure(code, "Gemini call failed: " + e.getClass().getSimpleName(), e);
        }
    }

    private static TranslationException failure(ErrorCode code, String message, Throwable cause) {
        return new TranslationException(code, message, cause);
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof InterruptedIOException) { // includes SocketTimeoutException
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
