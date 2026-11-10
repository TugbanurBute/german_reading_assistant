package com.germanreader.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.germanreader.library.BuiltinCatalog.BuiltinText;
import com.germanreader.processing.DocumentStats;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class BuiltinCatalogTest {

    private final List<BuiltinText> texts = BuiltinCatalog.load(JsonMapper.builder().build());

    @Test
    void hasFiveTextsPerLevelWithUniqueKeys() {
        Map<CefrLevel, Long> perLevel = texts.stream().collect(Collectors.groupingBy(BuiltinText::level, Collectors.counting()));

        assertThat(texts).hasSize(15);
        assertThat(perLevel).containsEntry(CefrLevel.A1, 5L).containsEntry(CefrLevel.A2, 5L).containsEntry(CefrLevel.B1, 5L);
        assertThat(texts).extracting(BuiltinText::key).doesNotHaveDuplicates();
    }

    @Test
    void lengthsFitTheLevels() {
        Map<CefrLevel, int[]> range = Map.of(CefrLevel.A1, new int[] {150, 250}, CefrLevel.A2, new int[] {250, 400},
                CefrLevel.B1, new int[] {400, 600});
        for (BuiltinText text : texts) {
            DocumentStats stats = DocumentStats.of(text.content());
            int[] words = range.get(text.level());
            assertThat(stats.words()).as(text.key()).isBetween(words[0], words[1]);
            assertThat(stats.paragraphs()).as(text.key()).isGreaterThanOrEqualTo(3);
        }
    }

    @Test
    void textsAreCleanAndDescribed() {
        for (BuiltinText text : texts) {
            assertThat(text.content()).as(text.key()).doesNotContain("\r").doesNotStartWith(" ").doesNotEndWith("\n");
            assertThat(text.title()).as(text.key()).isNotBlank();
            assertThat(text.description()).as(text.key()).isNotBlank().hasSizeLessThanOrEqualTo(300);
        }
    }

    @Test
    void readingTimeDependsOnTheLevel() {
        assertThat(CefrLevel.A1.readingMinutes(200)).isEqualTo(3);
        assertThat(CefrLevel.B1.readingMinutes(480)).isEqualTo(5);
        assertThat(CefrLevel.B1.readingMinutes(10)).isEqualTo(1);
    }
}
