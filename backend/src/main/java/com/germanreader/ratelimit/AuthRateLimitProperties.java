package com.germanreader.ratelimit;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;


@Validated
@ConfigurationProperties(prefix = "app.rate-limit.auth")
public record AuthRateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @Min(1) @DefaultValue("10") int loginLimit,
        @NotNull @DefaultValue("5m") Duration loginWindow,
        @Min(1) @DefaultValue("5") int registerLimit,
        @NotNull @DefaultValue("1h") Duration registerWindow,
        @DefaultValue("") String clientIpHeader
) {
}
