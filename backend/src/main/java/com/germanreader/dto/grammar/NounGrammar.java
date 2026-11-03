package com.germanreader.dto.grammar;

/**
 * @param article         gender article of the base form (das Haus)
 * @param plural          nominative plural (Häuser)
 * @param grammaticalCase case of the word as it appears in the sentence
 * @param number          number of the word as it appears in the sentence
 */
public record NounGrammar(
        Article article,
        String plural,
        GrammaticalCase grammaticalCase,
        GrammaticalNumber number
) implements Grammar {
}
