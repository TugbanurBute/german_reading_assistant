package com.germanreader.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;


@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    static final int MIN_SECRET_BYTES = 32;

    private static final List<String> PUBLIC_PATHS = List.of("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/logout");

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ProblemResponses problems, JwtDecoder jwtDecoder,
                                    AuthProperties properties) throws Exception {
        http
                .cors(Customizer.withDefaults()) // uses WebConfig's CorsConfigurationSource
                .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfCookies(properties)))
                .addFilterAfter(csrfCookieFilter(), CsrfFilter.class)
                .addFilterAt(tokenFilter(jwtDecoder, problems), BearerTokenAuthenticationFilter.class)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.POST, PUBLIC_PATHS.toArray(String[]::new)).permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems));
        return http.build();
    }

    /**
     * Reads the JWT from the cookie and authenticates the request with it. Added as a plain filter instead of
     * http.oauth2ResourceServer(), because that configurer turns CSRF protection off for every request that
     * carries a token: right for Authorization headers, wrong for a cookie the browser sends on its own.
     * Invalid or expired tokens end in {@link ProblemResponses#commence} (401).
     */
    private static BearerTokenAuthenticationFilter tokenFilter(JwtDecoder jwtDecoder, ProblemResponses problems) {
        var filter = new BearerTokenAuthenticationFilter(new ProviderManager(new JwtAuthenticationProvider(jwtDecoder)));
        filter.setBearerTokenResolver(SecurityConfig::tokenFromCookie);
        filter.setAuthenticationEntryPoint(problems);
        return filter;
    }

    private static String tokenFromCookie(HttpServletRequest request) {
        if (PUBLIC_PATHS.contains(request.getRequestURI()) || request.getCookies() == null) {
            return null;
        }
        return Arrays.stream(request.getCookies())
                .filter(cookie -> AuthTokens.COOKIE.equals(cookie.getName()) && !cookie.getValue().isBlank())
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    private static CookieCsrfTokenRepository csrfCookies(AuthProperties properties) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie.secure(properties.cookieSecure()).sameSite("Strict"));
        return repository;
    }


    private static Filter csrfCookieFilter() {
        return (request, response, chain) -> {
            if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
                token.getToken();
            }
            chain.doFilter(request, response);
        };
    }

    @Bean
    SecretKey jwtSigningKey(AuthProperties properties) {
        String secret = properties.jwtSecret();
        if (secret.isBlank()) {
            if (!properties.allowGeneratedSecret()) {
                throw new IllegalStateException("JWT_SECRET must be set (at least " + MIN_SECRET_BYTES + " bytes).");
            }
            log.warn("JWT_SECRET is not set: using a random key. Everyone has to log in again after a restart.");
            byte[] random = new byte[MIN_SECRET_BYTES];
            new SecureRandom().nextBytes(random);
            return new SecretKeySpec(random, "HmacSHA256");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            // Fixed message: the secret itself must never appear in a log.
            throw new IllegalStateException("JWT_SECRET is too short: it needs at least " + MIN_SECRET_BYTES + " bytes.");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey, ObjectProvider<ActiveAccountValidator> activeAccount) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(JwtValidators.createDefaultWithIssuer(AuthTokens.ISSUER));
        activeAccount.ifAvailable(validators::add);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
