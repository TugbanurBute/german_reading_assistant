package com.germanreader.exception;

public class DocumentException extends RuntimeException {

    private final ErrorCode errorCode;

    public DocumentException(ErrorCode errorCode, String internalMessage) {
        super(internalMessage);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
