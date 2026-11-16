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

@Slf4j
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class TranslationRateLimiter {

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
    private final AtomicBoolean redisDown = new AtomicBoolean(false);

    @Autowired
    public TranslationRateLimiter(StringRedisTemplate redis, RateLimitProperties properties) {
        this(redis, properties, Clock.systemUTC());
    }

    TranslationRateLimiter(StringRedisTemplate redis, RateLimitProperties properties, Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
    }

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
