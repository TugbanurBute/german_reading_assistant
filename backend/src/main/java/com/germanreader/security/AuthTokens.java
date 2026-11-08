package com.germanreader.security;

import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Issues access tokens and the cookie that carries them. The token is never logged or put in a response body:
 * the browser only receives it in an HttpOnly cookie that JavaScript cannot read.
 */
@Component
public class AuthTokens {

    public static final String COOKIE = "access_token";
    public static final String ISSUER = "german-reader";
    /** Only API requests need the token. */
    private static final String COOKIE_PATH = "/api";

    private final JwtEncoder encoder;
    private final AuthProperties properties;

    public AuthTokens(JwtEncoder encoder, AuthProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    /** A signed token: sub = user id, plus issuer, issue time and expiry. */
    public String issue(UUID userId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.tokenTtl()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public ResponseCookie cookie(String token) {
        return baseCookie(token).maxAge(properties.tokenTtl()).build();
    }

    /** Overwrites the cookie with an empty one that expires immediately. */
    public ResponseCookie clearedCookie() {
        return baseCookie("").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
        // SameSite=Strict: the browser never sends the token with requests started by another site.
        return ResponseCookie.from(COOKIE, value)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite("Strict")
                .path(COOKIE_PATH);
    }

    /** The user id of an authenticated request (the token's subject). */
    public static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
