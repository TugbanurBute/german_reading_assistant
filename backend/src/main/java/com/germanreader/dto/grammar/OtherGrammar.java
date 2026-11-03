package com.germanreader.dto.grammar;

/**
 * Grammar for every other word class (adjectives, adverbs, prepositions, ...).
 *
 * @param details short human-readable note, e.g. "comparative of schnell" or "takes the dative"
 */
public record OtherGrammar(String details) implements Grammar {
}
