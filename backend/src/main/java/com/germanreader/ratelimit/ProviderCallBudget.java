package com.germanreader.ratelimit;

import com.germanreader.exception.ErrorCode;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@EnableConfigurationProperties(QuotaProperties.class)
public class ProviderCallBudget {

    private final JdbcTemplate jdbc;
    private final QuotaProperties properties;
    private final Clock clock;
    private final AtomicReference<LocalDate> warnedOn = new AtomicReference<>();

    @Autowired
    public ProviderCallBudget(JdbcTemplate jdbc, QuotaProperties properties) {
        this(jdbc, properties, Clock.systemUTC());
    }

    ProviderCallBudget(JdbcTemplate jdbc, QuotaProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    public void acquire(String provider) {
        if (!properties.enabled()) {
            return;
        }
        LocalDate today = DailyQuotas.today(clock);
        int taken = jdbc.update("""
                INSERT INTO provider_daily_usage (provider, day, calls) VALUES (?, ?, 1)
                ON CONFLICT (provider, day) DO UPDATE SET calls = provider_daily_usage.calls + 1
                WHERE provider_daily_usage.calls < ?
                """, provider, Date.valueOf(today), properties.providerCallsPerDay());
        if (taken == 0) {
            if (!today.equals(warnedOn.getAndSet(today))) {
                log.warn("The daily budget of {} {} calls is used up; translations that are not cached fail until 00:00 UTC",
                        properties.providerCallsPerDay(), provider);
            }
            throw new RateLimitExceededException(ErrorCode.TRANSLATION_BUDGET_EXHAUSTED, DailyQuotas.untilReset(clock));
        }
    }
}
