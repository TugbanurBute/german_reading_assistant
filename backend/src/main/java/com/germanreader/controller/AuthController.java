package com.germanreader.controller;

import com.germanreader.dto.auth.LoginRequest;
import com.germanreader.dto.auth.RegisterRequest;
import com.germanreader.dto.auth.UserResponse;
import com.germanreader.entity.AppUser;
import com.germanreader.security.AuthTokens;
import com.germanreader.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The token is only ever sent as an HttpOnly cookie; response bodies contain the user, never the token. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthTokens tokens;

    public AuthController(AuthService authService, AuthTokens tokens) {
        this.authService = authService;
        this.tokens = tokens;
    }

    @PostMapping(path = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return loggedIn(HttpStatus.CREATED, authService.register(request.email(), request.password()));
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UserResponse> login(@Valid @RequestBody LoginRequest request) {
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

    private ResponseEntity<UserResponse> loggedIn(HttpStatus status, AppUser user) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, tokens.cookie(tokens.issue(user.getId())).toString())
                .body(UserResponse.from(user));
    }
}
