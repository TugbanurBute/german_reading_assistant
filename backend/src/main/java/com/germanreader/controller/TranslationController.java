package com.germanreader.controller;

import com.germanreader.dto.TranslationRequest;
import com.germanreader.dto.TranslationResponse;
import com.germanreader.ratelimit.DailyTranslationQuota;
import com.germanreader.ratelimit.TranslationRateLimiter;
import com.germanreader.security.AuthTokens;
import com.germanreader.service.TranslationService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/translations")
public class TranslationController {

    private final TranslationService translationService;
    private final TranslationRateLimiter rateLimiter;
    private final DailyTranslationQuota dailyQuota;

    public TranslationController(TranslationService translationService, TranslationRateLimiter rateLimiter,
                                 DailyTranslationQuota dailyQuota) {
        this.translationService = translationService;
        this.rateLimiter = rateLimiter;
        this.dailyQuota = dailyQuota;
    }

    @PostMapping
    public TranslationResponse translate(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TranslationRequest request) {
        UUID userId = AuthTokens.userId(jwt);
        rateLimiter.check(userId);
        dailyQuota.check(userId);
        return translationService.translate(request);
    }
}
