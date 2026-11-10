package com.germanreader.ratelimit;

import com.germanreader.exception.ErrorCode;
import java.time.Duration;


public class RateLimitExceededException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Duration retryAfter;

    public RateLimitExceededException(Duration retryAfter) {
        this(ErrorCode.TRANSLATION_RATE_LIMITED, retryAfter);
    }

    public RateLimitExceededException(ErrorCode errorCode, Duration retryAfter) {
        super(errorCode.name());
        this.errorCode = errorCode;
        this.retryAfter = retryAfter;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public long retryAfterSeconds() {
        return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }
}
