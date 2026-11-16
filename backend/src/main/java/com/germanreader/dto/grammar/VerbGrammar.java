package com.germanreader.dto.grammar;

public record VerbGrammar(
        Tense tense,
        String person,
        String pastParticiple,
        Auxiliary auxiliary,
        String separablePrefix
) implements Grammar {
}
