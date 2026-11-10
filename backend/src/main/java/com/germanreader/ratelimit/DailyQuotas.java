package com.germanreader.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

final class DailyQuotas {

    private DailyQuotas() {
    }

    static LocalDate today(Clock clock) {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    static Duration untilReset(Clock clock) {
        Instant now = clock.instant();
        Instant midnight = today(clock).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return Duration.between(now, midnight);
    }
}
