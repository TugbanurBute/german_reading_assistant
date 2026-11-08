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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The limiter's logic with an in-memory stand-in for the Redis script (an atomic counter per key, like INCR)
 * and a clock the tests move. RedisRateLimiterIT runs the real script against a real Redis when one is up.
 */
class TranslationRateLimiterTest {

    private static final UUID ANNA = UUID.fromString("0f3e2b4c-1d5a-4b6c-8d7e-9f0a1b2c3d4e");
    private static final UUID BEN = UUID.fromString("1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d");
    /** 12:00:15 UTC on a window boundary + 15 s (60 s windows start at whole minutes). */
    private static final Instant START = Instant.parse("2026-11-08T12:00:15Z");

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, String> expiries = new ConcurrentHashMap<>();
    private final MovableClock clock = new MovableClock(START);
    private final TranslationRateLimiter limiter = limiter(true);

    private TranslationRateLimiter limiter(boolean enabled) {
        return new TranslationRateLimiter(redis, new RateLimitProperties(enabled, 10, Duration.ofSeconds(60)), clock);
    }

    @BeforeEach
    void redisCountsLikeIncr() {
        given(redis.execute(eq(TranslationRateLimiter.INCREMENT), anyList(), any())).willAnswer(invocation -> {
            String key = invocation.<List<String>>getArgument(1).getFirst();
            expiries.putIfAbsent(key, invocation.getArgument(2));
            return counters.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
        });
    }

    private void use(UUID user, int times) {
        for (int i = 0; i < times; i++) {
            limiter.check(user);
        }
    }

    @Test
    void allowsTheLimitAndRefusesTheNextWithTheTimeLeftInTheWindow() {
        use(ANNA, 10);

        assertThatThrownBy(() -> limiter.check(ANNA))
                .isInstanceOfSatisfying(RateLimitExceededException.class,
                        e -> assertThat(e.retryAfterSeconds()).isEqualTo(45)); // 12:00:15 -> 12:01:00
    }

    @Test
    void countsEachUserSeparately() {
        use(ANNA, 10);

        assertThatCode(() -> use(BEN, 10)).doesNotThrowAnyException();
    }

    @Test
    void theNextWindowStartsAtZero() {
        use(ANNA, 10);
        clock.advance(Duration.ofSeconds(44)); // 12:00:59, same window
        assertThatThrownBy(() -> limiter.check(ANNA)).isInstanceOf(RateLimitExceededException.class);

        clock.advance(Duration.ofSeconds(1)); // 12:01:00, next window

        assertThatCode(() -> use(ANNA, 10)).doesNotThrowAnyException();
        assertThat(counters).hasSize(2);
    }

    @Test
    void eachWindowHasItsOwnKeyThatExpiresAfterTheWindow() {
        limiter.check(ANNA);

        long window = START.toEpochMilli() / 60_000;
        String key = "rate:translations:" + ANNA + ":" + window;
        assertThat(counters).containsOnlyKeys(key);
        assertThat(expiries).containsEntry(key, "60000");
    }

    @Test
    void concurrentRequestsNeverGetMoreThanTheLimit() throws Exception {
        int threads = 50;
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    start.await();
                    try {
                        limiter.check(ANNA);
                        allowed.incrementAndGet();
                    } catch (RateLimitExceededException e) {
                        refused.incrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(allowed).hasValue(10);
        assertThat(refused).hasValue(40);
    }

    @Test
    void anUnreachableRedisLetsTranslationsThrough() {
        given(redis.execute(eq(TranslationRateLimiter.INCREMENT), anyList(), any()))
                .willThrow(new RedisConnectionFailureException("Connection refused"));

        assertThatCode(() -> use(ANNA, 30)).doesNotThrowAnyException();
    }

    @Test
    void countingResumesWhenRedisIsBack() {
        given(redis.execute(eq(TranslationRateLimiter.INCREMENT), anyList(), any()))
                .willThrow(new RedisConnectionFailureException("Connection refused"))
                .willAnswer(invocation -> counters.computeIfAbsent(
                        invocation.<List<String>>getArgument(1).getFirst(), k -> new AtomicLong()).incrementAndGet());

        limiter.check(ANNA); // Redis down: allowed, not counted
        use(ANNA, 10);

        assertThatThrownBy(() -> limiter.check(ANNA)).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void aDisabledLimitNeverTalksToRedis() {
        assertThatCode(() -> {
            for (int i = 0; i < 100; i++) {
                limiter(false).check(ANNA);
            }
        }).doesNotThrowAnyException();

        verifyNoInteractions(redis);
    }

    @Test
    void retryAfterIsRoundedUpToWholeSecondsAndAtLeastOne() {
        assertThat(new RateLimitExceededException(Duration.ofMillis(1)).retryAfterSeconds()).isEqualTo(1);
        assertThat(new RateLimitExceededException(Duration.ofMillis(44_001)).retryAfterSeconds()).isEqualTo(45);
        assertThat(new RateLimitExceededException(Duration.ofSeconds(45)).retryAfterSeconds()).isEqualTo(45);
        assertThat(new RateLimitExceededException(Duration.ZERO).retryAfterSeconds()).isEqualTo(1);
    }

    /** A clock the test moves forward. */
    private static final class MovableClock extends Clock {

        private final AtomicLong millis;

        MovableClock(Instant start) {
            this.millis = new AtomicLong(start.toEpochMilli());
        }

        void advance(Duration duration) {
            millis.addAndGet(duration.toMillis());
        }

        @Override
        public long millis() {
            return millis.get();
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis());
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
