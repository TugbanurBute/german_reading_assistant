package com.germanreader.config;

import com.germanreader.service.translation.TranslationProvider;
import com.germanreader.service.translation.gemini.GeminiProperties;
import com.germanreader.service.translation.gemini.GeminiTranslationProvider;
import com.germanreader.service.translation.mock.MockTranslationProvider;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TranslationProperties.class)
public class TranslationProviderConfig {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "translation.provider", havingValue = "mock")
    static class MockProviderConfig {

        @Bean
        TranslationProvider mockTranslationProvider(JsonMapper jsonMapper) {
            return new MockTranslationProvider(jsonMapper);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "translation.provider", havingValue = "gemini")
    @EnableConfigurationProperties(GeminiProperties.class)
    static class GeminiProviderConfig {

        @Bean(destroyMethod = "close")
        Client geminiClient(GeminiProperties properties) {
            HttpRetryOptions retries = HttpRetryOptions.builder()
                    .attempts(properties.maxAttempts())
                    .httpStatusCodes(500, 502, 503, 504)
                    .build();
            HttpOptions httpOptions = HttpOptions.builder()
                    .timeout((int) properties.timeout().toMillis())
                    .retryOptions(retries)
                    .build();
            return Client.builder()
                    .apiKey(properties.apiKey())
                    .httpOptions(httpOptions)
                    .build();
        }

        @Bean
        TranslationProvider geminiTranslationProvider(Client geminiClient, GeminiProperties properties,
                                                      JsonMapper jsonMapper) {
            return new GeminiTranslationProvider(geminiClient.models, properties.model(), jsonMapper);
        }
    }
}
