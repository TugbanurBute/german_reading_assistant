package com.germanreader.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.germanreader.dto.grammar.Auxiliary;
import com.germanreader.dto.grammar.Tense;
import com.germanreader.dto.grammar.VerbGrammar;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TranslationResponseJsonTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void verbGrammarRoundTripsWithKindDiscriminator() {
        TranslationResponse original = new TranslationResponse(
                "ging", "went", "gehen", PartOfSpeech.VERB,
                new VerbGrammar(Tense.PRETERITE, "3rd person singular", "gegangen", Auxiliary.SEIN, null),
                "She went home.", SourceLanguage.DE, TargetLanguage.EN, TranslationType.WORD);

        String json = jsonMapper.writeValueAsString(original);
        TranslationResponse restored = jsonMapper.readValue(json, TranslationResponse.class);

        assertThat(json).contains("\"kind\":\"VERB\"").contains("\"auxiliary\":\"sein\"");
        assertThat(restored).isEqualTo(original);
        assertThat(restored.grammar()).isInstanceOf(VerbGrammar.class);
    }
}
