package com.germanreader.analytics;

import java.time.Clock;
import java.time.ZoneId;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

@Configuration(proxyBeanMethods = false)
public class AnalyticsConfig {

    public static final String TOPIC = "vocabulary.events";
    public static final String DEAD_LETTER_TOPIC = "vocabulary.events.dlt";
    public static final String CONSUMER_GROUP = "learning-analytics";

    static final int MAX_ATTEMPTS = 3;

    @Bean
    NewTopic vocabularyEventsTopic() {
        return TopicBuilder.name(TOPIC).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic vocabularyEventsDeadLetterTopic() {
        return TopicBuilder.name(DEAD_LETTER_TOPIC).partitions(1).replicas(1).build();
    }

    @Bean
    DefaultErrorHandler analyticsErrorHandler(KafkaTemplate<Object, Object> kafkaTemplate) {
        return errorHandler(kafkaTemplate, 1000);
    }

    static DefaultErrorHandler errorHandler(KafkaTemplate<?, ?> kafkaTemplate, long backOffMs) {
        DeadLetterPublishingRecoverer deadLetter = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(DEAD_LETTER_TOPIC, -1));
        DefaultErrorHandler handler = new DefaultErrorHandler(deadLetter, new FixedBackOff(backOffMs, MAX_ATTEMPTS - 1));
        handler.addNotRetryableExceptions(JacksonException.class, IllegalArgumentException.class);
        return handler;
    }

    @Bean
    ZoneId analyticsZone(@Value("${app.analytics.time-zone:UTC}") String zone) {
        return ZoneId.of(zone);
    }

    @Bean
    Clock analyticsClock() {
        return Clock.systemUTC();
    }
}
