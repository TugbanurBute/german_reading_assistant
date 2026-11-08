package com.germanreader.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.germanreader.dto.PartOfSpeech;
import com.germanreader.dto.SourceLanguage;
import com.germanreader.dto.TargetLanguage;
import com.germanreader.dto.TranslationRequest;
import com.germanreader.dto.TranslationResponse;
import com.germanreader.dto.TranslationType;
import com.germanreader.dto.grammar.Article;
import com.germanreader.dto.grammar.GrammaticalCase;
import com.germanreader.dto.grammar.GrammaticalNumber;
import com.germanreader.dto.grammar.NounGrammar;
import com.germanreader.exception.ErrorCode;
import com.germanreader.exception.TranslationException;
import com.germanreader.service.TranslationService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.germanreader.security.TestAuth;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(controllers = TranslationController.class,
        properties = {"app.cors.allowed-origins=http://localhost:5173", "app.auth.allow-generated-secret=true"})
@Import(TestAuth.WebSecurity.class)
class TranslationControllerTest {

    private static final String URL = "/api/v1/translations";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TranslationService translationService;

    private ResultActions postJson(String body) throws Exception {
        return mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String wordRequest(String selectedText, String context) {
        return """
                {"selectedText": %s, "context": %s, "sourceLanguage": "DE",
                 "targetLanguage": "TR", "translationType": "WORD"}
                """.formatted(json(selectedText), json(context));
    }

    private static String json(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    @Nested
    class ValidRequests {

        @Test
        void wordRequestReturnsServiceResultWithTypedGrammar() throws Exception {
            given(translationService.translate(any())).willReturn(new TranslationResponse(
                    "Häusern", "evler", "Haus", PartOfSpeech.NOUN,
                    new NounGrammar(Article.DAS, "Häuser", GrammaticalCase.DATIVE, GrammaticalNumber.PLURAL),
                    "Çocuklar evlerin arasında oynuyor.",
                    SourceLanguage.DE, TargetLanguage.TR, TranslationType.WORD));

            postJson(wordRequest("Häusern", "Die Kinder spielen zwischen den Häusern."))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.translation").value("evler"))
                    .andExpect(jsonPath("$.lemma").value("Haus"))
                    .andExpect(jsonPath("$.grammar.kind").value("NOUN"))
                    .andExpect(jsonPath("$.grammar.article").value("das"))
                    .andExpect(jsonPath("$.grammar.grammaticalCase").value("DATIVE"));
        }

        @Test
        void sentenceRequestDoesNotNeedContext() throws Exception {
            given(translationService.translate(any())).willReturn(new TranslationResponse(
                    "Ich lese.", "I am reading.", null, null, null, "I am reading.",
                    SourceLanguage.DE, TargetLanguage.EN, TranslationType.SENTENCE));

            postJson("""
                    {"selectedText": "Ich lese.", "sourceLanguage": "DE",
                     "targetLanguage": "EN", "translationType": "SENTENCE"}
                    """)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.translation").value("I am reading."))
                    .andExpect(jsonPath("$.grammar").doesNotExist());
        }

        @Test
        void wordMatchingIgnoresWhitespaceDifferences() throws Exception {
            postJson(wordRequest("zum Beispiel", "Das ist zum   Beispiel gut."))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    class InvalidRequests {

        private void expectFieldError(ResultActions result, String field) throws Exception {
            result.andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.errors[0].field").value(field));
            verifyNoInteractions(translationService);
        }

        @Test
        void blankSelectedText() throws Exception {
            expectFieldError(postJson(wordRequest("  ", "Die Kinder spielen.")), "selectedText");
        }

        @Test
        void wordWithoutContext() throws Exception {
            expectFieldError(postJson(wordRequest("Häusern", null)), "context");
        }

        @Test
        void wordNotFoundInContext() throws Exception {
            expectFieldError(postJson(wordRequest("Katze", "Die Kinder spielen.")), "selectedText");
        }

        @Test
        void wordTooLong() throws Exception {
            String longWord = "a".repeat(TranslationRequest.MAX_WORD_LENGTH + 1);
            expectFieldError(postJson(wordRequest(longWord, longWord)), "selectedText");
        }

        @Test
        void sentenceTooLong() throws Exception {
            String longSentence = "a".repeat(TranslationRequest.MAX_SENTENCE_LENGTH + 1);
            expectFieldError(postJson("""
                    {"selectedText": "%s", "sourceLanguage": "DE",
                     "targetLanguage": "EN", "translationType": "SENTENCE"}
                    """.formatted(longSentence)), "selectedText");
        }

        @Test
        void unsupportedTargetLanguageListsAllowedValues() throws Exception {
            postJson("""
                    {"selectedText": "Haus", "context": "Das Haus.", "sourceLanguage": "DE",
                     "targetLanguage": "FR", "translationType": "WORD"}
                    """)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.errors[0].field").value("targetLanguage"))
                    .andExpect(jsonPath("$.errors[0].message").value("must be one of [TR, EN]"));
        }

        @Test
        void unsupportedSourceLanguage() throws Exception {
            expectFieldError(postJson("""
                    {"selectedText": "house", "context": "The house.", "sourceLanguage": "EN",
                     "targetLanguage": "TR", "translationType": "WORD"}
                    """), "sourceLanguage");
        }

        @Test
        void unsupportedTranslationType() throws Exception {
            expectFieldError(postJson("""
                    {"selectedText": "Haus", "context": "Das Haus.", "sourceLanguage": "DE",
                     "targetLanguage": "TR", "translationType": "PARAGRAPH"}
                    """), "translationType");
        }

        @Test
        void missingTargetLanguage() throws Exception {
            expectFieldError(postJson("""
                    {"selectedText": "Haus", "context": "Das Haus.", "sourceLanguage": "DE",
                     "translationType": "WORD"}
                    """), "targetLanguage");
        }

        @Test
        void malformedJson() throws Exception {
            postJson("{ not json")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }

        @Test
        void wrongContentTypeStillGetsAnErrorCode() throws Exception {
            mockMvc.perform(post(URL).contentType(MediaType.TEXT_PLAIN).content("Haus"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.code").value("REQUEST_ERROR"));
        }
    }

    @Nested
    class ServiceErrors {

        private static final String VALID = wordRequest("Haus", "Das Haus ist alt.");

        @Test
        void translationExceptionMapsToStatusAndSafeMessage() throws Exception {
            given(translationService.translate(any())).willThrow(new TranslationException(
                    ErrorCode.PROVIDER_TIMEOUT, "Gemini call to models/x timed out after 15s"));

            postJson(VALID)
                    .andExpect(status().isGatewayTimeout())
                    .andExpect(jsonPath("$.code").value("PROVIDER_TIMEOUT"))
                    .andExpect(jsonPath("$.detail").value(ErrorCode.PROVIDER_TIMEOUT.message()))
                    .andExpect(content().string(not(containsString("Gemini"))));
        }

        @ParameterizedTest
        @EnumSource(value = ErrorCode.class,
                names = {"PROVIDER_MODEL_UNAVAILABLE", "PROVIDER_REJECTED"})
        void configurationProblemsAre503WithTheirOwnCodeAndNoProviderDetails(ErrorCode code) throws Exception {
            given(translationService.translate(any())).willThrow(new TranslationException(
                    code, "Gemini answered HTTP 404 NOT_FOUND", new IllegalStateException("models/x is no longer available")));

            postJson(VALID)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value(code.name()))
                    .andExpect(jsonPath("$.detail").value(code.message()))
                    .andExpect(content().string(not(containsString("Gemini"))))
                    .andExpect(content().string(not(containsString("no longer available"))));
        }

        @Test
        void unexpectedExceptionDoesNotLeakDetails() throws Exception {
            given(translationService.translate(any())).willThrow(new IllegalStateException("secret internals"));

            postJson(VALID)
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(content().string(not(containsString("secret internals"))));
        }
    }

    @Nested
    class Cors {

        private ResultActions preflightFrom(String origin) throws Exception {
            return mockMvc.perform(options(URL)
                    .header(HttpHeaders.ORIGIN, origin)
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type"));
        }

        @Test
        void allowsConfiguredOrigin() throws Exception {
            preflightFrom("http://localhost:5173")
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
        }

        @Test
        void rejectsOtherOrigins() throws Exception {
            preflightFrom("https://evil.example.com")
                    .andExpect(status().isForbidden());
        }
    }
}
