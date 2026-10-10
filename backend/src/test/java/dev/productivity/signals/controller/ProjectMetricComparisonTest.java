package dev.productivity.signals.controller;

import dev.productivity.signals.service.*;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ProjectMetricComparisonTest {
    @Test
    void comparesOnlyProjectMetricsForRealGithubProject() {
        var demo = mock(DemoMockDataService.class);
        var controller = spy(new SpaceMetricController(
                mock(CommitService.class), mock(MergeService.class), mock(IssueService.class),
                mock(SpaceMetricScoringService.class), mock(GitService.class),
                mock(AiCorrectionService.class), mock(ProjectSatisfactionSurveyService.class),
                mock(SpaceMetricSnapshotService.class), mock(SpaceMetricAiCorrectionService.class), demo,
                mock(ContributorRetentionService.class), mock(ActiveMemberService.class)));
        String project = "github~openai~openai-java";
        doReturn(Map.of("spaceTotalScore", 62.0, "mergedCount", 4))
                .when(controller).getSpaceMetrics(project, "2026-09-08", "2026-09-14", null, "main", null, false);
        doReturn(Map.of("spaceTotalScore", 70.0, "mergedCount", 6))
                .when(controller).getSpaceMetrics(project, "2026-09-01", "2026-09-07", null, "main", null, false);

        Map<String, Object> response = controller.getProjectMetricComparison(
                project, "2026-09-08", "2026-09-14", "main", 7);

        Map<?, ?> trends = (Map<?, ?>) response.get("projectTrends");
        assertThat(trends.containsKey("spaceTotalScore")).isTrue();
        assertThat(trends.containsKey("mergedCount")).isTrue();
        verify(controller, never()).getMembersSpaceMetrics(anyString(), any(), any(), any(), any(), anyBoolean());
        verify(demo).shouldUseMockProject(project);
    }
}
