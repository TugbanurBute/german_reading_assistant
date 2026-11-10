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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

class AuthRateLimiterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T12:02:30Z"), ZoneOffset.UTC);

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    private AuthRateLimiter limiter(boolean enabled, String clientIpHeader) {
        return new AuthRateLimiter(redis, new AuthRateLimitProperties(enabled, 3, Duration.ofMinutes(5), 2,
                Duration.ofHours(1), clientIpHeader), CLOCK);
    }

    private final AuthRateLimiter limiter = limiter(true, "X-Real-IP");

    private static MockHttpServletRequest from(String peer) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        return request;
    }

    private static MockHttpServletRequest viaProxy(String clientAddress) {
        MockHttpServletRequest request = from("172.18.0.5"); // Nginx in the Docker network
        request.addHeader("X-Real-IP", clientAddress);
        return request;
    }

    @BeforeEach
    void redisCountsLikeIncr() {
        given(redis.execute(eq(TranslationRateLimiter.INCREMENT), anyList(), any())).willAnswer(invocation -> {
            String key = invocation.<List<String>>getArgument(1).getFirst();
            return counters.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
        });
    }

    @Test
    void refusesTheAttemptAfterTheLimitWith429AndTheTimeLeft() {
        for (int i = 0; i < 3; i++) {
            limiter.checkLogin(viaProxy("203.0.113.7"));
        }

        assertThatThrownBy(() -> limiter.checkLogin(viaProxy("203.0.113.7")))
                .isInstanceOfSatisfying(RateLimitExceededException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_RATE_LIMITED);
                    assertThat(e.getErrorCode().status().value()).isEqualTo(429);
                    assertThat(e.retryAfterSeconds()).isEqualTo(150);
                });
    }

    @Test
    void countsEachAddressAndEachActionSeparately() {
        for (int i = 0; i < 3; i++) {
            limiter.checkLogin(viaProxy("203.0.113.7"));
        }

        assertThatCode(() -> limiter.checkLogin(viaProxy("198.51.100.20"))).doesNotThrowAnyException();
        assertThatCode(() -> limiter.checkRegistration(viaProxy("203.0.113.7"))).doesNotThrowAnyException();
    }

    @Test
    void registrationHasItsOwnLimit() {
        limiter.checkRegistration(viaProxy("203.0.113.7"));
        limiter.checkRegistration(viaProxy("203.0.113.7"));

        assertThatThrownBy(() -> limiter.checkRegistration(viaProxy("203.0.113.7")))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void aClientOnTheInternetCannotChooseItsAddressWithTheHeader() {
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest request = from("203.0.113.7");
            request.addHeader("X-Real-IP", "10.0.0." + i); // a new "address" every time
            limiter.checkLogin(request);
        }

        assertThat(counters).hasSize(1).allSatisfy((key, count) -> assertThat(key).contains(":203.0.113.7:"));
        assertThatThrownBy(() -> limiter.checkLogin(from("203.0.113.7"))).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void withoutAConfiguredHeaderTheConnectionAddressCounts() {
        AuthRateLimiter direct = limiter(true, "");
        MockHttpServletRequest request = from("127.0.0.1");
        request.addHeader("X-Real-IP", "203.0.113.7");

        direct.checkLogin(request);

        assertThat(counters.keySet()).singleElement().asString().contains(":127.0.0.1:");
    }

    @Test
    void garbageInTheHeaderFallsBackToTheConnectionAddress() {
        limiter.checkLogin(viaProxy("not-an-address"));

        assertThat(counters.keySet()).singleElement().asString().contains(":172.18.0.5:");
    }

    @Test
    void ipv6AddressesOfTheSameSlash64ShareOneCounter() {
        limiter.checkLogin(viaProxy("2001:db8:1:2::1"));
        limiter.checkLogin(viaProxy("2001:db8:1:2:ffff:ffff:ffff:ffff"));
        limiter.checkLogin(viaProxy("2001:db8:1:2::abcd"));

        assertThatThrownBy(() -> limiter.checkLogin(viaProxy("2001:db8:1:2::9"))).isInstanceOf(RateLimitExceededException.class);
        assertThatCode(() -> limiter.checkLogin(viaProxy("2001:db8:1:3::1"))).doesNotThrowAnyException();
    }

    @Test
    void disabledMeansNoRedisCalls() {
        AuthRateLimiter off = limiter(false, "X-Real-IP");
        for (int i = 0; i < 10; i++) {
            off.checkLogin(viaProxy("203.0.113.7"));
        }
        verifyNoInteractions(redis);
    }

    @Test
    void anUnreachableRedisLetsLoginsThrough() {
        given(redis.execute(eq(TranslationRateLimiter.INCREMENT), anyList(), any()))
                .willThrow(new RedisConnectionFailureException("Connection refused"));

        assertThatCode(() -> {
            for (int i = 0; i < 10; i++) {
                limiter.checkLogin(viaProxy("203.0.113.7"));
            }
        }).doesNotThrowAnyException();
    }

    @Nested
    class Addresses {

        @Test
        void privateAndLoopbackPeersAreTheOnlyTrustedProxies() {
            assertThat(ClientAddress.isPrivate("127.0.0.1")).isTrue();
            assertThat(ClientAddress.isPrivate("10.1.2.3")).isTrue();
            assertThat(ClientAddress.isPrivate("172.18.0.5")).isTrue();
            assertThat(ClientAddress.isPrivate("192.168.1.10")).isTrue();
            assertThat(ClientAddress.isPrivate("::1")).isTrue();
            assertThat(ClientAddress.isPrivate("fd12:3456::1")).isTrue();
            assertThat(ClientAddress.isPrivate("203.0.113.7")).isFalse();
            assertThat(ClientAddress.isPrivate("2001:db8::1")).isFalse();
            assertThat(ClientAddress.isPrivate("localhost")).isFalse(); // names are never resolved
        }

        @Test
        void addressesAreNormalized() {
            assertThat(ClientAddress.normalize("203.0.113.7")).isEqualTo("203.0.113.7");
            assertThat(ClientAddress.normalize("2001:DB8:1:2:3:4:5:6")).isEqualTo("2001:db8:1:2:0:0:0:0/64");
            assertThat(ClientAddress.normalize("example.com")).isEqualTo("unknown");
        }
    }
}
