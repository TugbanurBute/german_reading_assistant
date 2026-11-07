package com.germanreader.service.translation.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.google.genai.Models;
import com.google.genai.errors.ClientException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.errors.ServerException;
import com.google.genai.types.Model;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class GeminiModelCheckTest {

    private static final String MODEL = "test-model";
    private final Models models = mock(Models.class);

    @Test
    void anExistingModelIsAvailable() {
        given(models.get(eq(MODEL), any())).willReturn(Model.builder().name("models/" + MODEL).build());

        assertThat(GeminiModelCheck.verify(models, MODEL)).isEqualTo(GeminiModelCheck.Result.AVAILABLE);
    }

    @Test
    void aRetiredModelIsReported() {
        given(models.get(eq(MODEL), any())).willThrow(new ClientException(404, "NOT_FOUND", "no longer available"));

        assertThat(GeminiModelCheck.verify(models, MODEL)).isEqualTo(GeminiModelCheck.Result.MODEL_NOT_FOUND);
    }

    @Test
    void aRejectedKeyIsReported() {
        given(models.get(eq(MODEL), any())).willThrow(new ClientException(400, "INVALID_ARGUMENT", "API key not valid"));

        assertThat(GeminiModelCheck.verify(models, MODEL)).isEqualTo(GeminiModelCheck.Result.REJECTED);
    }

    @Test
    void outagesAndNetworkErrorsDoNotStopTheApplication() {
        given(models.get(eq(MODEL), any()))
                .willThrow(new ServerException(503, "UNAVAILABLE", "overloaded"))
                .willThrow(new GenAiIOException("Failed to execute HTTP request", new IOException("Connection refused")));

        assertThat(GeminiModelCheck.verify(models, MODEL)).isEqualTo(GeminiModelCheck.Result.NOT_VERIFIED);
        assertThat(GeminiModelCheck.verify(models, MODEL)).isEqualTo(GeminiModelCheck.Result.NOT_VERIFIED);
    }
}
