package com.example.germanreader.dto.grammar;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = NounGrammar.class, name = "NOUN"),
        @JsonSubTypes.Type(value = VerbGrammar.class, name = "VERB"),
        @JsonSubTypes.Type(value = OtherGrammar.class, name = "OTHER")
})
public sealed interface Grammar permits NounGrammar, VerbGrammar, OtherGrammar {
}
