package com.germanreader.analytics;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
public class LearningAnalyticsConsumer {

    private final AnalyticsService analytics;
    private final JsonMapper jsonMapper;

    public LearningAnalyticsConsumer(AnalyticsService analytics, JsonMapper jsonMapper) {
        this.analytics = analytics;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(topics = AnalyticsConfig.TOPIC, groupId = AnalyticsConfig.CONSUMER_GROUP,
            autoStartup = "${app.vocabulary-events.enabled:true}")
    public void onEvent(String payload) {
        VocabularyWordSaved event = jsonMapper.readValue(payload, VocabularyWordSaved.class);
        if (!VocabularyWordSaved.TYPE.equals(event.eventType()) || event.eventId() == null || event.userId() == null
                || event.occurredAt() == null) {
            throw new IllegalArgumentException("Not a valid " + VocabularyWordSaved.TYPE + " event");
        }
        if (!analytics.record(event)) {
            log.debug("Event {} was already counted", event.eventId());
        }
    }
}
