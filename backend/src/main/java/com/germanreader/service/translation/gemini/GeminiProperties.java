package com.germanreader.service.translation.gemini;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "translation.gemini")
public record GeminiProperties(
        // No @Pattern here: a failed pattern check would print the value, i.e. the key, in the startup error.
        @NotBlank String apiKey,
        @NotBlank
        @Pattern(regexp = "(models/)?[A-Za-z0-9][A-Za-z0-9._-]*",
                message = "must be a Gemini model name such as gemini-3.5-flash-lite")
        String model,
        @NotNull @DefaultValue("15s") Duration timeout,
        @Min(1) @Max(2) @DefaultValue("2") int maxAttempts,
        @DefaultValue("false") boolean verifyModel
) {

    public GeminiProperties {
        // Values pasted with spaces or quotes around them are a common reason for "API key not valid".
        apiKey = unquote(apiKey);
        model = unquote(model);
    }

    private static String unquote(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.length() >= 2 && (stripped.startsWith("\"") && stripped.endsWith("\"")
                || stripped.startsWith("'") && stripped.endsWith("'"))) {
            stripped = stripped.substring(1, stripped.length() - 1).strip();
        }
        return stripped;
    }

    @Override
    public String toString() {
        return "GeminiProperties[apiKey=***, model=%s, timeout=%s, maxAttempts=%d, verifyModel=%s]"
                .formatted(model, timeout, maxAttempts, verifyModel);
    }
}
