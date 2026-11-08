package com.germanreader.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** BCrypt reads at most 72 bytes, so longer passwords are refused rather than silently cut. */
public record RegisterRequest(
        @NotBlank @Email @Size(max = RegisterRequest.MAX_EMAIL_LENGTH) String email,
        @NotNull @Size(min = RegisterRequest.MIN_PASSWORD_LENGTH, max = RegisterRequest.MAX_PASSWORD_BYTES) String password
) {
    public static final int MAX_EMAIL_LENGTH = 254;
    public static final int MIN_PASSWORD_LENGTH = 8;
    public static final int MAX_PASSWORD_BYTES = 72;

    @Override
    public String toString() {
        return "RegisterRequest[password=***]";
    }
}
