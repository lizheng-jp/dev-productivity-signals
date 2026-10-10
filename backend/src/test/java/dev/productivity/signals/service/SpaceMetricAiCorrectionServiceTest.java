package dev.productivity.signals.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SpaceMetricAiCorrectionServiceTest {

    @Test
    void keepsRetentionBasedSatisfactionWhenCorrectingASnapshot() {
        var aiCorrection = mock(AiCorrectionService.class);
        var scoring = mock(SpaceMetricScoringService.class);
        when(aiCorrection.getCorrectedMetrics(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
        when(scoring.calculateScores(anyMap(), anyDouble())).thenAnswer(invocation -> new HashMap<>(Map.of("spaceTotalScore", 50.0)));

        Map<String, Object> raw = new HashMap<>(Map.of(
                "mergedCount", 4,
                "satisfactionSource", "retention",
                "contributorRetentionRate", 62.5,
                "contributorRetentionScore", 71.0,
                "retainedContributorCount", 5,
                "previousActiveContributorCount", 8));

        Map<String, Object> result = new SpaceMetricAiCorrectionService(aiCorrection, scoring)
                .correct(raw, "2026-09-01", "2026-09-30", null, List.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> corrected = (Map<String, Object>) result.get("aiCorrected");
        assertThat(corrected).containsEntry("satisfactionSource", "retention")
                .containsEntry("contributorRetentionRate", 62.5);
        verify(scoring).applyDimensionScoreOverride(corrected, "satisfaction", 71.0);
    }
}
