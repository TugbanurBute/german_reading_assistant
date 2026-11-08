package com.germanreader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.germanreader.analytics.AnalyticsConfig;
import com.germanreader.analytics.AnalyticsService;
import com.germanreader.analytics.LearningAnalyticsConsumer;
import com.germanreader.analytics.VocabularyEventRelay;
import com.germanreader.analytics.VocabularyStats;
import com.germanreader.analytics.VocabularyWordSaved;
import com.germanreader.dto.TargetLanguage;
import com.germanreader.entity.VocabularyEntry;
import com.germanreader.repository.VocabularyEntryRepository;
import com.germanreader.security.TestAuth;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "translation.provider=mock",
        "spring.flyway.schemas=" + TranslationCacheIT.SCHEMA,
        "spring.datasource.hikari.schema=" + TranslationCacheIT.SCHEMA
})
@ActiveProfiles("local")
@AutoConfigureMockMvc
@Import(TestAuth.AsDefaultUser.class)
class AnalyticsIT {

    private static final UUID OTHER_USER = UUID.fromString("2b3c4d5e-6f70-4a8b-9c0d-1e2f3a4b5c6d");
    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private VocabularyEventRelay relay;

    @Autowired
    private AnalyticsService analytics;

    @Autowired
    private LearningAnalyticsConsumer consumer;

    @Autowired
    private VocabularyEntryRepository vocabulary;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoBean
    private KafkaTemplate<String, String> kafka;

    @BeforeEach
    void cleanState() {
        TestAuth.ensureUser(jdbc, TestAuth.USER_ID);
        TestAuth.ensureUser(jdbc, OTHER_USER);
        jdbc.update("DELETE FROM vocabulary_entry WHERE owner_id IN (?, ?)", TestAuth.USER_ID, OTHER_USER);
        jdbc.update("DELETE FROM vocabulary_daily_stats WHERE user_id IN (?, ?)", TestAuth.USER_ID, OTHER_USER);
        jdbc.update("UPDATE vocabulary_entry SET event_published_at = now() WHERE event_published_at IS NULL");
    }

    private void kafkaAccepts() {
        given(kafka.send(anyString(), anyString(), anyString()))
                .willReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
    }

    private int saveWord(String translation) throws Exception {
        return mockMvc.perform(post("/api/v1/vocabulary").contentType(MediaType.APPLICATION_JSON).content("""
                        {"originalText": "Bank", "lemma": "Bank", "translation": "%s", "targetLanguage": "EN",
                         "sourceSentence": "Am Morgen sitzt meine Großmutter auf der Bank im Park."}
                        """.formatted(translation)))
                .andReturn().getResponse().getStatus();
    }

    private static VocabularyWordSaved event(UUID user, Instant at) {
        return new VocabularyWordSaved(UUID.randomUUID(), UUID.randomUUID(), user, at);
    }

    private AnalyticsService analyticsAt(Instant now, String zone) {
        return new AnalyticsService(jdbc, ZoneId.of(zone), Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void aNewWordPublishesOneEventKeyedByTheUserWithIdsOnly() throws Exception {
        assertThat(saveWord("bench")).isEqualTo(201);
        verifyNoInteractions(kafka); // nothing is published inside the request transaction
        kafkaAccepts();

        assertThat(relay.publishPending()).isEqualTo(1);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq(AnalyticsConfig.TOPIC), eq(TestAuth.USER_ID.toString()), payload.capture());
        JsonNode event = jsonMapper.readTree(payload.getValue());
        VocabularyEntry entry = vocabulary.findAll().stream()
                .filter(e -> TestAuth.USER_ID.equals(e.getOwnerId())).findFirst().orElseThrow();
        assertThat(event.get("eventType").asString()).isEqualTo("VocabularyWordSaved");
        assertThat(event.get("eventId").asString()).isEqualTo(entry.getSavedEventId().toString());
        assertThat(event.get("entryId").asString()).isEqualTo(entry.getId().toString());
        assertThat(event.get("userId").asString()).isEqualTo(TestAuth.USER_ID.toString());
        assertThat(event.has("occurredAt")).isTrue();
        assertThat(payload.getValue()).doesNotContain("Bank").doesNotContain("Großmutter").doesNotContain("bench");
        assertThat(relay.publishPending()).isZero();
    }

    @Test
    void returningAnExistingDuplicatePublishesNothing() throws Exception {
        assertThat(saveWord("bench")).isEqualTo(201);
        kafkaAccepts();
        assertThat(relay.publishPending()).isEqualTo(1);

        assertThat(saveWord("Bench")).isEqualTo(200);

        assertThat(relay.publishPending()).isZero();
    }

    @Test
    void aRolledBackSaveLeavesNoEvent() {
        kafkaAccepts();
        transaction.executeWithoutResult(status -> {
            vocabulary.saveAndFlush(new VocabularyEntry(TestAuth.USER_ID, "Bank", "Bank", null, null, "bench",
                    TargetLanguage.EN, null, null));
            status.setRollbackOnly();
        });

        assertThat(relay.publishPending()).isZero();
        verifyNoInteractions(kafka);
    }

    @Test
    void whenKafkaFailsTheEventWaitsAndIsPublishedLater() throws Exception {
        saveWord("bench");
        given(kafka.send(anyString(), anyString(), anyString()))
                .willReturn(CompletableFuture.failedFuture(new org.apache.kafka.common.errors.TimeoutException("down")));

        assertThat(relay.publishPending()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vocabulary_entry WHERE owner_id = ? AND event_published_at IS NULL",
                Integer.class, TestAuth.USER_ID)).isEqualTo(1);

        kafkaAccepts();
        assertThat(relay.publishPending()).isEqualTo(1);
    }

    @Test
    void aDuplicateOrReplayedEventIsCountedOnce() {
        VocabularyWordSaved event = event(TestAuth.USER_ID, NOW);

        assertThat(analytics.record(event)).isTrue();
        assertThat(analytics.record(event)).isFalse();
        assertThat(analytics.record(event)).isFalse(); // e.g. after a consumer restart re-reads uncommitted offsets

        assertThat(analyticsAt(NOW, "UTC").summary(TestAuth.USER_ID).today()).isEqualTo(1);
    }

    @Test
    void theConsumerCountsTheSameEventIdOnlyOnce() {
        VocabularyWordSaved event = event(TestAuth.USER_ID, NOW);
        String payload = jsonMapper.writeValueAsString(event);

        consumer.onEvent(payload);
        assertThatCode(() -> consumer.onEvent(payload)).doesNotThrowAnyException();

        assertThat(jdbc.queryForObject(
                "SELECT saved_count FROM vocabulary_daily_stats WHERE user_id = ? AND day = ?",
                Integer.class, TestAuth.USER_ID, java.sql.Date.valueOf(LocalDate.parse("2026-11-09")))).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM processed_vocabulary_event WHERE event_id = ?",
                Integer.class, event.eventId())).isEqualTo(1);
    }

    @Test
    void aFailedDatabaseWriteStoresNothingSoTheEventCanBeRetried() {
        VocabularyWordSaved forUnknownUser = event(UUID.randomUUID(), NOW); // the count row's foreign key fails

        assertThatThrownBy(() -> analytics.record(forUnknownUser)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_vocabulary_event WHERE event_id = ?",
                Integer.class, forUnknownUser.eventId())).isZero();
    }

    @Test
    void summarisesTodayAndTheLastSevenDaysWithEmptyDaysAsZero() {
        analytics.record(event(TestAuth.USER_ID, NOW));
        analytics.record(event(TestAuth.USER_ID, NOW.minusSeconds(3600)));
        analytics.record(event(TestAuth.USER_ID, NOW.minus(java.time.Duration.ofDays(2))));
        analytics.record(event(TestAuth.USER_ID, NOW.minus(java.time.Duration.ofDays(6))));
        analytics.record(event(TestAuth.USER_ID, NOW.minus(java.time.Duration.ofDays(7)))); // outside the window

        VocabularyStats stats = analyticsAt(NOW, "UTC").summary(TestAuth.USER_ID);

        assertThat(stats.timeZone()).isEqualTo("UTC");
        assertThat(stats.today()).isEqualTo(2);
        assertThat(stats.lastSevenDays()).isEqualTo(4);
        assertThat(stats.days()).extracting(VocabularyStats.Day::count).containsExactly(1, 0, 0, 0, 1, 0, 2);
        assertThat(stats.days().getFirst().date()).isEqualTo(LocalDate.parse("2026-11-03"));
        assertThat(stats.days().getLast().date()).isEqualTo(LocalDate.parse("2026-11-09"));
    }

    @Test
    void theDayDependsOnTheConfiguredTimeZone() {
        Instant lateEveningUtc = Instant.parse("2026-11-09T22:30:00Z"); // already 10 November in Istanbul (UTC+3)
        AnalyticsService istanbul = analyticsAt(lateEveningUtc, "Europe/Istanbul");

        istanbul.record(event(TestAuth.USER_ID, lateEveningUtc));

        assertThat(jdbc.queryForObject("SELECT day FROM vocabulary_daily_stats WHERE user_id = ?",
                java.sql.Date.class, TestAuth.USER_ID).toLocalDate()).isEqualTo(LocalDate.parse("2026-11-10"));
        assertThat(istanbul.summary(TestAuth.USER_ID).today()).isEqualTo(1);
        assertThat(analyticsAt(lateEveningUtc, "UTC").summary(TestAuth.USER_ID).today()).isZero();
    }

    @Test
    void theEndpointReturnsOnlyTheLoggedInUsersStatistics() throws Exception {
        Instant now = Instant.now();
        analytics.record(event(TestAuth.USER_ID, now));
        analytics.record(event(OTHER_USER, now));
        analytics.record(event(OTHER_USER, now));

        mockMvc.perform(get("/api/v1/analytics/vocabulary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.today").value(1))
                .andExpect(jsonPath("$.lastSevenDays").value(1))
                .andExpect(jsonPath("$.days.length()").value(7))
                .andExpect(jsonPath("$.days[6].count").value(1))
                .andExpect(jsonPath("$.timeZone").value("UTC"));
        assertThat(analytics.summary(OTHER_USER).today()).isEqualTo(2);
    }

    @Test
    void aUserWithoutActivityGetsSevenEmptyDays() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/vocabulary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.today").value(0))
                .andExpect(jsonPath("$.lastSevenDays").value(0))
                .andExpect(jsonPath("$.days[*].count").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(0))));
    }

    @Test
    void eventsAreListedOnlyOnceEvenWithManyPending() throws Exception {
        kafkaAccepts();
        for (String translation : List.of("bench", "bank", "shore")) {
            saveWord(translation);
        }

        assertThat(relay.publishPending()).isEqualTo(3);
        assertThat(relay.publishPending()).isZero();
    }
}
