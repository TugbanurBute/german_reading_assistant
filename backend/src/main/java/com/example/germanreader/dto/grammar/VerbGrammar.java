package com.example.germanreader.dto.grammar;

/**
 * The infinitive is not repeated here: it is the response's {@code lemma}.
 *
 * @param tense            tense of the form used in the sentence
 * @param person           person and number in plain words, e.g. "3rd person plural"
 * @param pastParticiple   Partizip II (gespielt, gegangen)
 * @param auxiliary        auxiliary used for the perfect tenses
 * @param separablePrefix  prefix of a separable verb (an in "anrufen"), if any
 */
public record VerbGrammar(
        Tense tense,
        String person,
        String pastParticiple,
        Auxiliary auxiliary,
        String separablePrefix
) implements Grammar {
}
