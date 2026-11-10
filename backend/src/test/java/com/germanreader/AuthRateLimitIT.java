package com.germanreader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;


@SpringBootTest(properties = {
        "translation.provider=mock",
        "spring.flyway.schemas=" + TranslationCacheIT.SCHEMA,
        "spring.datasource.hikari.schema=" + TranslationCacheIT.SCHEMA,
        "app.rate-limit.auth.enabled=true",
        "app.rate-limit.auth.login-limit=3",
        "app.rate-limit.auth.register-limit=2",
        "app.rate-limit.auth.client-ip-header=X-Real-IP"
})
@ActiveProfiles("local")
@AutoConfigureMockMvc
@EnabledIf("redisIsReachable")
class AuthRateLimitIT {

    private static final String PASSWORD = "richtig-geheim-42";
    private static final String NGINX = "172.18.0.5";

    @Autowired
    private MockMvc mockMvc;

    private String client;

    static boolean redisIsReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(System.getenv().getOrDefault("REDIS_HOST", "localhost"),
                    Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"))), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String randomPublicAddress() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "198." + (18 + random.nextInt(2)) + "." + random.nextInt(256) + "." + (1 + random.nextInt(254));
    }

    @BeforeEach
    void newClient() {
        client = randomPublicAddress();
    }

    private ResultActions call(String path, String email, String password, String peer, String realIp) throws Exception {
        return mockMvc.perform(post(path).with(csrf())
                .with(request -> {
                    request.setRemoteAddr(peer);
                    return request;
                })
                .header("X-Real-IP", realIp)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"" + email + "\", \"password\": \"" + password + "\"}"));
    }

    private ResultActions login(String email, String password) throws Exception {
        return call("/api/v1/auth/login", email, password, NGINX, client);
    }

    private ResultActions register(String email) throws Exception {
        return call("/api/v1/auth/register", email, PASSWORD, NGINX, client);
    }

    private static String uniqueEmail() {
        return "limit-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    void theFourthLoginFromOneAddressGets429WhateverTheEmail() throws Exception {
        String email = uniqueEmail();
        register(email).andExpect(status().isCreated());

        login(email, PASSWORD).andExpect(status().isOk());
        login(email, "falsches-passwort").andExpect(status().isUnauthorized());
        login(uniqueEmail(), PASSWORD).andExpect(status().isUnauthorized());

        String known = login(email, PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AUTH_RATE_LIMITED"))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andReturn().getResponse().getContentAsString();
        String unknown = login(uniqueEmail(), PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getContentAsString();

        assertThat(unknown.replaceAll("\"retryAfterSeconds\":\\d+", ""))
                .isEqualTo(known.replaceAll("\"retryAfterSeconds\":\\d+", ""));
    }

    @Test
    void anotherAddressIsNotAffected() throws Exception {
        String email = uniqueEmail();
        register(email).andExpect(status().isCreated());
        for (int i = 0; i < 3; i++) {
            login(email, "falsches-passwort").andExpect(status().isUnauthorized());
        }
        login(email, PASSWORD).andExpect(status().isTooManyRequests());

        client = randomPublicAddress();

        login(email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void registrationsHaveTheirOwnLimit() throws Exception {
        register(uniqueEmail()).andExpect(status().isCreated());
        register(uniqueEmail()).andExpect(status().isCreated());

        register(uniqueEmail())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AUTH_RATE_LIMITED"));
    }

    @Test
    void aDirectClientCannotEscapeTheLimitWithForgedHeaders() throws Exception {
        String peer = randomPublicAddress();
        for (int i = 0; i < 3; i++) {
            call("/api/v1/auth/login", uniqueEmail(), PASSWORD, peer, randomPublicAddress())
                    .andExpect(status().isUnauthorized());
        }

        call("/api/v1/auth/login", uniqueEmail(), PASSWORD, peer, randomPublicAddress())
                .andExpect(status().isTooManyRequests());
    }
}
