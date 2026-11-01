package com.example.germanreader.controller;

import com.example.germanreader.dto.TranslationRequest;
import com.example.germanreader.dto.TranslationResponse;
import com.example.germanreader.service.TranslationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/translations")
public class TranslationController {

    private final TranslationService translationService;

    public TranslationController(TranslationService translationService) {
        this.translationService = translationService;
    }

    @PostMapping
    public TranslationResponse translate(@Valid @RequestBody TranslationRequest request) {
        return translationService.translate(request);
    }
}
