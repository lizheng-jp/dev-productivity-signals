package dev.productivity.signals.service;

import dev.productivity.signals.dto.CommitMetricsResponseDTO;
import dev.productivity.signals.dto.CommitStatsDTO;
import dev.productivity.signals.dto.WeeklyBreakdownDTO;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryTrendCalculatorTest {

    @Test
    void calculatesCommitMergeAndLeadTimeSeriesFromExistingMetrics() throws Exception {
        CommitMetricsResponseDTO commits = new CommitMetricsResponseDTO(
                7,
                List.of(
                        new WeeklyBreakdownDTO("2026-08-27", "2026-09-02", 3),
                        new WeeklyBreakdownDTO("2026-09-03", "2026-09-09", 4)),
                new CommitStatsDTO(0, 0, 0));
        List<JSONObject> mergeRequests = List.of(
                mergeRequest("2026-08-28T00:00:00Z", "2026-08-29T00:00:00Z"),
                mergeRequest("2026-08-30T00:00:00Z", "2026-09-01T00:00:00Z"),
                mergeRequest("2026-09-01T00:00:00Z", "2026-09-04T00:00:00Z"));

        List<Map<String, Object>> result = DeliveryTrendCalculator.calculate(commits, mergeRequests);

        assertThat(result).hasSize(2);
        assertThat(result.get(0))
                .containsEntry("commitCount", 3L)
                .containsEntry("mergedCount", 2L)
                .containsEntry("sampleCount", 2);
        assertThat((Double) result.get(0).get("averageLeadTimeHours")).isEqualTo(36.0);
        assertThat((Double) result.get(0).get("medianLeadTimeHours")).isEqualTo(36.0);
        assertThat(result.get(1))
                .containsEntry("commitCount", 4L)
                .containsEntry("mergedCount", 1L)
                .containsEntry("sampleCount", 1)
                .containsEntry("averageLeadTimeHours", 72.0);
    }

    @Test
    void keepsEmptyWeeksAndIgnoresMalformedMergeRequests() throws Exception {
        CommitMetricsResponseDTO commits = new CommitMetricsResponseDTO(
                0,
                List.of(new WeeklyBreakdownDTO("2026-09-10", "2026-09-16", 0)),
                new CommitStatsDTO(0, 0, 0));

        List<Map<String, Object>> result = DeliveryTrendCalculator.calculate(
                commits,
                List.of(new JSONObject().put("merged_at", "invalid")));

        assertThat(result).singleElement().satisfies(point -> {
            assertThat(point).containsEntry("mergedCount", 0L).containsEntry("sampleCount", 0);
            assertThat(point.get("averageLeadTimeHours")).isNull();
            assertThat(point.get("medianLeadTimeHours")).isNull();
        });
    }

    private JSONObject mergeRequest(String createdAt, String mergedAt) throws Exception {
        return new JSONObject()
                .put("created_at", createdAt)
                .put("merged_at", mergedAt);
    }
}
