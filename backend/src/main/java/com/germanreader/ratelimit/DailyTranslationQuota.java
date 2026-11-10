package com.germanreader.ratelimit;

import com.germanreader.exception.ErrorCode;
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
import org.springframework.stereotype.Component;

@Slf4j
@Component
@EnableConfigurationProperties(QuotaProperties.class)
public class DailyTranslationQuota {

    private final StringRedisTemplate redis;
    private final QuotaProperties properties;
    private final Clock clock;
    private final AtomicBoolean redisDown = new AtomicBoolean(false);

    @Autowired
    public DailyTranslationQuota(StringRedisTemplate redis, QuotaProperties properties) {
        this(redis, properties, Clock.systemUTC());
    }

    DailyTranslationQuota(StringRedisTemplate redis, QuotaProperties properties, Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
    }

    public void check(UUID userId) {
        if (!properties.enabled()) {
            return;
        }
        Duration untilReset = DailyQuotas.untilReset(clock);
        String key = "quota:translations:" + userId + ":" + DailyQuotas.today(clock);
        Long count;
        try {
            long ttl = untilReset.plusHours(1).toMillis();
            count = redis.execute(TranslationRateLimiter.INCREMENT, List.of(key), String.valueOf(ttl));
            if (redisDown.getAndSet(false)) {
                log.info("Redis is reachable again; daily translation quotas are back on");
            }
        } catch (DataAccessException e) {
            if (!redisDown.getAndSet(true)) {
                log.warn("Redis is unreachable ({}); per-user daily quotas are paused, the global Gemini budget still applies",
                        e.getClass().getSimpleName());
            }
            return;
        }
        if (count != null && count > properties.translationsPerUserPerDay()) {
            throw new RateLimitExceededException(ErrorCode.TRANSLATION_DAILY_LIMIT_REACHED, untilReset);
        }
    }
}
