package com.germanreader.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Fixed-window rate limit for translations, counted in Redis so it holds across restarts and several backend
 * instances. Windows are aligned to the clock (e.g. 12:00:00–12:00:59 for a 60 s window); each user has one
 * counter per window: {@code rate:translations:<userId>:<window number>}.
 *
 * <p>If Redis is unreachable the request is allowed (fail-open): the limit is a safeguard, and an outage of it
 * should not stop everyone from reading. Logins, the PostgreSQL cache and Gemini's own quota still apply.
 */
@Slf4j
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class TranslationRateLimiter {

    /**
     * INCR and, for the first request of a window, PEXPIRE, as one atomic step. As two separate calls, a crash
     * in between could leave a counter without expiry, blocking that user for good.
     */
    static final RedisScript<Long> INCREMENT = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;
    private final Clock clock;
    /** Logs the outage once instead of on every request. */
    private final AtomicBoolean redisDown = new AtomicBoolean(false);

    @Autowired // the other constructor takes a test clock
    public TranslationRateLimiter(StringRedisTemplate redis, RateLimitProperties properties) {
        this(redis, properties, Clock.systemUTC());
    }

    TranslationRateLimiter(StringRedisTemplate redis, RateLimitProperties properties, Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
    }

    /** Counts one translation for this user; throws when the window's limit is already used up. */
    public void check(UUID userId) {
        if (!properties.enabled()) {
            return;
        }
        long windowMillis = properties.window().toMillis();
        long now = clock.millis();
        long window = now / windowMillis;
        String key = "rate:translations:" + userId + ":" + window;

        Long count;
        try {
            count = redis.execute(INCREMENT, List.of(key), String.valueOf(windowMillis));
            if (redisDown.getAndSet(false)) {
                log.info("Redis is reachable again; translation rate limiting is back on");
            }
        } catch (DataAccessException e) {
            if (!redisDown.getAndSet(true)) {
                log.warn("Redis is unreachable ({}); translations are not rate limited until it is back",
                        e.getClass().getSimpleName());
            }
            return;
        }

        if (count != null && count > properties.limit()) {
            throw new RateLimitExceededException(Duration.ofMillis((window + 1) * windowMillis - now));
        }
    }
}
