package com.germanreader.security;

import com.germanreader.exception.ErrorCode;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 401 and 403 answers from Spring Security, in the same problem-details format (with a "code") as every
 * other error of the API. Neither the token nor the reason it was rejected is logged or returned.
 */
@Component
public class ProblemResponses implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JsonMapper jsonMapper;
    private final AuthTokens tokens;

    public ProblemResponses(JsonMapper jsonMapper, AuthTokens tokens) {
        this.jsonMapper = jsonMapper;
        this.tokens = tokens;
    }

    /** No token, or an expired, tampered or otherwise invalid one. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        if (hasTokenCookie(request)) {
            // The token is no longer valid: let the browser drop it.
            response.addHeader(HttpHeaders.SET_COOKIE, tokens.clearedCookie().toString());
        }
        write(response, ErrorCode.UNAUTHENTICATED);
    }

    /** Logged in, but the request was refused: here, a missing or wrong CSRF token. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        write(response, ErrorCode.FORBIDDEN);
    }

    private void write(HttpServletResponse response, ErrorCode code) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), code.message());
        problem.setProperty("code", code.name());
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }

    private static boolean hasTokenCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        return cookies != null && Arrays.stream(cookies).anyMatch(c -> AuthTokens.COOKIE.equals(c.getName()));
    }
}
