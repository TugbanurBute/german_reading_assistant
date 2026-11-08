package com.germanreader.ratelimit;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Translation rate limit per user: at most {@code limit} requests per {@code window}.
 *
 * @param enabled false switches the limit off entirely (no Redis calls)
 */
@Validated
@ConfigurationProperties(prefix = "app.rate-limit.translations")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @Min(1) @DefaultValue("10") int limit,
        @NotNull @DefaultValue("60s") Duration window
) {
}
