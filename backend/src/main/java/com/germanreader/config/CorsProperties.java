package com.germanreader.config;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(
        @NotEmpty
        List<@Pattern(regexp = "https?://[A-Za-z0-9.-]+(:\\d+)?",
                message = "must be an origin such as http://localhost:5173 (no path or trailing slash)") String>
                allowedOrigins
) {
}
