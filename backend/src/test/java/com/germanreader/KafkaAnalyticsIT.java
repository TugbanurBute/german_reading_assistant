package com.germanreader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.germanreader.analytics.AnalyticsConfig;
import com.germanreader.analytics.AnalyticsService;
import com.germanreader.analytics.VocabularyWordSaved;
import com.germanreader.security.TestAuth;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@EmbeddedKafka
@SpringBootTest(properties = {
        "translation.provider=mock",
        "spring.flyway.schemas=" + TranslationCacheIT.SCHEMA,
        "spring.datasource.hikari.schema=" + TranslationCacheIT.SCHEMA,
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.admin.auto-create=true",
        "app.vocabulary-events.enabled=true",
        "app.vocabulary-events.relay-interval=PT0.2S"
})
@ActiveProfiles("local")
@AutoConfigureMockMvc
@Import(TestAuth.AsDefaultUser.class)
class KafkaAnalyticsIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AnalyticsService analytics;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private JsonMapper jsonMapper;

    private UUID user;

    @BeforeEach
    void freshUser() {
        TestAuth.ensureUser(jdbc, TestAuth.USER_ID);
        user = UUID.randomUUID();
        TestAuth.ensureUser(jdbc, user);
        jdbc.update("UPDATE vocabulary_entry SET event_published_at = now() WHERE event_published_at IS NULL");
    }

    private void publish(VocabularyWordSaved event) {
        kafka.send(AnalyticsConfig.TOPIC, event.userId().toString(), jsonMapper.writeValueAsString(event)).join();
    }

    private boolean processed(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM processed_vocabulary_event WHERE event_id = ?",
                Integer.class, eventId) == 1;
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting until " + what);
    }

    @Test
    void savingAWordReachesTheStatisticsThroughKafka() throws Exception {
        int before = analytics.summary(TestAuth.USER_ID).today();

        mockMvc.perform(post("/api/v1/vocabulary").contentType(MediaType.APPLICATION_JSON).content("""
                        {"originalText": "Bank", "lemma": "Bank", "translation": "%s", "targetLanguage": "EN"}
                        """.formatted("bench-" + UUID.randomUUID())))
                .andExpect(status().isCreated());

        await("the saved word is counted", () -> analytics.summary(TestAuth.USER_ID).today() == before + 1);
    }

    @Test
    void anEventDeliveredTwiceIsCountedOnce() throws Exception {
        VocabularyWordSaved event = new VocabularyWordSaved(UUID.randomUUID(), UUID.randomUUID(), user, Instant.now());
        VocabularyWordSaved marker = new VocabularyWordSaved(UUID.randomUUID(), UUID.randomUUID(), user, Instant.now());

        publish(event);
        publish(event);
        publish(marker);

        await("the marker is processed", () -> processed(marker.eventId()));
        assertThat(analytics.summary(user).today()).isEqualTo(2);
    }

    @Test
    void eventsPublishedWhileTheConsumerIsStoppedAreProcessedOnceAfterRestart() throws Exception {
        VocabularyWordSaved first = new VocabularyWordSaved(UUID.randomUUID(), UUID.randomUUID(), user, Instant.now());
        publish(first);
        await("the first event is processed", () -> processed(first.eventId()));

        listeners.stop();
        VocabularyWordSaved whileStopped = new VocabularyWordSaved(UUID.randomUUID(), UUID.randomUUID(), user, Instant.now());
        publish(whileStopped);
        publish(first);
        listeners.start();

        await("the event sent while stopped is processed", () -> processed(whileStopped.eventId()));
        Thread.sleep(500);
        assertThat(analytics.summary(user).today()).isEqualTo(2);
    }

    @Test
    void unreadableAndRepeatedlyFailingEventsEndInTheDeadLetterTopic() throws Exception {
        VocabularyWordSaved forUnknownUser =
                new VocabularyWordSaved(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now());
        String unreadable = "not an event " + UUID.randomUUID();

        kafka.send(AnalyticsConfig.TOPIC, user.toString(), unreadable).join();
        publish(forUnknownUser);

        List<String> deadLetters = readDeadLetters(2);
        assertThat(deadLetters).contains(unreadable, jsonMapper.writeValueAsString(forUnknownUser));
        assertThat(processed(forUnknownUser.eventId())).isFalse();

        VocabularyWordSaved next = new VocabularyWordSaved(UUID.randomUUID(), UUID.randomUUID(), user, Instant.now());
        publish(next);
        await("the next event is processed", () -> processed(next.eventId()));
    }

    private List<String> readDeadLetters(int expected) throws InterruptedException {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<String> values = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(AnalyticsConfig.DEAD_LETTER_TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (values.size() < expected && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    values.add(record.value());
                }
            }
        }
        return values;
    }
}
