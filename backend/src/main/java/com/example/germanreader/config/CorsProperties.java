package com.example.germanreader.config;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Origins allowed to call the API from a browser, bound from {@code app.cors.allowed-origins}.
 * A comma-separated environment variable binds to the list automatically.
 */
@Validated
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(@NotEmpty List<String> allowedOrigins) {
}
