package com.germanreader.service;

import com.germanreader.dto.SourceLanguage;
import com.germanreader.dto.TargetLanguage;
import com.germanreader.dto.TranslationRequest;
import com.germanreader.dto.TranslationType;
import com.germanreader.entity.TranslationCacheEntry;
import com.germanreader.util.TextNormalizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record TranslationCacheKey(
        String hash,
        String provider,
        String promptVersion,
        TranslationType translationType,
        SourceLanguage sourceLanguage,
        TargetLanguage targetLanguage,
        String selectedText,
        String context
) {

    public static TranslationCacheKey of(String provider, String promptVersion, TranslationRequest request) {
        String text = TextNormalizer.normalize(request.selectedText());
        String context = request.translationType() == TranslationType.WORD
                ? TextNormalizer.normalize(request.context())
                : null;

        // Normalized text never contains '\n' (all whitespace becomes a single space),
        // so joining with '\n' cannot make two different field combinations look identical.
        String identity = String.join("\n", provider, promptVersion, request.translationType().name(),
                request.sourceLanguage().name(), request.targetLanguage().name(), text,
                context == null ? "" : context);

        return new TranslationCacheKey(sha256(identity), provider, promptVersion, request.translationType(),
                request.sourceLanguage(), request.targetLanguage(), text, context);
    }

    public TranslationCacheEntry toEntry(String responseJson) {
        return new TranslationCacheEntry(hash, provider, promptVersion, translationType, sourceLanguage,
                targetLanguage, selectedText, context, responseJson);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every Java platform", e);
        }
    }
}
