package com.germanreader.ratelimit;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.quota")
public record QuotaProperties(
        @DefaultValue("true") boolean enabled,
        @Min(1) @DefaultValue("200") int translationsPerUserPerDay,
        @Min(1) @DefaultValue("500") int providerCallsPerDay
) {
}
