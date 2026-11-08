package com.germanreader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.germanreader.repository.AppUserRepository;
import com.germanreader.security.AuthTokens;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * Registration, login and token checks end to end, with real BCrypt hashes, real signed tokens and the real
 * cookie. Nothing here uses the test shortcut that skips token validation.
 */
@SpringBootTest(properties = {
        "translation.provider=mock",
        "spring.flyway.schemas=" + TranslationCacheIT.SCHEMA,
        "spring.datasource.hikari.schema=" + TranslationCacheIT.SCHEMA
})
@ActiveProfiles("local")
@AutoConfigureMockMvc
class AuthIT {

    private static final String PASSWORD = "richtig-geheim-42";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private JwtEncoder jwtEncoder;

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private static String credentials(String email, String password) {
        return "{\"email\": \"" + email + "\", \"password\": \"" + password + "\"}";
    }

    private ResultActions register(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(credentials(email, password)));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(credentials(email, password)));
    }

    private static Cookie tokenCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(AuthTokens.COOKIE);
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private String token(UUID userId, Instant issuedAt, Instant expiresAt) {
        return token(jwtEncoder, AuthTokens.ISSUER, userId, issuedAt, expiresAt);
    }

    private static String token(JwtEncoder encoder, String issuer, UUID userId, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject(userId.toString())
                .issuedAt(issuedAt).expiresAt(expiresAt).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private UUID registeredUser() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD).andExpect(status().isCreated());
        return users.findByEmail(email).orElseThrow().getId();
    }

    @Test
    void registrationSetsAnHttpOnlyCookieAndStoresOnlyABcryptHash() throws Exception {
        String email = uniqueEmail();

        MvcResult result = register(email, PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andReturn();

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).startsWith(AuthTokens.COOKIE + "=")
                .contains("HttpOnly").contains("SameSite=Strict").contains("Path=/api").contains("Max-Age=28800");
        assertThat(result.getResponse().getContentAsString()).doesNotContain(tokenCookie(result).getValue());

        String hash = users.findByEmail(email).orElseThrow().getPasswordHash();
        assertThat(hash).startsWith("$2a$12$").doesNotContain(PASSWORD);
    }

    @Test
    void theTokenFromALoginOpensTheApi() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);

        Cookie cookie = tokenCookie(login("  " + email.toUpperCase() + " ", PASSWORD).andExpect(status().isOk()).andReturn());

        mockMvc.perform(get("/api/v1/auth/me").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
        mockMvc.perform(get("/api/v1/documents").cookie(cookie)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/vocabulary").cookie(cookie)).andExpect(status().isOk());
    }

    @Test
    void anEmailCanOnlyBeRegisteredOnceWhateverItsCase() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD).andExpect(status().isCreated());

        register(email.toUpperCase(), "ein-anderes-passwort")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    @Test
    void aWrongPasswordAndAnUnknownEmailGetTheSameAnswer() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);

        String wrongPassword = login(email, "falsches-passwort")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = login(uniqueEmail(), PASSWORD)
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(unknownEmail).isEqualTo(wrongPassword);
    }

    @ParameterizedTest
    @ValueSource(strings = {"kurz", "üüüüüüüüüüüüüüüüüüüüüüüüüüüüüüüüüüüüüüüü"}) // too short; 40 characters but 80 bytes (BCrypt reads 72)
    void weakOrOverlongPasswordsAreRejected(String password) throws Exception {
        register(uniqueEmail(), password)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void anInvalidEmailIsRejected() throws Exception {
        register("keine-email", PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("email"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/documents", "/api/v1/vocabulary", "/api/v1/auth/me"})
    void withoutATokenTheApiAnswers401(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE));
    }

    @Test
    void theFirstRequestAlreadyGetsTheCsrfCookieTheFrontendEchoes() throws Exception {
        Cookie csrfCookie = mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getCookie("XSRF-TOKEN");

        assertThat(csrfCookie).isNotNull();
        assertThat(csrfCookie.isHttpOnly()).isFalse(); // the frontend must be able to read it
        String email = uniqueEmail();
        mockMvc.perform(post("/api/v1/auth/register").cookie(csrfCookie).header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(credentials(email, PASSWORD)))
                .andExpect(status().isCreated());
    }

    @Test
    void translationsNeedALoginToo() throws Exception {
        mockMvc.perform(post("/api/v1/translations").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"selectedText": "Bank", "context": "Am Morgen sitzt meine Großmutter auf der Bank im Park.",
                         "sourceLanguage": "DE", "targetLanguage": "EN", "translationType": "WORD"}
                        """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void anExpiredTokenIsRejectedAndRemoved() throws Exception {
        UUID user = registeredUser();
        Instant now = Instant.now();
        String expired = token(user, now.minusSeconds(7200), now.minusSeconds(3600));

        mockMvc.perform(get("/api/v1/documents").cookie(new Cookie(AuthTokens.COOKIE, expired)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")));
    }

    @Test
    void aTamperedTokenIsRejected() throws Exception {
        UUID user = registeredUser();
        String valid = token(user, Instant.now(), Instant.now().plusSeconds(600));
        // Change the subject in the payload but keep the old signature.
        String[] parts = valid.split("\\.");
        String otherPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(java.util.Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                        .replace(user.toString(), UUID.randomUUID().toString()).getBytes(StandardCharsets.UTF_8));
        String tampered = parts[0] + "." + otherPayload + "." + parts[2];

        mockMvc.perform(get("/api/v1/documents").cookie(new Cookie(AuthTokens.COOKIE, tampered)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/documents").cookie(new Cookie(AuthTokens.COOKIE, "not-a-jwt")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenSignedWithAnotherKeyOrForAnotherIssuerIsRejected() throws Exception {
        UUID user = registeredUser();
        JwtEncoder otherKey = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(
                "a-completely-different-secret-key-0123456789".getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        Instant now = Instant.now();

        mockMvc.perform(get("/api/v1/documents").cookie(new Cookie(AuthTokens.COOKIE,
                        token(otherKey, AuthTokens.ISSUER, user, now, now.plusSeconds(600)))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/documents").cookie(new Cookie(AuthTokens.COOKIE,
                        token(jwtEncoder, "someone-else", user, now, now.plusSeconds(600)))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changesWithoutACsrfTokenAreForbidden() throws Exception {
        UUID user = registeredUser();
        Cookie cookie = new Cookie(AuthTokens.COOKIE, token(user, Instant.now(), Instant.now().plusSeconds(600)));

        mockMvc.perform(post("/api/v1/documents").cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"T\", \"content\": \"Text.\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(post("/api/v1/documents").cookie(cookie).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"T\", \"content\": \"Text.\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void aStaleCookieDoesNotBlockLoggingIn() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);

        mockMvc.perform(post("/api/v1/auth/login").with(csrf()).cookie(new Cookie(AuthTokens.COOKIE, "expired-or-broken"))
                        .contentType(MediaType.APPLICATION_JSON).content(credentials(email, PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void logoutRemovesTheCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout").with(csrf()))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.startsWith(AuthTokens.COOKIE + "=;"),
                        org.hamcrest.Matchers.containsString("Max-Age=0"),
                        org.hamcrest.Matchers.containsString("HttpOnly"))));
    }
}
