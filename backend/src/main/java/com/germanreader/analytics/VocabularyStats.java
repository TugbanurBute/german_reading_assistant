package com.germanreader.analytics;

import java.time.LocalDate;
import java.util.List;

public record VocabularyStats(String timeZone, int today, int lastSevenDays, List<Day> days) {

    public record Day(LocalDate date, int count) {
    }
}
