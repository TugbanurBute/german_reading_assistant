package com.germanreader.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.germanreader.dto.PartOfSpeech;
import com.germanreader.dto.TargetLanguage;
import com.germanreader.dto.grammar.Article;
import com.germanreader.dto.vocabulary.SaveVocabularyRequest;
import com.germanreader.dto.vocabulary.VocabularyResponse;
import com.germanreader.exception.ErrorCode;
import com.germanreader.exception.VocabularyException;
import com.germanreader.service.VocabularyService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(controllers = VocabularyController.class,
        properties = "app.cors.allowed-origins=http://localhost:5173")
class VocabularyControllerTest {

    private static final String URL = "/api/v1/vocabulary";
    private static final UUID ID = UUID.fromString("5b8ae4f2-8647-404b-a675-4d1a2b3c4d5e");
    private static final VocabularyResponse ENTRY = new VocabularyResponse(ID, "Bank", "Bank", Article.DIE,
            PartOfSpeech.NOUN, "bench", TargetLanguage.EN, "Am Morgen sitzt meine Großmutter auf der Bank im Park.",
            null, null, Instant.parse("2026-11-05T10:15:30Z"));

    private static final String VALID_BODY = """
            {"originalText": "Bank", "lemma": "Bank", "article": "die", "partOfSpeech": "NOUN",
             "translation": "bench", "targetLanguage": "EN",
             "sourceSentence": "Am Morgen sitzt meine Großmutter auf der Bank im Park."}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VocabularyService vocabularyService;

    private ResultActions postJson(String body) throws Exception {
        return mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void aNewWordReturns201WithLocationAndTheEntry() throws Exception {
        given(vocabularyService.save(any())).willReturn(new VocabularyService.SaveResult(ENTRY, true));

        postJson(VALID_BODY)
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/vocabulary/" + ID))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.lemma").value("Bank"))
                .andExpect(jsonPath("$.article").value("die"))
                .andExpect(jsonPath("$.translation").value("bench"))
                .andExpect(jsonPath("$.sourceDocumentId").isEmpty());

        ArgumentCaptor<SaveVocabularyRequest> captor = ArgumentCaptor.forClass(SaveVocabularyRequest.class);
        verify(vocabularyService).save(captor.capture());
        // "die" in JSON is the Article enum (its @JsonValue), as in translation responses.
        assertThat(captor.getValue().article()).isEqualTo(Article.DIE);
    }

    @Test
    void anAlreadySavedMeaningReturns200WithTheExistingEntry() throws Exception {
        given(vocabularyService.save(any())).willReturn(new VocabularyService.SaveResult(ENTRY, false));

        postJson(VALID_BODY)
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.id").value(ID.toString()));
    }

    @Test
    void onlyWordTranslationAndLanguageAreRequired() throws Exception {
        given(vocabularyService.save(any())).willReturn(new VocabularyService.SaveResult(ENTRY, true));

        postJson("""
                {"originalText": "schnell", "translation": "quickly", "targetLanguage": "EN"}
                """).andExpect(status().isCreated());
    }

    @Test
    void missingRequiredFieldsAreValidationErrors() throws Exception {
        postJson("""
                {"originalText": " ", "translation": "", "targetLanguage": null}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors.length()").value(3));

        verifyNoInteractions(vocabularyService);
    }

    @Test
    void tooLongValuesAreValidationErrors() throws Exception {
        String body = """
                {"originalText": "%s", "translation": "%s", "targetLanguage": "TR", "sourceSentence": "%s"}
                """.formatted("a".repeat(101), "b".repeat(301), "c".repeat(1001));

        postJson(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors.length()").value(3));

        verifyNoInteractions(vocabularyService);
    }

    @Test
    void unknownEnumValuesAreValidationErrors() throws Exception {
        postJson("""
                {"originalText": "Bank", "translation": "bench", "targetLanguage": "EN", "article": "dem"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("article"));
    }

    @Test
    void listReturnsTheEntries() throws Exception {
        given(vocabularyService.list()).willReturn(List.of(ENTRY));

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].lemma").value("Bank"))
                .andExpect(jsonPath("$[0].targetLanguage").value("EN"));
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete(URL + "/" + ID)).andExpect(status().isNoContent());

        verify(vocabularyService).delete(ID);
    }

    @Test
    void deletingAMissingEntryReturns404() throws Exception {
        willThrow(new VocabularyException(ErrorCode.VOCABULARY_NOT_FOUND, "not found"))
                .given(vocabularyService).delete(ID);

        mockMvc.perform(delete(URL + "/" + ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VOCABULARY_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value(ErrorCode.VOCABULARY_NOT_FOUND.message()));
    }

    @Test
    void aNonUuidIdIsARequestError() throws Exception {
        mockMvc.perform(delete(URL + "/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_ERROR"));
    }
}
