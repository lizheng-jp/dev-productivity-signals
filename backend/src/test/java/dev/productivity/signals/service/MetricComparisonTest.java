package dev.productivity.signals.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricComparisonTest {

    @Test
    void normalizesCountsWhenPeriodsHaveDifferentLengths() {
        MetricComparison.Periods periods = MetricComparison.periods("2026-09-01", "2026-09-14", 7);

        var trends = MetricComparison.compare(
                Map.of("commitCount", 28, "mergedLeadTimeHours", 12.0, "mergedCount", 4),
                Map.of("commitCount", 7, "mergedLeadTimeHours", 18.0, "mergedCount", 2),
                periods);

        assertThat(trends.get("commitCount").dailyNormalized()).isTrue();
        assertThat(trends.get("commitCount").percentChange()).isEqualTo(100.0);
        assertThat(trends.get("mergedLeadTimeHours").lowerIsBetter()).isTrue();
    }
}
