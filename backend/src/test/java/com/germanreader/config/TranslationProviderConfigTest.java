package com.germanreader.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.germanreader.service.translation.TranslationProvider;
import com.germanreader.service.translation.gemini.GeminiProperties;
import com.germanreader.service.translation.gemini.GeminiTranslationProvider;
import com.germanreader.service.translation.mock.MockTranslationProvider;
import com.google.genai.Client;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

/**
 * Verifies which beans exist for each {@code translation.provider} value, using a minimal context
 * (Jackson + validation + our provider config). Creating a Gemini {@link Client} makes no network call.
 */
class TranslationProviderConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, ValidationAutoConfiguration.class))
            .withUserConfiguration(TranslationProviderConfig.class);

    @Test
    void mockModeCreatesOnlyTheMockProviderAndNoGeminiBeans() {
        contextRunner
                .withPropertyValues("translation.provider=mock")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBean(TranslationProvider.class).isInstanceOf(MockTranslationProvider.class);
                    // Nothing that could reach Gemini exists in mock mode.
                    assertThat(context).doesNotHaveBean(Client.class);
                    assertThat(context).doesNotHaveBean(GeminiProperties.class);
                    assertThat(context).doesNotHaveBean(GeminiTranslationProvider.class);
                });
    }

    @Test
    void mockModeIgnoresMissingGeminiConfiguration() {
        contextRunner
                .withPropertyValues("translation.provider=mock", "translation.gemini.api-key=", "translation.gemini.model=")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void geminiModeCreatesGeminiProvider() {
        contextRunner
                .withPropertyValues("translation.provider=gemini",
                        "translation.gemini.api-key=test-key", "translation.gemini.model=test-model")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBean(TranslationProvider.class).isInstanceOf(GeminiTranslationProvider.class);
                    assertThat(context.getBean(TranslationProvider.class).cacheNamespace()).isEqualTo("gemini:test-model");
                    assertThat(context.getBean(GeminiProperties.class).timeout()).hasSeconds(15);
                    assertThat(context).doesNotHaveBean(MockTranslationProvider.class);
                });
    }

    @Test
    void geminiModeFailsAtStartupWithoutApiKey() {
        contextRunner
                .withPropertyValues("translation.provider=gemini", "translation.gemini.model=test-model")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("apiKey");
                });
    }

    @Test
    void geminiModeFailsAtStartupWithoutModel() {
        contextRunner
                .withPropertyValues("translation.provider=gemini", "translation.gemini.api-key=test-key")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("model");
                });
    }

    @Test
    void geminiPropertiesNeverPrintTheApiKey() {
        contextRunner
                .withPropertyValues("translation.provider=gemini",
                        "translation.gemini.api-key=super-secret", "translation.gemini.model=test-model")
                .run(context -> assertThat(context.getBean(GeminiProperties.class).toString())
                        .doesNotContain("super-secret"));
    }

    @Test
    void geminiModeFailsAtStartupForAMalformedModelName() {
        contextRunner
                .withPropertyValues("translation.provider=gemini",
                        "translation.gemini.api-key=test-key", "translation.gemini.model=gemini 3.5 flash")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("model");
                });
    }

    @Test
    void geminiModeAllowsAtMostOneRetry() {
        contextRunner
                .withPropertyValues("translation.provider=gemini", "translation.gemini.api-key=test-key",
                        "translation.gemini.model=test-model", "translation.gemini.max-attempts=3")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void pastedSpacesAndQuotesAroundTheKeyAndModelAreRemoved() {
        contextRunner
                .withPropertyValues("translation.provider=gemini",
                        "translation.gemini.api-key= \"test-key\" ", "translation.gemini.model='models/test-model'")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GeminiProperties properties = context.getBean(GeminiProperties.class);
                    assertThat(properties.apiKey()).isEqualTo("test-key");
                    assertThat(properties.model()).isEqualTo("models/test-model");
                });
    }

    @Test
    void theRemoteModelCheckIsOffByDefault() {
        contextRunner
                .withPropertyValues("translation.provider=gemini",
                        "translation.gemini.api-key=test-key", "translation.gemini.model=test-model")
                .run(context -> {
                    assertThat(context.getBean(GeminiProperties.class).verifyModel()).isFalse();
                    assertThat(context).doesNotHaveBean(ApplicationRunner.class);
                });
    }

    @Test
    void theRemoteModelCheckCanBeSwitchedOn() {
        // The context runner never starts ApplicationRunners, so this makes no call to Google.
        contextRunner
                .withPropertyValues("translation.provider=gemini", "translation.gemini.api-key=test-key",
                        "translation.gemini.model=test-model", "translation.gemini.verify-model=true")
                .run(context -> assertThat(context).hasSingleBean(ApplicationRunner.class));
    }

    @Test
    void failsAtStartupWhenProviderIsMissing() {
        contextRunner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsAtStartupForUnknownProvider() {
        contextRunner
                .withPropertyValues("translation.provider=deepl")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("deepl");
                });
    }
}
