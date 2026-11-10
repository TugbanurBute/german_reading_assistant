package com.germanreader.ratelimit;

import com.germanreader.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@EnableConfigurationProperties(AuthRateLimitProperties.class)
public class AuthRateLimiter {

    private final StringRedisTemplate redis;
    private final AuthRateLimitProperties properties;
    private final Clock clock;
    private final AtomicBoolean redisDown = new AtomicBoolean(false);

    @Autowired // the other constructor takes a test clock
    public AuthRateLimiter(StringRedisTemplate redis, AuthRateLimitProperties properties) {
        this(redis, properties, Clock.systemUTC());
    }

    AuthRateLimiter(StringRedisTemplate redis, AuthRateLimitProperties properties, Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
    }

    public void checkLogin(HttpServletRequest request) {
        check("login", request, properties.loginLimit(), properties.loginWindow());
    }

    public void checkRegistration(HttpServletRequest request) {
        check("register", request, properties.registerLimit(), properties.registerWindow());
    }

    private void check(String action, HttpServletRequest request, int limit, Duration window) {
        if (!properties.enabled()) {
            return;
        }
        long windowMillis = window.toMillis();
        long now = clock.millis();
        long windowNumber = now / windowMillis;
        String address = ClientAddress.of(request, properties.clientIpHeader());
        String key = "rate:auth:" + action + ":" + address + ":" + windowNumber;

        Long count;
        try {
            count = redis.execute(TranslationRateLimiter.INCREMENT, List.of(key), String.valueOf(windowMillis));
            if (redisDown.getAndSet(false)) {
                log.info("Redis is reachable again; login and registration limits are back on");
            }
        } catch (DataAccessException e) {
            if (!redisDown.getAndSet(true)) {
                log.warn("Redis is unreachable ({}); logins and registrations are not rate limited until it is back",
                        e.getClass().getSimpleName());
            }
            return;
        }
        if (count != null && count > limit) {
            log.debug("Too many {} attempts from one address", action);
            throw new RateLimitExceededException(ErrorCode.AUTH_RATE_LIMITED,
                    Duration.ofMillis((windowNumber + 1) * windowMillis - now));
        }
    }
}
