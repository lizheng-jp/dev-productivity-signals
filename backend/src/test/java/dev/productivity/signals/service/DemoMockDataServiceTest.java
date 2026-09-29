package dev.productivity.signals.service;

import dev.productivity.signals.dto.AiEvaluationResponseDTO;
import dev.productivity.signals.entity.AiMrEvaluation;
import dev.productivity.signals.repository.MetricWeightManagementRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoMockDataServiceTest {

    private final GeminiService geminiService = mock(GeminiService.class);
    private final SpaceMetricScoringService scoringService = new SpaceMetricScoringService(
            mock(MetricWeightManagementRepository.class));
    private final DemoMockDataService service = new DemoMockDataService(scoringService, geminiService);

    @Test
    void providesPrecomputedMrAnalysisWithoutCallingGeminiDiffAnalysis() {
        List<AiMrEvaluation> evaluations = service.getMockMrEvaluations("1", null);

        assertThat(evaluations).hasSize(6);
        assertThat(evaluations).extracting(AiMrEvaluation::getChangeType)
                .containsExactly("New Feature", "Bug Fix", "Refactor", "Optimization", "Auto-generated", "New Feature");
        assertThat(evaluations).filteredOn(AiMrEvaluation::getHasBug)
                .singleElement()
                .extracting(AiMrEvaluation::getMrIid)
                .isEqualTo(106);
        verify(geminiService, never()).analyzeDiff(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void callsGeminiOnlyForFinalPerformanceEvaluation() {
        when(geminiService.evaluatePerformance(anyMap(), anyList())).thenReturn("""
                {
                  "strengths": ["強み"],
                  "weaknesses": ["課題"],
                  "suggestions": ["改善策"]
                }
                """);

        AiEvaluationResponseDTO result = service.getAiEvaluation("1", "ALICE");

        assertThat(result.getStrengths()).containsExactly("強み");
        assertThat(result.getWeaknesses()).containsExactly("課題");
        assertThat(result.getSuggestions()).containsExactly("改善策");
        verify(geminiService).evaluatePerformance(anyMap(), anyList());
        verify(geminiService, never()).analyzeDiff(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void includesFocusTimeAndContextSwitchMetricsInEfficiencyScore() {
        Map<String, Object> metrics = service.getSpaceMetrics("1", "ALICE");

        assertThat(metrics.get("uninterruptedFocusTimeHours")).isEqualTo(3.6);
        assertThat(metrics.get("contextSwitchFrequency")).isEqualTo(3.0);
        assertThat(metrics.get("uninterruptedFocusTimeHoursScore")).isEqualTo(86.67);
        assertThat(metrics.get("contextSwitchFrequencyScore")).isEqualTo(83.33);
    }
}
