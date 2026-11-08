package com.germanreader.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.germanreader.config.WebConfig;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Test helpers for the secured API. Tests that are not about security import {@link AsDefaultUser}: every
 * MockMvc request then runs as {@link #USER_ID}, with a valid CSRF token, so those tests read as before.
 * Security itself is tested without these shortcuts, with real tokens and cookies (AuthIT, OwnershipIT).
 */
public final class TestAuth {

    public static final UUID USER_ID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");

    private TestAuth() {
    }

    /** An authenticated request as this user (the token's subject), without going through the decoder. */
    public static RequestPostProcessor as(UUID userId) {
        return jwt().jwt(token -> token.subject(userId.toString()));
    }

    /** The user row that owned rows need (foreign key); created once per database. */
    public static void ensureUser(JdbcTemplate jdbc, UUID id) {
        jdbc.update("""
                INSERT INTO app_user (id, email, password_hash, created_at)
                VALUES (?, ?, '$2a$12$not.a.real.hash.only.for.tests.xxxxxxxxxxxxxxxxxxxxxx', now())
                ON CONFLICT (id) DO NOTHING
                """, id, id + "@test.invalid");
    }

    /** For @WebMvcTest slices: our security beans plus the default user. */
    @TestConfiguration(proxyBeanMethods = false)
    @Import({SecurityConfig.class, ProblemResponses.class, AuthTokens.class, WebConfig.class, AsDefaultUser.class})
    public static class WebSecurity {
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class AsDefaultUser {

        @Bean
        MockMvcBuilderCustomizer authenticatedByDefault() {
            return builder -> builder.defaultRequest(get("/").with(as(USER_ID)).with(csrf()));
        }
    }
}
