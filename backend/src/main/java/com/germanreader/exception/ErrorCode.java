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

    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "The document does not exist. It may have been deleted."),
    INVALID_UPLOAD(HttpStatus.BAD_REQUEST, "The file must be a non-empty, UTF-8 encoded .txt text file."),
    UPLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "The file is too large. The limit is 512 KB and 100,000 characters."),

    VOCABULARY_NOT_FOUND(HttpStatus.NOT_FOUND, "The saved word does not exist. It may have been deleted."),

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
