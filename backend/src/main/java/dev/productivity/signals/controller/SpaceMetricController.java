package dev.productivity.signals.controller;

import dev.productivity.signals.dto.CommitMetricsResponseDTO;
import dev.productivity.signals.dto.CommitStatsDTO;
import dev.productivity.signals.dto.IssueStatsDTO;
import dev.productivity.signals.dto.MergeRequestStatsDTO;
import dev.productivity.signals.dto.ProjectSatisfactionSummaryDTO;
import dev.productivity.signals.service.*;
import dev.productivity.signals.entity.AiMrEvaluation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static dev.productivity.signals.util.SpaceMetricConstants.*;
import dev.productivity.signals.util.PerformanceTimingLog;

@RestController
@Tag(name = "SPACE指標関連")
@RequestMapping("/api/gitlab/projects/{projectId}/space-metrics")
@RequiredArgsConstructor
@Slf4j
public class SpaceMetricController {

    private final CommitService commitService;
    private final MergeService mergeService;
    private final IssueService issueService;
    private final SpaceMetricScoringService scoringService;
    private final GitService gitService;
    private final AiCorrectionService aiCorrectionService;
    private final ProjectSatisfactionSurveyService satisfactionSurveyService;
    private final SpaceMetricSnapshotService snapshotService;
    private final SpaceMetricAiCorrectionService cachedAiCorrectionService;
    private final DemoMockDataService demoMockDataService;

    @Operation(summary = "プロジェクト全体、または特定ユーザーのSPACE指標とスコアを取得します")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "正常終了"),
            @ApiResponse(responseCode = "500", description = "サーバーエラー") })
    @GetMapping
    public Map<String, Object> getSpaceMetrics(
            @PathVariable String projectId,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) String userName,
            @RequestParam(required = false) String refName,
            @RequestParam(required = false) String snapshotId,
            @RequestParam(defaultValue = "true") boolean aiEnabled) {

        log.info("Request getSpaceMetrics - projectId: {}, since: {}, until: {}, userName: {}, refName: {}, snapshotId: {}, aiEnabled: {}",
                projectId, since, until, userName, refName, snapshotId, aiEnabled);
        if (demoMockDataService.shouldUseMockProject(projectId)) {
            return demoMockDataService.getSpaceMetrics(projectId, userName);
        }
        PerformanceTimingLog timing = PerformanceTimingLog.start("spaceMetrics.single", projectId, userName, since, until, refName);
        try {
            if (aiEnabled && snapshotId != null && !snapshotId.isBlank()) {
                var cachedMetrics = snapshotService.findSingle(
                        snapshotId, projectId, since, until, userName, refName);
                if (cachedMetrics.isPresent()) {
                    Map<String, Object> corrected = cachedAiCorrectionService.correct(
                            cachedMetrics.get(), projectId, since, until, userName, refName);
                    corrected.put("snapshotId", snapshotId);
                    log.info("Applied AI correction from SPACE snapshot: {}", snapshotId);
                    return corrected;
                }
                log.info("SPACE snapshot unavailable or context mismatch; recalculating: {}", snapshotId);
            }

            Map<String, Object> results = calculateForUser(projectId, since, until, userName, refName, aiEnabled);
            if (!aiEnabled) {
                String newSnapshotId = snapshotService.storeSingle(
                        projectId, since, until, userName, refName, results);
                results.put("snapshotId", newSnapshotId);
            }
            return results;
        } catch (Exception e) {
            log.error("Error in getSpaceMetrics for projectId: {}, userName: {}. Error: {}",
                    projectId, userName, e.getMessage(), e);
            throw e;
        } finally {
            timing.logSummary();
            PerformanceTimingLog.clear();
        }
    }

    @Operation(summary = "プロジェクト全メンバーのSPACE指標とスコアを取得します")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "正常終了"),
            @ApiResponse(responseCode = "500", description = "サーバーエラー") })
    @GetMapping("/members")
    public List<Map<String, Object>> getMembersSpaceMetrics(
            @PathVariable String projectId,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) String refName,
            @RequestParam(required = false) String snapshotId,
            @RequestParam(defaultValue = "true") boolean aiEnabled) {

        log.info("Request getMembersSpaceMetrics - projectId: {}, since: {}, until: {}, refName: {}, snapshotId: {}, aiEnabled: {}",
                projectId, since, until, refName, snapshotId, aiEnabled);
        if (demoMockDataService.shouldUseMockProject(projectId)) {
            return demoMockDataService.getMembersSpaceMetrics(projectId);
        }
        PerformanceTimingLog timing = PerformanceTimingLog.start("spaceMetrics.members", projectId, "all-members", since, until, refName);
        long totalStartTime = System.currentTimeMillis();
        try {
            if (aiEnabled && snapshotId != null && !snapshotId.isBlank()) {
                var cachedMetrics = snapshotService.findMembers(snapshotId, projectId, since, until, refName);
                if (cachedMetrics.isPresent()) {
                    List<AiMrEvaluation> allAiEvaluations = PerformanceTimingLog.time("ai.prefetchEvaluations",
                            () -> aiCorrectionService.findAnalyzedMRs(projectId, since, until, null, refName));
                    List<Map<String, Object>> correctedResults = cachedMetrics.get().stream()
                            .map(rawResult -> {
                                Object userCode = rawResult.get("userCode");
                                Map<String, Object> corrected = cachedAiCorrectionService.correct(
                                        rawResult,
                                        since,
                                        until,
                                        userCode == null ? null : userCode.toString(),
                                        allAiEvaluations);
                                corrected.put("snapshotId", snapshotId);
                                return corrected;
                            })
                            .toList();
                    log.info("Applied AI correction to {} members from SPACE snapshot: {}",
                            correctedResults.size(), snapshotId);
                    return correctedResults;
                }
                log.info("SPACE members snapshot unavailable or context mismatch; recalculating: {}", snapshotId);
            }

            List<String> userCodes = PerformanceTimingLog.time("gitlab.projectMembers",
                    () -> gitService.getAllProjectUserCodes(projectId));
            PerformanceTimingLog.addCount("members.total", userCodes.size());

            String apiSince = (since != null && since.length() == 10) ? since + "T00:00:00Z" : since;
            String apiUntil = (until != null && until.length() == 10) ? until + "T23:59:59Z" : until;
            ZonedDateTime startRange = (apiSince != null && !apiSince.isEmpty()) ? ZonedDateTime.parse(apiSince) : null;
            ZonedDateTime endRange = (apiUntil != null && !apiUntil.isEmpty()) ? ZonedDateTime.parse(apiUntil) : null;

            // パフォーマンス改善：全ユーザーで共通のMRリストを事前に一括取得
            List<JSONObject> allCreatedMRs = PerformanceTimingLog.time("gitlab.prefetchCreatedMRs",
                    () -> mergeService.fetchAllCreatedMergeRequests(projectId, since, until, refName));
            PerformanceTimingLog.addCount("mrs.created", allCreatedMRs.size());

            List<JSONObject> allUpdatedMRs = PerformanceTimingLog.time("gitlab.prefetchUpdatedMRs",
                    () -> mergeService.fetchAllUpdatedMergeRequests(projectId, since, until, refName));
            PerformanceTimingLog.addCount("mrs.updated", allUpdatedMRs.size());

            List<JSONObject> allMergedMRs = PerformanceTimingLog.time("gitlab.prefetchMergedMRs",
                    () -> mergeService.fetchAllMergedMergeRequests(projectId, since, until, refName));
            PerformanceTimingLog.addCount("mrs.merged", allMergedMRs.size());

            List<JSONObject> noteTargetMRs = uniqueMergeRequestsByIid(allCreatedMRs, allUpdatedMRs);
            Map<Integer, org.json.JSONArray> notesByMrIid = PerformanceTimingLog.time("gitlab.prefetchMRNotes",
                    () -> mergeService.fetchNotesByMergeRequest(projectId, noteTargetMRs));
            PerformanceTimingLog.addCount("mrs.notes.prefetched", notesByMrIid.size());

            // パフォーマンス改善：AI分析用のMRリストも事前に一括取得
            List<AiMrEvaluation> allAiEvaluations = aiEnabled
                    ? PerformanceTimingLog.time("ai.prefetchEvaluations",
                            () -> aiCorrectionService.findAnalyzedMRs(projectId, since, until, null, refName))
                    : List.of();

            Map<String, CommitMetricsResponseDTO> commitMetricsByUser = PerformanceTimingLog.time("gitlab.prefetchCommits",
                    () -> commitService.getCommitMetricsByUser(projectId, userCodes, since, until, refName));
            PerformanceTimingLog.addCount("commits.users", commitMetricsByUser.size());

            Map<String, MergeRequestStatsDTO> authorMergeStatsByUser = PerformanceTimingLog.time("space.authorMergeStatsFromPrefetch",
                    () -> mergeService.calculateAuthorStatsByUser(projectId, userCodes, allCreatedMRs, startRange, endRange, notesByMrIid));
            PerformanceTimingLog.addCount("mrs.authorStats.users", authorMergeStatsByUser.size());

            Map<String, MergeRequestStatsDTO> mergedStatsByUser = PerformanceTimingLog.time("space.mergedStatsByAuthorFromPrefetch",
                    () -> mergeService.calculateMergedStatsByAuthor(userCodes, allMergedMRs, startRange, endRange));
            PerformanceTimingLog.addCount("mrs.mergedStats.users", mergedStatsByUser.size());

            Map<String, MergeRequestStatsDTO> reviewStatsByUser = PerformanceTimingLog.time("space.reviewStatsByReviewerFromPrefetch",
                    () -> mergeService.calculateReviewStatsByReviewer(projectId, userCodes, allUpdatedMRs,
                            startRange, endRange, notesByMrIid));
            PerformanceTimingLog.addCount("mrs.reviewStats.users", reviewStatsByUser.size());

            Map<String, IssueStatsDTO> issueStatsByUser = PerformanceTimingLog.time("gitlab.prefetchIssues",
                    () -> issueService.getIssueStatsByUser(projectId, since, until, userCodes, refName));
            PerformanceTimingLog.addCount("issues.users", issueStatsByUser.size());

            List<Map<String, Object>> results = PerformanceTimingLog.time("space.membersCalculation", () -> userCodes.parallelStream()
                    .filter(userCode -> userCode != null && !userCode.isEmpty()) // Skip null/empty users
                    .map(userCode -> {
                        return PerformanceTimingLog.withCurrent(timing, () -> {
                            try {
                                // 事前取得したリストを渡して計算
                                Map<String, Object> result = calculateForUser(
                                        projectId,
                                        since,
                                        until,
                                        userCode.toUpperCase(),
                                        refName,
                                        allCreatedMRs,
                                        allUpdatedMRs,
                                        allMergedMRs,
                                        startRange,
                                        endRange,
                                        allAiEvaluations,
                                        notesByMrIid,
                                        commitMetricsByUser.get(userCode.toUpperCase()),
                                        authorMergeStatsByUser.get(userCode.toUpperCase()),
                                        mergedStatsByUser.get(userCode.toUpperCase()),
                                        reviewStatsByUser.get(userCode.toUpperCase()),
                                        issueStatsByUser.get(userCode.toUpperCase()),
                                        aiEnabled);
                                if (result != null) {
                                    result.put("userCode", userCode.toUpperCase());
                                }
                                return result;
                            } catch (Exception e) {
                                log.error("CRITICAL: Failed for user {}: {}", userCode, e.getMessage());
                                Map<String, Object> err = new HashMap<>();
                                err.put("userCode", userCode != null ? userCode.toUpperCase() : "UNKNOWN");
                                err.put(spaceTotalScore, 0.0);
                                return err;
                            }
                        });
                    })
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toList()));
            PerformanceTimingLog.addCount("members.calculated", results.size());

            log.info("Calculation finished. Total items: {}. Total time: {}ms.",
                    results.size(), System.currentTimeMillis() - totalStartTime);

            if (!aiEnabled) {
                String newSnapshotId = snapshotService.storeMembers(projectId, since, until, refName, results);
                results.forEach(result -> result.put("snapshotId", newSnapshotId));
            }

            return results;
        } catch (Exception e) {
            log.error("Fatal error in getMembersSpaceMetrics: {}", e.getMessage(), e);
            throw e;
        } finally {
            timing.logSummary();
            PerformanceTimingLog.clear();
        }
    }

    @Operation(summary = "現在期間と直前期間のSPACE指標を比較します")
    @GetMapping("/comparison/project")
    public Map<String, Object> getProjectMetricComparison(
            @PathVariable String projectId,
            @RequestParam String since,
            @RequestParam String until,
            @RequestParam(required = false) String refName,
            @RequestParam(defaultValue = "7") int previousDays) {
        MetricComparison.Periods periods = MetricComparison.periods(since, until, previousDays);
        Map<String, Object> current = getSpaceMetrics(projectId, since, until, null, refName, null, false);
        Map<String, Object> previous = demoMockDataService.shouldUseMockProject(projectId)
                ? buildDemoPrevious(current)
                : getSpaceMetrics(projectId, periods.previous().since().toString(),
                        periods.previous().until().toString(), null, refName, null, false);
        return Map.of(
                "periods", Map.of(
                        "current", Map.of("since", since, "until", until),
                        "previous", Map.of("since", periods.previous().since().toString(),
                                "until", periods.previous().until().toString())),
                "current", current,
                "previous", previous,
                "projectTrends", MetricComparison.compare(current, previous, periods));
    }

    @Operation(summary = "現在期間と直前期間のSPACE指標を比較します")
    @GetMapping("/comparison")
    public Map<String, Object> getMetricComparison(
            @PathVariable String projectId,
            @RequestParam String since,
            @RequestParam String until,
            @RequestParam(required = false) String refName,
            @RequestParam(defaultValue = "7") int previousDays,
            @RequestParam(defaultValue = "false") boolean aiEnabled,
            @RequestParam(required = false) String comparisonId) {
        MetricComparison.Periods periods = MetricComparison.periods(since, until, previousDays);
        String previousSince = periods.previous().since().toString();
        String previousUntil = periods.previous().until().toString();

        Map<String, Object> currentProject;
        List<Map<String, Object>> currentMembers;
        Map<String, Object> previousProject;
        List<Map<String, Object>> previousMembers;
        List<?> members;

        if (demoMockDataService.shouldUseMockProject(projectId)) {
            currentProject = demoMockDataService.getSpaceMetrics(projectId, null);
            currentMembers = demoMockDataService.getMembersSpaceMetrics(projectId);
            previousProject = buildDemoPrevious(currentProject);
            previousMembers = currentMembers.stream().map(this::buildDemoPrevious).toList();
            members = demoMockDataService.getProjectMembers(projectId);
        } else {
            currentProject = getSpaceMetrics(
                    projectId, since, until, null, refName, null, aiEnabled);
            currentMembers = getMembersSpaceMetrics(
                    projectId, since, until, refName, null, aiEnabled);
            previousProject = getSpaceMetrics(
                    projectId, previousSince, previousUntil, null, refName, null, false);
            previousMembers = getMembersSpaceMetrics(
                    projectId, previousSince, previousUntil, refName, null, false);
            members = gitService.getAllProjectUserCodes(projectId).stream()
                    .map(userCode -> Map.of(
                            "userCode", userCode,
                            "userName", userCode,
                            "groupId", "",
                            "groupName", ""))
                    .toList();
        }

        Map<String, Map<String, MetricComparison.Trend>> developerTrends = new LinkedHashMap<>();
        Map<String, Map<String, Object>> previousByUser = previousMembers.stream()
                .filter(item -> item.get("userCode") != null)
                .collect(Collectors.toMap(
                        item -> item.get("userCode").toString().toUpperCase(),
                        item -> item,
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        for (Map<String, Object> currentMember : currentMembers) {
            Object userCodeValue = currentMember.get("userCode");
            if (userCodeValue == null) {
                continue;
            }
            String userCode = userCodeValue.toString().toUpperCase();
            Map<String, Object> previousMember = previousByUser.getOrDefault(userCode, Map.of());
            developerTrends.put(userCode, MetricComparison.compare(currentMember, previousMember, periods));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("periods", Map.of(
                "current", Map.of("since", since, "until", until),
                "previous", Map.of("since", previousSince, "until", previousUntil)));
        response.put("current", Map.of(
                "project", currentProject,
                "members", currentMembers,
                "commitTrend", List.of()));
        response.put("previous", Map.of(
                "project", previousProject,
                "members", previousMembers,
                "commitTrend", List.of()));
        response.put("members", members);
        response.put("projectTrends", MetricComparison.compare(currentProject, previousProject, periods));
        response.put("developerTrends", developerTrends);
        response.put("comparisonId", comparisonId == null || comparisonId.isBlank()
                ? UUID.randomUUID().toString()
                : comparisonId);
        response.put("deliveryTrend", demoMockDataService.shouldUseMockProject(projectId)
                ? buildDemoDeliveryTrend(currentProject, previousProject, periods)
                : combineDeliveryTrends(currentProject, previousProject));
        return response;
    }

    private List<Map<String, Object>> combineDeliveryTrends(
            Map<String, Object> current,
            Map<String, Object> previous) {
        List<Map<String, Object>> points = new ArrayList<>();
        appendDeliveryTrend(points, previous.get("deliveryTrend"), "previous");
        appendDeliveryTrend(points, current.get("deliveryTrend"), "current");
        return points;
    }

    private void appendDeliveryTrend(List<Map<String, Object>> points, Object source, String period) {
        if (!(source instanceof List<?> sourcePoints)) {
            return;
        }
        for (Object sourcePoint : sourcePoints) {
            if (!(sourcePoint instanceof Map<?, ?> sourceMap)) {
                continue;
            }
            Map<String, Object> point = new LinkedHashMap<>();
            sourceMap.forEach((key, value) -> point.put(String.valueOf(key), value));
            point.put("period", period);
            points.add(point);
        }
    }

    private Map<String, Object> buildDemoPrevious(Map<String, Object> current) {
        Set<String> lowerIsBetter = Set.of(
                mergedLeadTimeHours, bugCausedCount, bugFixLeadTimeHours,
                reviewWaitTime, contextSwitchFrequency);
        Set<String> countMetrics = Set.of(
                mergedCount, commitCount, issueCreatedCount, bugFoundCount, bugFixedCount,
                linesAdded, linesDeleted, linesTotal, reviewedCount, commentCount);
        Map<String, Object> previous = new LinkedHashMap<>();
        current.forEach((key, value) -> {
            if (!(value instanceof Number number)) {
                if (!"aiCorrected".equals(key) && !"aiEvaluations".equals(key)) {
                    previous.put(key, value);
                }
                return;
            }
            double numeric = number.doubleValue();
            double adjusted;
            if (key.endsWith("Score")) {
                adjusted = Math.max(0, numeric - 4.5);
            } else if (lowerIsBetter.contains(key)) {
                adjusted = numeric * 1.18;
            } else if (countMetrics.contains(key)) {
                adjusted = Math.max(0, Math.round(numeric * 0.82));
            } else {
                adjusted = numeric * 0.96;
            }
            previous.put(key, adjusted);
        });
        return previous;
    }

    private List<Map<String, Object>> buildDemoDeliveryTrend(
            Map<String, Object> current,
            Map<String, Object> previous,
            MetricComparison.Periods periods) {
        List<Map<String, Object>> points = new ArrayList<>();
        appendDemoDeliveryPoints(points, previous, periods.previous(), "previous");
        appendDemoDeliveryPoints(points, current, periods.current(), "current");
        return points;
    }

    private void appendDemoDeliveryPoints(
            List<Map<String, Object>> points,
            Map<String, Object> metrics,
            MetricComparison.Window window,
            String period) {
        int bucketDays = window.days() <= 45 ? 7 : 14;
        int bucketCount = (int) Math.ceil(window.days() / (double) bucketDays);
        int commits = numberAsInt(metrics.get(commitCount));
        int merges = numberAsInt(metrics.get(mergedCount));
        double leadTime = numberAsDouble(metrics.get(mergedLeadTimeHours));
        for (int index = 0; index < bucketCount; index++) {
            LocalDate bucketSince = window.since().plusDays((long) index * bucketDays);
            LocalDate bucketUntil = bucketSince.plusDays(bucketDays - 1L);
            if (bucketUntil.isAfter(window.until())) {
                bucketUntil = window.until();
            }
            int commitValue = distributedValue(commits, bucketCount, index);
            int mergeValue = distributedValue(merges, bucketCount, index);
            double variation = 1 + ((index % 3) - 1) * 0.08;
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("since", bucketSince.toString());
            point.put("until", bucketUntil.toString());
            point.put("period", period);
            point.put("commitCount", commitValue);
            point.put("mergedCount", mergeValue);
            point.put("averageLeadTimeHours", mergeValue == 0 ? null : leadTime * variation);
            point.put("medianLeadTimeHours", mergeValue == 0 ? null : leadTime * variation * 0.88);
            point.put("sampleCount", mergeValue);
            points.add(point);
        }
    }

    private int distributedValue(int total, int buckets, int index) {
        return total / buckets + (index < total % buckets ? 1 : 0);
    }

    private int numberAsInt(Object value) {
        return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
    }

    private double numberAsDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private Map<String, Object> calculateForUser(String projectId, String since, String until, String userName,
            String refName, List<JSONObject> allCreatedMRs, List<JSONObject> allUpdatedMRs, List<JSONObject> allMergedMRs,
            ZonedDateTime startRange, ZonedDateTime endRange,
            List<AiMrEvaluation> allAiEvaluations,
            Map<Integer, org.json.JSONArray> prefetchedNotesByMrIid,
            CommitMetricsResponseDTO prefetchedCommitMetrics,
            MergeRequestStatsDTO prefetchedUserMergeStats,
            MergeRequestStatsDTO prefetchedMergedStats,
            MergeRequestStatsDTO prefetchedReviewStats,
            IssueStatsDTO prefetchedIssueStats,
            boolean aiEnabled) {

        // 1. 各サービスから統計情報を取得
        CommitMetricsResponseDTO commitMetrics = prefetchedCommitMetrics != null
                ? prefetchedCommitMetrics
                : PerformanceTimingLog.time("gitlab.commits",
                        () -> commitService.getCommitCount(projectId, userName, since, until, refName));

        // ユーザー自身のMR統計を取得
        MergeRequestStatsDTO userMergeStats = prefetchedUserMergeStats != null
                ? prefetchedUserMergeStats
                : PerformanceTimingLog.time("gitlab.userMergeStats",
                        () -> mergeService.getMergeRequestStatsForUser(projectId, userName, since, until, refName,
                                allMergedMRs, allCreatedMRs, prefetchedNotesByMrIid));

        // 全MRリストからレビュー関連の統計を計算
        MergeRequestStatsDTO reviewStats = prefetchedReviewStats != null
                ? prefetchedReviewStats
                : PerformanceTimingLog.time("space.reviewStatsFromPrefetch",
                        () -> mergeService.calculateReviewStatsFromFetchedLists(projectId, userName,
                                allUpdatedMRs, startRange, endRange, prefetchedNotesByMrIid));

        MergeRequestStatsDTO mergedStats = prefetchedMergedStats != null
                ? prefetchedMergedStats
                : userMergeStats;

        // 2つのDTOをマージ
        MergeRequestStatsDTO mergeStats = new MergeRequestStatsDTO(
                userMergeStats.getCreatedCount(),
                userMergeStats.getCreatedAndMergedCount(),
                mergedStats.getMergedCount(),
                userMergeStats.getTotalCommentCount(), // 自分のMRについたコメント
                userMergeStats.getTotalReviewerCount(), // 自分のMRのレビュワー
                userMergeStats.getAvgHoursToMergeByCreated(),
                mergedStats.getAvgHoursToMergeByMerged(),
                userMergeStats.getAvgHoursToFirstReview(),
                reviewStats.getGivenReviewCount(),
                reviewStats.getGivenCommentCount());

        IssueStatsDTO issueStats = prefetchedIssueStats != null
                ? prefetchedIssueStats
                : PerformanceTimingLog.time("gitlab.issues",
                        () -> issueService.getIssueStats(projectId, since, until, userName, refName));

        // 2. 指標マップを作成
        Map<String, Number> metrics = new HashMap<>();

        double weeks = 1.0;
        if (since != null && until != null && !since.isEmpty() && !until.isEmpty()) {
            try {
                LocalDate startDate = LocalDate.parse(since, DateTimeFormatter.ISO_DATE);
                LocalDate endDate = LocalDate.parse(until, DateTimeFormatter.ISO_DATE);
                long days = ChronoUnit.DAYS.between(startDate, endDate) + 1;
                weeks = (days < 1) ? 1.0 / 7.0 : days / 7.0;
            } catch (Exception e) {
                log.warn("Failed to parse dates since={} until={}, defaulting weeks to 1.0. Error: {}",
                        since, until, e.getMessage());
            }
        }

        // Performance
        metrics.put(mergedCount, mergeStats.getMergedCount());
        metrics.put(mergedLeadTimeHours, sanitize(mergeStats.getAvgHoursToMergeByMerged()));
        metrics.put(bugCausedCount, issueStats.getBugCausedCount());
        metrics.put(bugFixLeadTimeHours, sanitize(issueStats.getAvgBugFixedHours()));

        // Activity
        metrics.put(commitCount, commitMetrics.getTotalCommitCount());
        metrics.put(issueCreatedCount, issueStats.getCreatedCount());
        metrics.put(bugFoundCount, issueStats.getBugFoundCount());
        metrics.put(bugFixedCount, issueStats.getBugFixedCount());

        CommitStatsDTO commitStats = commitMetrics.getCommitStats();
        metrics.put(linesAdded, commitStats.getLinesAdded());
        metrics.put(linesDeleted, commitStats.getLinesDeleted());
        metrics.put(linesTotal, commitStats.getLinesTotal());

        // Communication & Collaboration
        if (userName != null) {
            metrics.put(reviewedCount, sanitize(mergeStats.getGivenReviewCount()));
            metrics.put(commentCount, mergeStats.getGivenCommentCount()); // 自分が投稿したコメント数

            double avgCommentsPerMR = (mergeStats.getCreatedCount() > 0)
                    ? (double) mergeStats.getTotalCommentCount() / mergeStats.getCreatedCount()
                    : 0.0;
            metrics.put(reviewCommentCount, sanitize(avgCommentsPerMR)); // マージリクエスト1件あたりの平均コメント数
        } else {
            metrics.put(reviewedCount, sanitize(mergeStats.getTotalReviewerCount()));
            metrics.put(commentCount, mergeStats.getTotalCommentCount());

            double avgCommentsPerMR = (mergeStats.getCreatedCount() > 0)
                    ? (double) mergeStats.getTotalCommentCount() / mergeStats.getCreatedCount()
                    : 0.0;
            metrics.put(reviewCommentCount, sanitize(avgCommentsPerMR));
        }

        // Efficiency & Flow
        metrics.put(reviewWaitTime, sanitize(mergeStats.getAvgHoursToFirstReview()));
        final double scoringWeeks = weeks;

        // スコア計算 (Raw)
        Map<String, Object> results = PerformanceTimingLog.time("space.rawScoring",
                () -> scoringService.calculateScores(metrics, scoringWeeks));
        PerformanceTimingLog.time("db.satisfaction", () -> applyProjectSatisfactionScore(projectId, since, until, userName, results));
        if (userName == null || userName.isBlank()) {
            results.put("deliveryTrend", DeliveryTrendCalculator.calculate(commitMetrics, allMergedMRs));
        }

        // AI補正 (Decoupled)
        if (aiEnabled) {
        try {
            // ユーザーに関連する評価のみをフィルタリング
            List<AiMrEvaluation> userEvaluations = (userName != null)
                    ? allAiEvaluations.stream()
                            .filter(eval -> userName.equalsIgnoreCase(eval.getAuthorUsername()))
                            .collect(Collectors.toList())
                    : allAiEvaluations;

            Map<String, Number> correctedMetrics = PerformanceTimingLog.time("ai.correctMetrics",
                    () -> aiCorrectionService.getCorrectedMetrics(userEvaluations, metrics));
            Map<String, Object> aiResults = PerformanceTimingLog.time("space.aiCorrectedScoring",
                    () -> scoringService.calculateScores(correctedMetrics, scoringWeeks));
            PerformanceTimingLog.time("db.satisfactionAiCorrected", () -> applyProjectSatisfactionScore(projectId, since, until, userName, aiResults));

            log.info("AI Score Correction for target [{}]: Raw Score = {}, AI Corrected Score = {}",
                    userName != null ? userName : "Project" + projectId,
                    results.get(spaceTotalScore),
                    aiResults.get(spaceTotalScore));

            results.put("aiCorrected", aiResults);

            // AI評価の理由などを取得（オプション）
            results.put("aiEvaluations", userEvaluations);

        } catch (Exception e) {
            log.error("AI correction failed: {}", e.getMessage());
            // AI補正が失敗しても、Rawの結果は返す
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

    private Map<String, Object> calculateForUser(String projectId, String since, String until, String userName,
            String refName) {
        return calculateForUser(projectId, since, until, userName, refName, true);
    }

    private Map<String, Object> calculateForUser(String projectId, String since, String until, String userName,
            String refName, boolean aiEnabled) {
        List<JSONObject> allCreatedMRs = PerformanceTimingLog.time("gitlab.prefetchCreatedMRs",
                () -> mergeService.fetchAllCreatedMergeRequests(projectId, since, until, refName));
        PerformanceTimingLog.addCount("mrs.created", allCreatedMRs.size());
        List<JSONObject> allUpdatedMRs = PerformanceTimingLog.time("gitlab.prefetchUpdatedMRs",
                () -> mergeService.fetchAllUpdatedMergeRequests(projectId, since, until, refName));
        PerformanceTimingLog.addCount("mrs.updated", allUpdatedMRs.size());
        List<JSONObject> allMergedMRs = PerformanceTimingLog.time("gitlab.prefetchMergedMRs",
                () -> mergeService.fetchAllMergedMergeRequests(projectId, since, until, refName));
        PerformanceTimingLog.addCount("mrs.merged", allMergedMRs.size());
        List<JSONObject> noteTargetMRs = uniqueMergeRequestsByIid(allCreatedMRs, allUpdatedMRs);
        Map<Integer, org.json.JSONArray> notesByMrIid = PerformanceTimingLog.time("gitlab.prefetchMRNotes",
                () -> mergeService.fetchNotesByMergeRequest(projectId, noteTargetMRs));
        PerformanceTimingLog.addCount("mrs.notes.prefetched", notesByMrIid.size());
        // userName=null で全ユーザーの評価を取得
        List<AiMrEvaluation> allAiEvaluations = aiEnabled
                ? PerformanceTimingLog.time("ai.prefetchEvaluations",
                        () -> aiCorrectionService.findAnalyzedMRs(projectId, since, until, null, refName))
                : List.of();
        String apiSince = (since != null && since.length() == 10) ? since + "T00:00:00Z" : since;
        String apiUntil = (until != null && until.length() == 10) ? until + "T23:59:59Z" : until;
        ZonedDateTime startRange = (apiSince != null && !apiSince.isEmpty()) ? ZonedDateTime.parse(apiSince) : null;
        ZonedDateTime endRange = (apiUntil != null && !apiUntil.isEmpty()) ? ZonedDateTime.parse(apiUntil) : null;
        return calculateForUser(projectId, since, until, userName, refName,
                allCreatedMRs, allUpdatedMRs, allMergedMRs, startRange,
                endRange,
                allAiEvaluations,
                notesByMrIid,
                null,
                null,
                null,
                null,
                null,
                aiEnabled);
    }

    private List<JSONObject> uniqueMergeRequestsByIid(List<JSONObject> first, List<JSONObject> second) {
        List<JSONObject> result = new ArrayList<>();
        Set<Integer> seenIids = new HashSet<>();
        addUniqueMergeRequests(result, seenIids, first);
        addUniqueMergeRequests(result, seenIids, second);
        return result;
    }

    private void addUniqueMergeRequests(List<JSONObject> result, Set<Integer> seenIids, List<JSONObject> mergeRequests) {
        for (JSONObject mr : mergeRequests) {
            if (!mr.has("iid") || mr.isNull("iid") || seenIids.add(mr.getInt("iid"))) {
                result.add(mr);
            }
        }
    }

    private double sanitize(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return value;
    }
}
