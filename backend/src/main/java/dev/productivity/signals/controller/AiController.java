package dev.productivity.signals.controller;

import dev.productivity.signals.dto.AiAnalysisProgressDTO;
import dev.productivity.signals.dto.AiEvaluationResponseDTO;
import dev.productivity.signals.dto.AiMrAnalysisJobRequestDTO;
import dev.productivity.signals.dto.ComparisonAnalysisResponseDTO;
import dev.productivity.signals.entity.AiMrAnalysisJob;
import dev.productivity.signals.entity.AiMrAnalysisJobItem;
import dev.productivity.signals.entity.AiPromptVersion;
import dev.productivity.signals.service.AiCorrectionService;
import dev.productivity.signals.service.AiEvaluationService;
import dev.productivity.signals.service.AiEvaluationFeedbackService;
import dev.productivity.signals.service.AiMrAnalysisBatchService;
import dev.productivity.signals.service.DemoMockDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@Tag(name = "AI評価関連")
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Slf4j
public class AiController {

    private final AiEvaluationService aiEvaluationService;
    private final AiCorrectionService aiCorrectionService;
    private final AiMrAnalysisBatchService aiMrAnalysisBatchService;
    private final DemoMockDataService demoMockDataService;
    private final AiEvaluationFeedbackService aiEvaluationFeedbackService;

    @Value("${ai.mr-analysis.manual-run-enabled:true}")
    private boolean manualRunEnabled;

    @Operation(summary = "開発パフォーマンスのAI評価を取得します")
    @GetMapping("/evaluate")
    public AiEvaluationResponseDTO evaluatePerformance(
            @RequestParam String projectId,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) String userName,
            @RequestParam(required = false) String refName,
            @RequestParam(required = false) String snapshotId,
            @RequestParam(defaultValue = "true") boolean aiEnabled) {

        log.info("Request evaluatePerformance - projectId: {}, since: {}, until: {}, userName: {}, refName: {}, snapshotId: {}, aiEnabled: {}",
                projectId, since, until, userName, refName, snapshotId, aiEnabled);
        if (demoMockDataService.shouldUseMockProject(projectId)) {
            return aiEvaluationFeedbackService.capture(
                    aiEnabled ? demoMockDataService.getAiEvaluation(projectId, userName) : null,
                    projectId, since, until, userName, refName, aiEnabled);
        }
        return aiEvaluationFeedbackService.capture(
                aiEvaluationService.evaluatePerformance(
                        projectId, since, until, userName, refName, snapshotId, aiEnabled),
                projectId, since, until, userName, refName, aiEnabled);
    }

    @Operation(summary = "比較画面向けにSPACE指標とAI評価をまとめて取得します")
    @GetMapping("/comparison-analysis")
    public ComparisonAnalysisResponseDTO analyzeComparisonTarget(
            @RequestParam String projectId,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) String userName,
            @RequestParam(required = false) String refName,
            @RequestParam(required = false) String snapshotId,
            @RequestParam(defaultValue = "true") boolean aiEnabled) {

        log.info("Request analyzeComparisonTarget - projectId: {}, since: {}, until: {}, userName: {}, refName: {}, snapshotId: {}, aiEnabled: {}",
                projectId, since, until, userName, refName, snapshotId, aiEnabled);
        if (demoMockDataService.shouldUseMockProject(projectId)) {
            AiEvaluationResponseDTO evaluation = aiEvaluationFeedbackService.capture(
                    aiEnabled ? demoMockDataService.getAiEvaluation(projectId, userName) : null,
                    projectId, since, until, userName, refName, aiEnabled);
            return new ComparisonAnalysisResponseDTO(
                    demoMockDataService.getSpaceMetrics(projectId, userName),
                    evaluation);
        }

        ComparisonAnalysisResponseDTO result = aiEvaluationService.analyzeComparisonTarget(
                projectId, since, until, userName, refName, snapshotId, aiEnabled);
        aiEvaluationFeedbackService.capture(
                result.getAiEvaluation(), projectId, since, until, userName, refName, aiEnabled);
        return result;
    }

    @Operation(summary = "MR差分のAI分析進捗を取得します")
    @GetMapping("/analysis-progress")
    public AiAnalysisProgressDTO getAnalysisProgress(
            @RequestParam String projectId,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) String userName,
            @RequestParam(required = false) String refName) {
        if (demoMockDataService.shouldUseMockProject(projectId)) {
            return demoMockDataService.getAiAnalysisProgress(projectId);
        }
        return aiCorrectionService.getAnalysisProgress(projectId, since, until, userName, refName);
    }

    @Operation(summary = "MR品質分析のプロンプトバージョン一覧を取得します")
    @GetMapping("/prompt-versions")
    public List<AiPromptVersion> getPromptVersions() {
        return aiCorrectionService.getPromptVersions();
    }

    @GetMapping("/mr-analysis-settings")
    public Map<String, Boolean> getMrAnalysisSettings() {
        return Map.of("manualRunEnabled", manualRunEnabled);
    }

    @Operation(summary = "MR品質分析ジョブ履歴を取得します")
    @GetMapping("/mr-analysis-jobs")
    public List<AiMrAnalysisJob> getMrAnalysisJobs() {
        return aiCorrectionService.getAnalysisJobs();
    }

    @Operation(summary = "MR品質分析ジョブを実行します")
    @PostMapping("/mr-analysis-jobs")
    public AiMrAnalysisJob runMrAnalysisJob(@RequestBody AiMrAnalysisJobRequestDTO request) {
        requireManualRunEnabled();
        log.info("Request runMrAnalysisJob - projectId: {}, sinceDate: {}, untilDate: {}, refName: {}, promptVersionId: {}",
                request.getProjectId(), request.getSinceDate(), request.getUntilDate(), request.getRefName(),
                request.getPromptVersionId());
        return aiCorrectionService.runAnalysisJob(request);
    }

    @Operation(summary = "MR品質分析ジョブ明細を取得します")
    @GetMapping("/mr-analysis-jobs/{jobId}/items")
    public List<AiMrAnalysisJobItem> getMrAnalysisJobItems(@PathVariable Long jobId) {
        return aiCorrectionService.getAnalysisJobItems(jobId);
    }

    @Operation(summary = "指定したプロジェクト・期間のMR差分AI分析をバッチ実行します")
    @PostMapping("/mr-analysis-batch")
    public Map<String, Object> runMrAnalysisBatch(
            @RequestParam String projectIds,
            @RequestParam String since,
            @RequestParam String until,
            @RequestParam(required = false) String refName) {

        requireManualRunEnabled();

        log.info("Request runMrAnalysisBatch - projectIds: {}, since: {}, until: {}, refName: {}",
                projectIds, since, until, refName);

        return aiMrAnalysisBatchService.runBatch(
                aiMrAnalysisBatchService.parseProjectIds(projectIds),
                since,
                until,
                refName);
    }

    private void requireManualRunEnabled() {
        if (!manualRunEnabled) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Manual MR analysis is disabled in this demo");
        }
    }
}
