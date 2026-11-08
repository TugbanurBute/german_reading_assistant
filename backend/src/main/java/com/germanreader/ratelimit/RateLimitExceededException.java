package com.germanreader.ratelimit;

import java.time.Duration;

/** The user used up this window's translations; {@code retryAfter} is the time until the next window. */
public class RateLimitExceededException extends RuntimeException {

    private final Duration retryAfter;

    public RateLimitExceededException(Duration retryAfter) {
        super("Translation rate limit reached");
        this.retryAfter = retryAfter;
    }

    /** Whole seconds, rounded up, never less than 1: the value of the Retry-After header. */
    public long retryAfterSeconds() {
        return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }
}
