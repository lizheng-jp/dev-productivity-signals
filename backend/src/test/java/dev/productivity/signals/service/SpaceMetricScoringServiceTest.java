package dev.productivity.signals.service;

import dev.productivity.signals.entity.MetricWeightManagement;
import dev.productivity.signals.repository.MetricWeightManagementRepository;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SpaceMetricScoringServiceTest {

    private final SpaceMetricScoringService service =
            new SpaceMetricScoringService(mock(MetricWeightManagementRepository.class));

    private final List<MetricWeightManagement> configs = List.of(
            dimension("performance"),
            dimension("activity"),
            dimension("efficiency"),
            metric("mergedCount", "performance", 0, 4, true),
            metric("bugCausedCount", "performance", 0, 2, false),
            metric("commitCount", "activity", 2, 12, true),
            metric("bugFoundCount", "activity", 0, 5, true),
            metric("mergedLeadTimeHours", "efficiency", 8, 48, false));

    @Test
    void scoresProjectTotalsPerActiveContributor() {
        // 40 merges in one week by 20 contributors is 2 per person: half of the 0-4 range.
        Map<String, Object> result = service.calculateScores(metrics(Map.of(
                "mergedCount", 40, "coreContributorCount", 20)), 1.0, configs);

        assertThat(result.get("mergedCountScore")).isEqualTo(50.0);
        assertThat(result.get("mergedCount")).isEqualTo(40);
    }

    @Test
    void withoutContributorCountScoresTotalsAsBefore() {
        Map<String, Object> result = service.calculateScores(metrics(Map.of("mergedCount", 40)), 1.0, configs);

        assertThat(result.get("mergedCountScore")).isEqualTo(100.0);
    }

    @Test
    void scoresLeadTimeByMedianWhenAvailable() {
        // An old pull request drags the average to 5000h; the median of 28h is mid-range.
        Map<String, Object> result = service.calculateScores(metrics(Map.of(
                "mergedCount", 3, "mergedLeadTimeHours", 5000.0, "mergedLeadTimeMedianHours", 28.0)), 1.0, configs);

        assertThat(result.get("mergedLeadTimeHoursScore")).isEqualTo(50.0);
        assertThat(result.get("mergedLeadTimeHours")).isEqualTo(5000.0);
    }

    @Test
    void leavesBugMetricsUnscoredWithoutBugData() {
        Map<String, Object> result = service.calculateScores(metrics(Map.of(
                "mergedCount", 2, "commitCount", 5, "bugCausedCount", 0, "bugFoundCount", 0, "bugDataAvailable", 0)), 1.0, configs);

        assertThat(result).doesNotContainKeys("bugCausedCountScore", "bugFoundCountScore");
        assertThat(result.get("performanceScore")).isEqualTo(result.get("mergedCountScore"));
    }

    @Test
    void scoresZeroBugsWhenTheProjectHasBugData() {
        Map<String, Object> result = service.calculateScores(metrics(Map.of(
                "mergedCount", 2, "commitCount", 5, "bugCausedCount", 0, "bugDataAvailable", 1)), 1.0, configs);

        assertThat(result.get("bugCausedCountScore")).isEqualTo(100.0);
    }

    @Test
    void medianLeadTimeCountsOnlyMergesInRangeByTheAuthor() throws Exception {
        List<JSONObject> merges = List.of(
                merge("alice", "2026-09-01T00:00:00Z", "2026-09-02T00:00:00Z"),
                merge("alice", "2026-09-01T00:00:00Z", "2026-09-03T00:00:00Z"),
                merge("alice", "2020-01-01T00:00:00Z", "2026-09-04T00:00:00Z"),
                merge("bob", "2026-09-01T00:00:00Z", "2026-09-01T06:00:00Z"),
                merge("alice", "2026-08-01T00:00:00Z", "2026-08-02T00:00:00Z"));
        ZonedDateTime start = ZonedDateTime.parse("2026-09-01T00:00:00Z");
        ZonedDateTime end = ZonedDateTime.parse("2026-09-30T23:59:59Z");

        assertThat(DeliveryTrendCalculator.medianLeadTimeHours(merges, start, end, "ALICE")).isEqualTo(48.0);
        assertThat(DeliveryTrendCalculator.medianLeadTimeHours(merges, start, end, null)).isEqualTo(36.0);
        assertThat(DeliveryTrendCalculator.medianLeadTimeHours(List.of(), start, end, null)).isNull();
    }

    private static Map<String, Number> metrics(Map<String, Number> values) {
        return new HashMap<>(values);
    }

    private static JSONObject merge(String author, String createdAt, String mergedAt) throws Exception {
        return new JSONObject()
                .put("author", new JSONObject().put("username", author))
                .put("created_at", createdAt)
                .put("merged_at", mergedAt);
    }

    private static MetricWeightManagement dimension(String key) {
        return metric(key, null, 0, 0, true);
    }

    private static MetricWeightManagement metric(String key, String parent, double min, double max, boolean higherIsBetter) {
        MetricWeightManagement config = new MetricWeightManagement();
        config.setMetricKey(key);
        config.setParentKey(parent);
        config.setWeight(1.0);
        config.setActive(true);
        config.setMinThreshold(min);
        config.setMaxThreshold(max);
        config.setPositiveMetric(higherIsBetter);
        return config;
    }
}
