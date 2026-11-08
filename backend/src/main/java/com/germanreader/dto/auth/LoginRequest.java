package com.germanreader.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** No password rules here: a wrong login must look the same whatever was typed. */
public record LoginRequest(
        @NotBlank @Size(max = RegisterRequest.MAX_EMAIL_LENGTH) String email,
        @NotBlank @Size(max = 1000) String password
) {

    @Override
    public String toString() {
        return "LoginRequest[password=***]";
    }
}
