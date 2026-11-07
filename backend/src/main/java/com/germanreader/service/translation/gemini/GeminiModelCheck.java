package com.germanreader.service.translation.gemini;

import com.google.genai.Models;
import com.google.genai.errors.ApiException;
import com.google.genai.types.GetModelConfig;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class GeminiModelCheck {

    public enum Result { AVAILABLE, MODEL_NOT_FOUND, REJECTED, NOT_VERIFIED }

    private GeminiModelCheck() {
    }

    public static Result verify(Models models, String model) {
        try {
            models.get(model, GetModelConfig.builder().build());
            log.info("Gemini model {} is available", model);
            return Result.AVAILABLE;
        } catch (ApiException e) {
            if (e.code() == 404) {
                log.error("Gemini model {} does not exist or has been retired (HTTP 404). Translations will fail "
                        + "until GEMINI_MODEL names a current model.", model);
                return Result.MODEL_NOT_FOUND;
            }
            if (e.code() >= 400 && e.code() < 500 && e.code() != 429) {
                log.error("Gemini rejected the startup model check (HTTP {} {}). Check GEMINI_API_KEY.", e.code(), e.status());
                return Result.REJECTED;
            }
            log.warn("Could not verify Gemini model {} (HTTP {} {}); continuing.", model, e.code(), e.status());
            return Result.NOT_VERIFIED;
        } catch (RuntimeException e) {
            log.warn("Could not verify Gemini model {} ({}); continuing.", model, e.getClass().getSimpleName());
            return Result.NOT_VERIFIED;
        }
    }
}
