package com.germanreader.service.translation.gemini;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "translation.gemini")
public record GeminiProperties(
        @NotBlank String apiKey,
        @NotBlank String model,
        @NotNull @DefaultValue("15s") Duration timeout,
        @Min(1) @Max(3) @DefaultValue("2") int maxAttempts
) {

    @Override
    public String toString() {
        return "GeminiProperties[apiKey=***, model=%s, timeout=%s, maxAttempts=%d]"
                .formatted(model, timeout, maxAttempts);
    }
}
