package com.germanreader.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeleteAccountRequest(@NotBlank @Size(max = 1000) String password) {

    @Override
    public String toString() {
        return "DeleteAccountRequest[password=***]";
    }
}
