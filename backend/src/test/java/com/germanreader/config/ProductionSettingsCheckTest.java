package com.germanreader.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.germanreader.ratelimit.AuthRateLimitProperties;
import com.germanreader.ratelimit.QuotaProperties;
import com.germanreader.security.AuthProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionSettingsCheckTest {

    private static final AuthProperties SECURE = new AuthProperties("x".repeat(40), Duration.ofHours(8), true, false);
    private static final AuthRateLimitProperties LIMITS =
            new AuthRateLimitProperties(true, 10, Duration.ofMinutes(5), 5, Duration.ofHours(1), "X-Real-IP");

    private static ProductionSettingsCheck check(AuthProperties auth, List<String> origins, boolean quotas,
                                                 AuthRateLimitProperties limits) {
        MockEnvironment environment = new MockEnvironment().withProperty("translation.provider", "gemini");
        return new ProductionSettingsCheck(environment, auth, new CorsProperties(origins),
                new QuotaProperties(quotas, 200, 500), limits);
    }

    @Test
    void aSafeConfigurationHasNoWarnings() {
        assertThat(check(SECURE, List.of("https://lesehilfe.duckdns.org"), true, LIMITS).warnings()).isEmpty();
    }

    @Test
    void localhostOverHttpIsFine() {
        assertThat(check(SECURE, List.of("http://localhost:8081"), true, LIMITS).warnings()).isEmpty();
    }

    @Test
    void unsafeSettingsAreNamedWithoutSecrets() {
        AuthProperties insecure = new AuthProperties("geheimer-schluessel-".repeat(3), Duration.ofHours(8), false, false);
        AuthRateLimitProperties noHeader =
                new AuthRateLimitProperties(true, 10, Duration.ofMinutes(5), 5, Duration.ofHours(1), "");

        List<String> warnings = check(insecure, List.of("http://lesehilfe.duckdns.org"), false, noHeader).warnings();

        assertThat(warnings).hasSize(4)
                .anySatisfy(w -> assertThat(w).contains("AUTH_COOKIE_SECURE"))
                .anySatisfy(w -> assertThat(w).contains("CORS_ALLOWED_ORIGINS"))
                .anySatisfy(w -> assertThat(w).contains("QUOTAS_ENABLED"))
                .anySatisfy(w -> assertThat(w).contains("CLIENT_IP_HEADER"))
                .noneSatisfy(w -> assertThat(w).contains("geheimer"));
    }
}
