package com.germanreader.analytics;

import com.germanreader.security.AuthTokens;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final AnalyticsService analytics;

    public AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/vocabulary")
    public VocabularyStats vocabulary(@AuthenticationPrincipal Jwt jwt) {
        return analytics.summary(AuthTokens.userId(jwt));
    }
}
