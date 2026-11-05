package com.germanreader.exception;

public class VocabularyException extends RuntimeException {

    private final ErrorCode errorCode;

    public VocabularyException(ErrorCode errorCode, String internalMessage) {
        super(internalMessage);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
