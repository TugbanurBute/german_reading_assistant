package com.germanreader.config;

import com.germanreader.ratelimit.AuthRateLimitProperties;
import com.germanreader.ratelimit.QuotaProperties;
import com.germanreader.security.AuthProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ProductionSettingsCheck {

    private final Environment environment;
    private final AuthProperties auth;
    private final CorsProperties cors;
    private final QuotaProperties quotas;
    private final AuthRateLimitProperties authLimits;

    public ProductionSettingsCheck(Environment environment, AuthProperties auth, CorsProperties cors,
                                   QuotaProperties quotas, AuthRateLimitProperties authLimits) {
        this.environment = environment;
        this.auth = auth;
        this.cors = cors;
        this.quotas = quotas;
        this.authLimits = authLimits;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warnAboutUnsafeSettings() {
        if (Arrays.asList(environment.getActiveProfiles()).contains("local")) {
            return;
        }
        warnings().forEach(warning -> log.warn("Production settings: {}", warning));
    }

    List<String> warnings() {
        List<String> warnings = new ArrayList<>();
        if (!auth.cookieSecure()) {
            warnings.add("AUTH_COOKIE_SECURE is false: login cookies would also be sent over plain HTTP.");
        }
        cors.allowedOrigins().stream()
                .filter(origin -> origin.startsWith("http://") && !isLocalhost(origin))
                .forEach(origin -> warnings.add("CORS_ALLOWED_ORIGINS contains a plain HTTP address (" + origin
                        + "); the public address should be https://."));
        boolean gemini = "gemini".equalsIgnoreCase(environment.getProperty("translation.provider"));
        if (gemini && !quotas.enabled()) {
            warnings.add("QUOTAS_ENABLED is false with the Gemini provider: nothing limits Gemini usage per day.");
        }
        if (!authLimits.enabled()) {
            warnings.add("AUTH_RATE_LIMIT_ENABLED is false: login and registration attempts are not limited.");
        } else if (authLimits.clientIpHeader().isBlank()) {
            warnings.add("CLIENT_IP_HEADER is empty: behind a proxy all clients share one login limit.");
        }
        return warnings;
    }

    private static boolean isLocalhost(String origin) {
        return origin.startsWith("http://localhost") || origin.startsWith("http://127.0.0.1");
    }
}
