package com.example.germanreader.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/**
 * Starts a tiny Spring context (no web server, no database) to check how configuration binds.
 * The same technique is used later to test TranslationProvider selection.
 */
class CorsPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CorsProperties.class)
    static class TestConfig {
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    void bindsCommaSeparatedOriginsToList() {
        contextRunner
                .withPropertyValues("app.cors.allowed-origins=http://localhost:5173,https://reader.example.com")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CorsProperties.class).allowedOrigins())
                            .containsExactly("http://localhost:5173", "https://reader.example.com");
                });
    }

    @Test
    void failsStartupWhenOriginsAreMissing() {
        contextRunner.run(context -> assertThat(context).hasFailed());
    }
}
