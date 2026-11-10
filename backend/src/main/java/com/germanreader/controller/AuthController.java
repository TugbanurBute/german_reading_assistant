package com.germanreader.controller;

import com.germanreader.dto.auth.DeleteAccountRequest;
import com.germanreader.dto.auth.LoginRequest;
import com.germanreader.dto.auth.RegisterRequest;
import com.germanreader.dto.auth.UserResponse;
import com.germanreader.entity.AppUser;
import com.germanreader.ratelimit.AuthRateLimiter;
import com.germanreader.security.AuthTokens;
import com.germanreader.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthTokens tokens;
    private final AuthRateLimiter rateLimiter;

    public AuthController(AuthService authService, AuthTokens tokens, AuthRateLimiter rateLimiter) {
        this.authService = authService;
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping(path = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        rateLimiter.checkRegistration(http);
        return loggedIn(HttpStatus.CREATED, authService.register(request.email(), request.password()));
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UserResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        rateLimiter.checkLogin(http);
        return loggedIn(HttpStatus.OK, authService.login(request.email(), request.password()));
    }

    /** Stateless tokens can't be revoked on the server; logging out removes the cookie from the browser. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, tokens.clearedCookie().toString()).build();
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return UserResponse.from(authService.currentUser(AuthTokens.userId(jwt)));
    }


    @DeleteMapping(path = "/me", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> deleteAccount(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DeleteAccountRequest request,
                                              HttpServletRequest http) {
        rateLimiter.checkLogin(http); // a password check: the same guessing risk as a login
        authService.deleteAccount(AuthTokens.userId(jwt), request.password());
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, tokens.clearedCookie().toString()).build();
    }

    private ResponseEntity<UserResponse> loggedIn(HttpStatus status, AppUser user) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, tokens.cookie(tokens.issue(user.getId())).toString())
                .body(UserResponse.from(user));
    }
}
