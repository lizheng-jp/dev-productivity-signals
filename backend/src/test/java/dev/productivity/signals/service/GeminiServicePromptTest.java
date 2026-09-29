package dev.productivity.signals.service;

import dev.productivity.signals.repository.MetricWeightManagementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.productivity.signals.util.SpaceMetricConstants.activityScore;
import static dev.productivity.signals.util.SpaceMetricConstants.commitCount;
import static dev.productivity.signals.util.SpaceMetricConstants.contextSwitchFrequency;
import static dev.productivity.signals.util.SpaceMetricConstants.hasActivity;
import static dev.productivity.signals.util.SpaceMetricConstants.uninterruptedFocusTimeHours;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GeminiServicePromptTest {

    private GeminiService service;

    @BeforeEach
    void setUp() {
        MetricWeightManagementRepository repository = mock(MetricWeightManagementRepository.class);
        when(repository.findByIsActive(true)).thenReturn(List.of());
        service = new GeminiService(repository, mock(MonitoringMetricsService.class));
    }

    @Test
    void missingScoresAreRenderedAsMissingInsteadOfZero() {
        String prompt = service.buildPerformanceEvaluationPrompt(
                Map.of(hasActivity, false),
                List.of());

        assertThat(prompt)
                .contains("- Performance: 未取得")
                .contains("- Activity: 未取得")
                .contains("\"開発活動データ\":\"なし\"")
                .contains("【関連SPACE：データ品質／関連指標：活動データ取得状況】")
                .contains("Do not invent a strength")
                .contains("it must not call the developer's performance low")
                .doesNotContain("- Performance: 0")
                .doesNotContain("- Activity: 0");
    }

    @Test
    void observedZeroIsExplicitlyDistinguishedFromMissingData() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put(hasActivity, true);
        metrics.put(activityScore, 0);
        metrics.put(commitCount, 0);

        String prompt = service.buildPerformanceEvaluationPrompt(metrics, List.of());

        assertThat(prompt)
                .contains("- Activity: 0")
                .contains("\"開発活動データ\":\"あり\"")
                .contains("\"アクティビティ\":\"取得済み（値0）\"")
                .contains("\"コミット数\":\"取得済み（値0）\"")
                .contains("The paired suggestion must start with the identical tag")
                .contains("期待する変化：<related Japanese metric name and expected direction>");
    }

    @Test
    void efficiencyFlowMetricsAreIncludedInFinalEvaluationPrompt() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put(hasActivity, true);
        metrics.put(uninterruptedFocusTimeHours, 3.6);
        metrics.put(contextSwitchFrequency, 3.0);

        String prompt = service.buildPerformanceEvaluationPrompt(metrics, List.of());

        assertThat(prompt)
                .contains("\"指標名\":\"連続集中時間（時間／営業日）\"")
                .contains("\"指標名\":\"コンテキストスイッチ頻度（回／営業日）\"")
                .contains("\"値\":3.6")
                .contains("\"値\":3");
    }
}
