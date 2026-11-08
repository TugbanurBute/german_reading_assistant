package com.germanreader.analytics;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyticsService {

    static final int DAYS = 7;

    private final JdbcTemplate jdbc;
    private final ZoneId zone;
    private final Clock clock;

    public AnalyticsService(JdbcTemplate jdbc, ZoneId analyticsZone, Clock analyticsClock) {
        this.jdbc = jdbc;
        this.zone = analyticsZone;
        this.clock = analyticsClock;
    }

    @Transactional
    public boolean record(VocabularyWordSaved event) {
        int inserted = jdbc.update("""
                INSERT INTO processed_vocabulary_event (event_id, processed_at) VALUES (?, now())
                ON CONFLICT (event_id) DO NOTHING
                """, event.eventId());
        if (inserted == 0) {
            return false;
        }
        jdbc.update("""
                INSERT INTO vocabulary_daily_stats (user_id, day, saved_count) VALUES (?, ?, 1)
                ON CONFLICT (user_id, day) DO UPDATE SET saved_count = vocabulary_daily_stats.saved_count + 1
                """, event.userId(), Date.valueOf(event.occurredAt().atZone(zone).toLocalDate()));
        return true;
    }

    @Transactional(readOnly = true)
    public VocabularyStats summary(UUID userId) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate first = today.minusDays(DAYS - 1);
        Map<LocalDate, Integer> counts = new HashMap<>();
        jdbc.query("SELECT day, saved_count FROM vocabulary_daily_stats WHERE user_id = ? AND day BETWEEN ? AND ?",
                row -> {
                    counts.put(row.getDate(1).toLocalDate(), row.getInt(2));
                }, userId, Date.valueOf(first), Date.valueOf(today));
        List<VocabularyStats.Day> days = IntStream.range(0, DAYS)
                .mapToObj(i -> first.plusDays(i))
                .map(day -> new VocabularyStats.Day(day, counts.getOrDefault(day, 0)))
                .toList();
        int lastSevenDays = days.stream().mapToInt(VocabularyStats.Day::count).sum();
        return new VocabularyStats(zone.getId(), counts.getOrDefault(today, 0), lastSevenDays, days);
    }
}
