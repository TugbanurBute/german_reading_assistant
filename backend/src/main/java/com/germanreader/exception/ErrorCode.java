package com.germanreader.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "The request contains invalid values."),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "The request body could not be read."),
    REQUEST_ERROR(HttpStatus.BAD_REQUEST, "The request could not be processed."),

    MOCK_TRANSLATION_NOT_FOUND(HttpStatus.NOT_FOUND,
            "No mock translation exists for this text. Use the sample text or switch to the Gemini provider."),

    PROVIDER_INVALID_RESPONSE(HttpStatus.BAD_GATEWAY, "The translation service returned an unusable answer. Please try again."),
    PROVIDER_RATE_LIMITED(HttpStatus.SERVICE_UNAVAILABLE, "The translation service is busy. Please try again in a moment."),
    PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The translation service is currently unavailable."),
    PROVIDER_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "The translation service took too long to respond."),
    // Configuration problems: retrying does not help until the server's settings change.
    PROVIDER_MODEL_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE,
            "The configured translation model is not available. It may have been retired."),
    PROVIDER_REJECTED(HttpStatus.SERVICE_UNAVAILABLE,
            "The translation service rejected the request. The server's translation settings need to be checked."),

    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "The document does not exist. It may have been deleted."),
    INVALID_UPLOAD(HttpStatus.BAD_REQUEST, "The file must be a non-empty, UTF-8 encoded .txt text file."),
    UPLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "The file is too large. The limit is 512 KB and 100,000 characters."),

    VOCABULARY_NOT_FOUND(HttpStatus.NOT_FOUND, "The saved word does not exist. It may have been deleted."),

    TRANSLATION_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS,
            "You have reached the translation limit. Please wait a moment before translating more."),
    TRANSLATION_DAILY_LIMIT_REACHED(HttpStatus.TOO_MANY_REQUESTS,
            "You have reached today's translation limit. It resets at midnight UTC."),
    TRANSLATION_BUDGET_EXHAUSTED(HttpStatus.SERVICE_UNAVAILABLE,
            "Translations are paused for today because the service's daily limit is used up. Saved translations still work."),
    AUTH_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Please wait a few minutes and try again."),

    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Please log in."),
    WRONG_PASSWORD(HttpStatus.FORBIDDEN, "The password is incorrect."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "The email or password is incorrect."),
    EMAIL_TAKEN(HttpStatus.CONFLICT, "An account with this email already exists."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "The request was refused. Reload the page and try again."),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}
