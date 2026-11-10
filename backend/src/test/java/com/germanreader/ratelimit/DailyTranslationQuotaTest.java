package com.germanreader.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.germanreader.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

class DailyTranslationQuotaTest {

    private static final UUID ANNA = UUID.fromString("0f3e2b4c-1d5a-4b6c-8d7e-9f0a1b2c3d4e");
    private static final UUID BEN = UUID.fromString("1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d");
    private static final Instant EVENING = Instant.parse("2026-11-08T22:00:00Z");

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, String> expiries = new ConcurrentHashMap<>();

    private DailyTranslationQuota quota(Instant now, boolean enabled) {
        return new DailyTranslationQuota(redis, new QuotaProperties(enabled, 3, 100), Clock.fixed(now, ZoneOffset.UTC));
    }

    @BeforeEach
    void redisCountsLikeIncr() {
        given(redis.execute(eq(TranslationRateLimiter.INCREMENT), anyList(), any())).willAnswer(invocation -> {
            String key = invocation.<List<String>>getArgument(1).getFirst();
            expiries.putIfAbsent(key, invocation.getArgument(2));
            return counters.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
        });
    }

    @Test
    void refusesTheRequestAfterTheQuotaWith429UntilMidnightUtc() {
        DailyTranslationQuota quota = quota(EVENING, true);
        for (int i = 0; i < 3; i++) {
            quota.check(ANNA);
        }

        assertThatThrownBy(() -> quota.check(ANNA)).isInstanceOfSatisfying(RateLimitExceededException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TRANSLATION_DAILY_LIMIT_REACHED);
            assertThat(e.getErrorCode().status().value()).isEqualTo(429);
            assertThat(e.retryAfterSeconds()).isEqualTo(2 * 3600);
        });
        assertThatCode(() -> quota.check(BEN)).doesNotThrowAnyException();
    }

    @Test
    void theQuotaResetsAtMidnightUtc() {
        DailyTranslationQuota today = quota(EVENING, true);
        for (int i = 0; i < 3; i++) {
            today.check(ANNA);
        }

        DailyTranslationQuota tomorrow = quota(Instant.parse("2026-11-09T00:00:00Z"), true);

        assertThatCode(() -> tomorrow.check(ANNA)).doesNotThrowAnyException();
        assertThat(counters).containsOnlyKeys("quota:translations:" + ANNA + ":2026-11-08",
                "quota:translations:" + ANNA + ":2026-11-09");
    }

    @Test
    void eachDayKeyExpiresAnHourAfterTheDayEnds() {
        quota(EVENING, true).check(ANNA);

        assertThat(expiries).containsEntry("quota:translations:" + ANNA + ":2026-11-08", String.valueOf(3 * 3600_000L));
    }

    @Test
    void disabledMeansNoRedisCalls() {
        DailyTranslationQuota off = quota(EVENING, false);
        for (int i = 0; i < 10; i++) {
            off.check(ANNA);
        }
        verifyNoInteractions(redis);
    }

    @Test
    void anUnreachableRedisLetsRequestsThroughTheGlobalBudgetStillApplies() {
        given(redis.execute(eq(TranslationRateLimiter.INCREMENT), anyList(), any()))
                .willThrow(new RedisConnectionFailureException("Connection refused"));
        DailyTranslationQuota quota = quota(EVENING, true);

        assertThatCode(() -> {
            for (int i = 0; i < 10; i++) {
                quota.check(ANNA);
            }
        }).doesNotThrowAnyException();
    }
}
