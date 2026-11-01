package com.example.germanreader.dto.grammar;

import com.fasterxml.jackson.annotation.JsonValue;

public enum Auxiliary {
    HABEN("haben"),
    SEIN("sein");

    private final String value;

    Auxiliary(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
