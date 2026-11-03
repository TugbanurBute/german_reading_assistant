package com.germanreader.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "translation")
public record TranslationProperties(@NotNull ProviderType provider) {

    public enum ProviderType {
        MOCK,
        GEMINI
    }
}
