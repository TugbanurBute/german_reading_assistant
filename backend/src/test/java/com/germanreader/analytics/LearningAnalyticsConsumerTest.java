package com.germanreader.analytics;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

class LearningAnalyticsConsumerTest {

    private final AnalyticsService analytics = mock(AnalyticsService.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final LearningAnalyticsConsumer consumer = new LearningAnalyticsConsumer(analytics, json);

    private final VocabularyWordSaved event = new VocabularyWordSaved(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), Instant.parse("2026-11-09T12:00:00Z"));

    @Test
    void countsAValidEvent() {
        given(analytics.record(event)).willReturn(true);

        consumer.onEvent(json.writeValueAsString(event));

        verify(analytics).record(event);
    }

    @Test
    void anUnreadablePayloadFailsWithoutTouchingTheDatabase() {
        assertThatThrownBy(() -> consumer.onEvent("not json")).isInstanceOf(JacksonException.class);
        verifyNoInteractions(analytics);
    }

    @Test
    void anEventOfAnotherTypeOrWithoutIdsIsRejected() {
        String otherType = json.writeValueAsString(new VocabularyWordSaved(UUID.randomUUID(), "SomethingElse",
                UUID.randomUUID(), UUID.randomUUID(), Instant.now()));
        String noUser = json.writeValueAsString(new VocabularyWordSaved(UUID.randomUUID(), UUID.randomUUID(), null, Instant.now()));

        assertThatThrownBy(() -> consumer.onEvent(otherType)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consumer.onEvent(noUser)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(analytics);
    }

    @Test
    void aDatabaseFailurePropagatesSoTheOffsetIsNotCommitted() {
        given(analytics.record(event)).willThrow(new DataAccessResourceFailureException("database down"));

        assertThatThrownBy(() -> consumer.onEvent(json.writeValueAsString(event)))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
