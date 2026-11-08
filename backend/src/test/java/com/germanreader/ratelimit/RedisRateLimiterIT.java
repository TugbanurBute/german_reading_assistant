package com.germanreader.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The real Lua script against a real Redis (e.g. `docker compose up -d redis`). Skipped when nothing listens on
 * REDIS_HOST:REDIS_PORT (default localhost:6379), so the rest of the suite never depends on Redis.
 */
@EnabledIf("redisIsReachable")
class RedisRateLimiterIT {

    private static final String HOST = System.getenv().getOrDefault("REDIS_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));

    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;

    static boolean redisIsReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @BeforeAll
    static void connect() {
        connections = new LettuceConnectionFactory(new RedisStandaloneConfiguration(HOST, PORT));
        connections.afterPropertiesSet();
        connections.start();
        redis = new StringRedisTemplate(connections);
    }

    @AfterAll
    static void disconnect() {
        connections.destroy();
    }

    private static TranslationRateLimiter limiter(int limit) {
        return new TranslationRateLimiter(redis, new RateLimitProperties(true, limit, Duration.ofSeconds(60)));
    }

    @Test
    void theScriptCountsAndSetsAnExpiry() {
        UUID user = UUID.randomUUID();
        TranslationRateLimiter limiter = limiter(3);

        assertThatCode(() -> {
            limiter.check(user);
            limiter.check(user);
            limiter.check(user);
        }).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.check(user)).isInstanceOf(RateLimitExceededException.class);

        String key = redis.keys("rate:translations:" + user + ":*").iterator().next();
        assertThat(redis.opsForValue().get(key)).isEqualTo("4");
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(60_000);
    }

    @Test
    void concurrentRequestsNeverGetMoreThanTheLimit() throws Exception {
        UUID user = UUID.randomUUID();
        TranslationRateLimiter limiter = limiter(10);
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(30);
        for (int i = 0; i < 30; i++) {
            pool.submit(() -> {
                start.await();
                try {
                    limiter.check(user);
                    allowed.incrementAndGet();
                } catch (RateLimitExceededException e) {
                    // expected for 20 of them
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // A window boundary during the test could allow up to 20; at the usual case it is exactly 10.
        assertThat(allowed.get()).isBetween(10, 20);
    }
}
