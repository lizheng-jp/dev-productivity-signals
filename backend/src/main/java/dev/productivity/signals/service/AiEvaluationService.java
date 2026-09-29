package dev.productivity.signals.service;

import dev.productivity.signals.dto.AiEvaluationResponseDTO;
import dev.productivity.signals.dto.ComparisonAnalysisResponseDTO;
import dev.productivity.signals.dto.CommitMetricsResponseDTO;
import dev.productivity.signals.dto.IssueStatsDTO;
import dev.productivity.signals.dto.MergeRequestStatsDTO;
import dev.productivity.signals.dto.ProjectSatisfactionSummaryDTO;
import dev.productivity.signals.entity.AiMrEvaluation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.json.JSONArray;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static dev.productivity.signals.util.SpaceMetricConstants.*;
import dev.productivity.signals.util.PerformanceTimingLog;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiEvaluationService {

    private final CommitService commitService;
    private final MergeService mergeService;
    private final IssueService issueService;
    private final SpaceMetricScoringService scoringService;
    private final GeminiService geminiService;
    private final AiCorrectionService aiCorrectionService;
    private final ProjectSatisfactionSurveyService satisfactionSurveyService;
    private final SpaceMetricSnapshotService snapshotService;
    private final SpaceMetricAiCorrectionService cachedAiCorrectionService;

    public AiEvaluationResponseDTO evaluatePerformance(
            String projectId, String since, String until, String userName, String refName) {
        return evaluatePerformance(projectId, since, until, userName, refName, true);
    }

    public AiEvaluationResponseDTO evaluatePerformance(
            String projectId, String since, String until, String userName, String refName, boolean aiEnabled) {
        return evaluatePerformance(projectId, since, until, userName, refName, null, aiEnabled);
    }

    public AiEvaluationResponseDTO evaluatePerformance(
            String projectId, String since, String until, String userName, String refName, String snapshotId,
            boolean aiEnabled) {
        PerformanceTimingLog timing = PerformanceTimingLog.start("ai.evaluatePerformance", projectId, userName, since, until, refName);
        try {
            if (!aiEnabled) {
                return null;
            }
            Map<String, Object> spaceMetrics = getAiCorrectedMetrics(
                    projectId, since, until, userName, refName, snapshotId);
            return PerformanceTimingLog.time("ai.buildEvaluation", () -> buildAiEvaluation(spaceMetrics));
        } finally {
            timing.logSummary();
            PerformanceTimingLog.clear();
        }
    }

    public ComparisonAnalysisResponseDTO analyzeComparisonTarget(
            String projectId, String since, String until, String userName, String refName) {
        return analyzeComparisonTarget(projectId, since, until, userName, refName, true);
    }

    public ComparisonAnalysisResponseDTO analyzeComparisonTarget(
            String projectId, String since, String until, String userName, String refName, boolean aiEnabled) {
        return analyzeComparisonTarget(projectId, since, until, userName, refName, null, aiEnabled);
    }

    public ComparisonAnalysisResponseDTO analyzeComparisonTarget(
            String projectId, String since, String until, String userName, String refName, String snapshotId,
            boolean aiEnabled) {
        PerformanceTimingLog timing = PerformanceTimingLog.start("ai.comparisonAnalysis", projectId, userName, since, until, refName);
        try {
            if (!aiEnabled) {
                Map<String, Object> spaceMetrics = calculateSpaceMetrics(
                        projectId, since, until, userName, refName, false);
                String newSnapshotId = snapshotService.storeSingle(
                        projectId, since, until, userName, refName, spaceMetrics);
                spaceMetrics.put("snapshotId", newSnapshotId);
                return new ComparisonAnalysisResponseDTO(spaceMetrics, null);
            }
            Map<String, Object> spaceMetrics = getAiCorrectedMetrics(
                    projectId, since, until, userName, refName, snapshotId);
            AiEvaluationResponseDTO evaluation = PerformanceTimingLog.time("ai.buildEvaluation",
                    () -> buildAiEvaluation(spaceMetrics));
            return new ComparisonAnalysisResponseDTO(spaceMetrics, evaluation);
        } finally {
            timing.logSummary();
            PerformanceTimingLog.clear();
        }
    }

    private Map<String, Object> getAiCorrectedMetrics(
            String projectId,
            String since,
            String until,
            String userName,
            String refName,
            String snapshotId) {
        if (snapshotId != null && !snapshotId.isBlank()) {
            Optional<Map<String, Object>> cachedMetrics = snapshotService.findSingle(
                    snapshotId, projectId, since, until, userName, refName);
            if (cachedMetrics.isEmpty() && userName != null && !userName.isBlank()) {
                cachedMetrics = snapshotService.findMember(
                        snapshotId, projectId, since, until, userName, refName);
            }
            if (cachedMetrics.isPresent()) {
                Map<String, Object> corrected = cachedAiCorrectionService.correct(
                        cachedMetrics.get(), projectId, since, until, userName, refName);
                corrected.put("snapshotId", snapshotId);
                log.info("Built AI evaluation from SPACE snapshot: {}, userName: {}", snapshotId, userName);
                return corrected;
            }
            log.info("SPACE snapshot unavailable or context mismatch; recalculating AI metrics: {}", snapshotId);
        }
        return calculateSpaceMetrics(projectId, since, until, userName, refName, true);
    }

    private AiEvaluationResponseDTO buildAiEvaluation(Map<String, Object> spaceMetrics) {
        String aiResponseJson = geminiService.evaluatePerformance(spaceMetrics, extractAiEvaluations(spaceMetrics));
        if (aiResponseJson == null) {
            return null;
        }

        try {
            JSONObject json = new JSONObject(aiResponseJson);
            AiEvaluationResponseDTO response = new AiEvaluationResponseDTO();
            response.setStrengths(toList(json.getJSONArray("strengths")));
            response.setWeaknesses(toList(json.getJSONArray("weaknesses")));
            response.setSuggestions(toList(json.getJSONArray("suggestions")));
            return response;
        } catch (Exception e) {
            log.error("Error parsing AI evaluation response: {}", e.getMessage());
            return null;
        }
    }

    private List<?> extractAiEvaluations(Map<String, Object> spaceMetrics) {
        Object value = spaceMetrics.get("aiEvaluations");
        return value instanceof List<?> list ? list : List.of();
    }

    private List<String> toList(org.json.JSONArray array) {
        List<String> list = new java.util.ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            list.add(array.getString(i));
        }
        return list;
    }

    // SpaceMetricController からコピー (リファクタリングが望ましいが、今回は最小限の変更に留める)
    private Map<String, Object> calculateSpaceMetrics(String projectId, String since, String until, String userName, String refName) {
        return calculateSpaceMetrics(projectId, since, until, userName, refName, true);
    }

    private Map<String, Object> calculateSpaceMetrics(String projectId, String since, String until, String userName, String refName, boolean aiEnabled) {
        // SpaceMetricController の calculateForUser と同等のロジック
        List<JSONObject> allCreatedMRs = PerformanceTimingLog.time("gitlab.prefetchCreatedMRs",
                () -> mergeService.fetchAllCreatedMergeRequests(projectId, since, until, refName));
        PerformanceTimingLog.addCount("mrs.created", allCreatedMRs.size());
        List<JSONObject> allUpdatedMRs = PerformanceTimingLog.time("gitlab.prefetchUpdatedMRs",
                () -> mergeService.fetchAllUpdatedMergeRequests(projectId, since, until, refName));
        PerformanceTimingLog.addCount("mrs.updated", allUpdatedMRs.size());
        List<JSONObject> allMergedMRs = PerformanceTimingLog.time("gitlab.prefetchMergedMRs",
                () -> mergeService.fetchAllMergedMergeRequests(projectId, since, until, refName));
        PerformanceTimingLog.addCount("mrs.merged", allMergedMRs.size());
        List<JSONObject> noteTargets = new ArrayList<>(allCreatedMRs);
        noteTargets.addAll(allUpdatedMRs);
        Map<Integer, JSONArray> notesByMrIid = PerformanceTimingLog.time("gitlab.prefetchMRNotes",
                () -> mergeService.fetchNotesByMergeRequest(projectId, noteTargets));
        PerformanceTimingLog.addCount("mrs.notes.prefetched", notesByMrIid.size());
        List<AiMrEvaluation> allAiEvaluations = aiEnabled
                ? PerformanceTimingLog.time("ai.prefetchEvaluations",
                        () -> aiCorrectionService.findAnalyzedMRs(projectId, since, until, null, refName))
                : List.of();

        String apiSince = (since != null && since.length() == 10) ? since + "T00:00:00Z" : since;
        String apiUntil = (until != null && until.length() == 10) ? until + "T23:59:59Z" : until;
        ZonedDateTime startRange = (apiSince != null && !apiSince.isEmpty()) ? ZonedDateTime.parse(apiSince) : null;
        ZonedDateTime endRange = (apiUntil != null && !apiUntil.isEmpty()) ? ZonedDateTime.parse(apiUntil) : null;

        // Commit stats
        CommitMetricsResponseDTO commitMetrics = PerformanceTimingLog.time("gitlab.commits",
                () -> commitService.getCommitCount(projectId, userName, since, until, refName));

        // Merge stats
        MergeRequestStatsDTO userMergeStats = PerformanceTimingLog.time("gitlab.userMergeStats",
                () -> mergeService.getMergeRequestStatsForUser(projectId, userName, since, until, refName,
                        allMergedMRs, allCreatedMRs, notesByMrIid));
        MergeRequestStatsDTO reviewStats = PerformanceTimingLog.time("space.reviewStatsFromPrefetch",
                () -> mergeService.calculateReviewStatsFromFetchedLists(projectId, userName, allUpdatedMRs,
                        startRange, endRange, notesByMrIid));

        MergeRequestStatsDTO mergeStats = new MergeRequestStatsDTO(
                userMergeStats.getCreatedCount(),
                userMergeStats.getCreatedAndMergedCount(),
                userMergeStats.getMergedCount(),
                userMergeStats.getTotalCommentCount(),
                userMergeStats.getTotalReviewerCount(),
                userMergeStats.getAvgHoursToMergeByCreated(),
                userMergeStats.getAvgHoursToMergeByMerged(),
                userMergeStats.getAvgHoursToFirstReview(),
                reviewStats.getGivenReviewCount(),
                reviewStats.getGivenCommentCount());

        // Issue stats
        IssueStatsDTO issueStats = PerformanceTimingLog.time("gitlab.issues",
                () -> issueService.getIssueStats(projectId, since, until, userName, refName));

        // Metrics map
        Map<String, Number> metrics = new HashMap<>();
        double weeks = calculateWeeks(since, until);

        metrics.put(mergedCount, mergeStats.getMergedCount());
        metrics.put(mergedLeadTimeHours, sanitize(mergeStats.getAvgHoursToMergeByMerged()));
        metrics.put(bugCausedCount, issueStats.getBugCausedCount());
        metrics.put(bugFixLeadTimeHours, sanitize(issueStats.getAvgBugFixedHours()));
        metrics.put(commitCount, commitMetrics.getTotalCommitCount());
        metrics.put(issueCreatedCount, issueStats.getCreatedCount());
        metrics.put(bugFoundCount, issueStats.getBugFoundCount());
        metrics.put(bugFixedCount, issueStats.getBugFixedCount());
        metrics.put(linesAdded, commitMetrics.getCommitStats().getLinesAdded());
        metrics.put(linesDeleted, commitMetrics.getCommitStats().getLinesDeleted());
        metrics.put(linesTotal, commitMetrics.getCommitStats().getLinesTotal());

        if (userName != null) {
            metrics.put(reviewedCount, sanitize(mergeStats.getGivenReviewCount()));
            metrics.put(commentCount, mergeStats.getGivenCommentCount());
            double avgCommentsPerMR = (mergeStats.getCreatedCount() > 0) ? (double) mergeStats.getTotalCommentCount() / mergeStats.getCreatedCount() : 0.0;
            metrics.put(reviewCommentCount, sanitize(avgCommentsPerMR));
        } else {
            metrics.put(reviewedCount, sanitize(mergeStats.getTotalReviewerCount()));
            metrics.put(commentCount, mergeStats.getTotalCommentCount());
            double avgCommentsPerMR = (mergeStats.getCreatedCount() > 0) ? (double) mergeStats.getTotalCommentCount() / mergeStats.getCreatedCount() : 0.0;
            metrics.put(reviewCommentCount, sanitize(avgCommentsPerMR));
        }
        metrics.put(reviewWaitTime, sanitize(mergeStats.getAvgHoursToFirstReview()));

        Map<String, Object> results = PerformanceTimingLog.time("space.rawScoring",
                () -> scoringService.calculateScores(metrics, weeks));
        PerformanceTimingLog.time("db.satisfaction", () -> applyProjectSatisfactionScore(projectId, since, until, userName, results));

        if (aiEnabled) {
        try {
            List<AiMrEvaluation> userEvaluations = (userName != null)
                    ? allAiEvaluations.stream()
                            .filter(eval -> userName.equalsIgnoreCase(eval.getAuthorUsername()))
                            .collect(Collectors.toList())
                    : allAiEvaluations;

            Map<String, Number> correctedMetrics = PerformanceTimingLog.time("ai.correctMetrics",
                    () -> aiCorrectionService.getCorrectedMetrics(userEvaluations, metrics));
            Map<String, Object> aiResults = PerformanceTimingLog.time("space.aiCorrectedScoring",
                    () -> scoringService.calculateScores(correctedMetrics, weeks));
            PerformanceTimingLog.time("db.satisfactionAiCorrected", () -> applyProjectSatisfactionScore(projectId, since, until, userName, aiResults));
            results.put("aiCorrected", aiResults);
            results.put("aiEvaluations", userEvaluations);
        } catch (Exception e) {
            log.error("AI correction failed: {}", e.getMessage());
        }
        }

        return results;
    }

    private void applyProjectSatisfactionScore(
            String projectId,
            String since,
            String until,
            String userName,
            Map<String, Object> results) {
        if (results == null) {
            return;
        }

        ProjectSatisfactionSummaryDTO summary = satisfactionSurveyService.getSummary(projectId, since, until,
                userName);
        if (summary.getResponseCount() <= 0 || summary.getSatisfactionScore() == null) {
            return;
        }

        results.put(satisfactionSurveyScore, summary.getSatisfactionScore());
        results.put(satisfactionResponseCount, summary.getResponseCount());
        results.put(satisfactionJobMeaning, summary.getS1JobSatisfaction());
        results.put(satisfactionDeveloperEfficacy, summary.getS2DeveloperEfficacy());
        results.put(satisfactionSustainability, summary.getS3Sustainability());
        results.put(satisfactionImprovementPotential, summary.getS4ImprovementPotential());
        results.put("satisfactionSurvey", summary);
        scoringService.applyDimensionScoreOverride(results, "satisfaction", summary.getSatisfactionScore());
    }

    private double calculateWeeks(String since, String until) {
        if (since == null || until == null || since.isEmpty() || until.isEmpty()) return 1.0;
        try {
            LocalDate startDate = LocalDate.parse(since, DateTimeFormatter.ISO_DATE);
            LocalDate endDate = LocalDate.parse(until, DateTimeFormatter.ISO_DATE);
            long days = ChronoUnit.DAYS.between(startDate, endDate) + 1;
            return (days < 1) ? 1.0 / 7.0 : days / 7.0;
        } catch (Exception e) {
            return 1.0;
        }
    }

    private double sanitize(double value) {
        return (Double.isNaN(value) || Double.isInfinite(value)) ? 0.0 : value;
    }
}
