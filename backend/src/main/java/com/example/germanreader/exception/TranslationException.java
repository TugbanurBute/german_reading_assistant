package com.example.germanreader.exception;

public class TranslationException extends RuntimeException {

    private final ErrorCode errorCode;

    public TranslationException(ErrorCode errorCode, String internalMessage) {
        super(internalMessage);
        this.errorCode = errorCode;
    }

    public TranslationException(ErrorCode errorCode, String internalMessage, Throwable cause) {
        super(internalMessage, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
