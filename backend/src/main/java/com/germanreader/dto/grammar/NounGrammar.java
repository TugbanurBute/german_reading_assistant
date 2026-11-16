package com.germanreader.dto.grammar;

public record NounGrammar(
        Article article,
        String plural,
        GrammaticalCase grammaticalCase,
        GrammaticalNumber number
) implements Grammar {
}
