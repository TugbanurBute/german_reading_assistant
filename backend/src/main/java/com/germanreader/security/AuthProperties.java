package com.germanreader.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Authentication settings. The secret is deliberately not validated here: a failing check on a bound property
 * prints its value in the startup error. SecurityConfig checks it and fails with a fixed message instead.
 *
 * @param jwtSecret            HMAC key for signing tokens (JWT_SECRET), at least 32 bytes
 * @param tokenTtl             how long a login lasts (AUTH_TOKEN_TTL)
 * @param cookieSecure         send the token cookie over HTTPS only (AUTH_COOKIE_SECURE); off only in the local profile
 * @param allowGeneratedSecret local profile: without JWT_SECRET, use a random key (logins end on restart)
 */
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        @DefaultValue("") String jwtSecret,
        @DefaultValue("8h") Duration tokenTtl,
        @DefaultValue("true") boolean cookieSecure,
        @DefaultValue("false") boolean allowGeneratedSecret
) {

    @Override
    public String toString() {
        return "AuthProperties[jwtSecret=***, tokenTtl=%s, cookieSecure=%s, allowGeneratedSecret=%s]"
                .formatted(tokenTtl, cookieSecure, allowGeneratedSecret);
    }
}
