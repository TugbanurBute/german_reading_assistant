package com.example.germanreader.dto.grammar;

import com.fasterxml.jackson.annotation.JsonValue;

public enum Article {
    DER("der"),
    DIE("die"),
    DAS("das");

    private final String value;

    Article(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
