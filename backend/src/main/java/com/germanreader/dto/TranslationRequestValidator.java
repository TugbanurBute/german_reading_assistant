package com.germanreader.dto;

import com.germanreader.util.TextNormalizer;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class TranslationRequestValidator
        implements ConstraintValidator<ValidTranslationRequest, TranslationRequest> {

    @Override
    public boolean isValid(TranslationRequest request, ConstraintValidatorContext context) {
        // Single-field problems (nulls, blanks) are reported by the field annotations already.
        if (request.translationType() != TranslationType.WORD || isBlank(request.selectedText())) {
            return true;
        }

        if (request.selectedText().length() > TranslationRequest.MAX_WORD_LENGTH) {
            return violation(context, "selectedText",
                    "must be at most " + TranslationRequest.MAX_WORD_LENGTH + " characters for WORD translations");
        }
        if (isBlank(request.context())) {
            return violation(context, "context", "is required for WORD translations");
        }

        String word = TextNormalizer.normalize(request.selectedText());
        String sentence = TextNormalizer.normalize(request.context());
        if (!sentence.contains(word)) {
            return violation(context, "selectedText", "must appear in the context sentence");
        }
        return true;
    }

    private static boolean violation(ConstraintValidatorContext context, String field, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message)
                .addPropertyNode(field)
                .addConstraintViolation();
        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
