package dev.productivity.signals.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiMrAnalysisBatchService {

    private final AiCorrectionService aiCorrectionService;

    @Value("${ai.mr-analysis.batch.enabled:false}")
    private boolean scheduledEnabled;

    @Value("${ai.mr-analysis.batch.project-ids:}")
    private String scheduledProjectIds;

    @Value("${ai.mr-analysis.batch.since:}")
    private String scheduledSince;

    @Value("${ai.mr-analysis.batch.until:}")
    private String scheduledUntil;

    @Value("${ai.mr-analysis.batch.ref-name:}")
    private String scheduledRefName;

    @Scheduled(cron = "${ai.mr-analysis.batch.cron:0 0 2 * * *}")
    public void runScheduledBatch() {
        if (!scheduledEnabled) {
            return;
        }

        try {
            runBatch(parseProjectIds(scheduledProjectIds), scheduledSince, scheduledUntil, scheduledRefName);
        } catch (Exception e) {
            log.error("Scheduled AI MR analysis batch failed: {}", e.getMessage(), e);
        }
    }

    public Map<String, Object> runBatch(List<String> projectIds, String since, String until, String refName) {
        validate(projectIds, since, until);

        List<Map<String, Object>> projectResults = projectIds.stream()
                .map(projectId -> aiCorrectionService.analyzeMissingMergedMRs(projectId, since, until, blankToNull(refName)))
                .toList();

        int totalTargets = sum(projectResults, "targetCount");
        int totalAnalyzed = sum(projectResults, "analyzedCount");
        int totalSkipped = sum(projectResults, "skippedCount");
        int totalFailed = sum(projectResults, "failedCount");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectIds", projectIds);
        result.put("since", since);
        result.put("until", until);
        result.put("refName", blankToNull(refName));
        result.put("projectCount", projectIds.size());
        result.put("targetCount", totalTargets);
        result.put("analyzedCount", totalAnalyzed);
        result.put("skippedCount", totalSkipped);
        result.put("failedCount", totalFailed);
        result.put("projectResults", projectResults);
        return result;
    }

    public List<String> parseProjectIds(String projectIds) {
        if (projectIds == null || projectIds.isBlank()) {
            return List.of();
        }
        return Arrays.stream(projectIds.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private void validate(List<String> projectIds, String since, String until) {
        if (projectIds == null || projectIds.isEmpty()) {
            throw new IllegalArgumentException("projectIds is required. Example: projectIds=100,200");
        }
        if (since == null || since.isBlank() || until == null || until.isBlank()) {
            throw new IllegalArgumentException("since and until are required. Example: since=2026-07-01&until=2026-07-31");
        }
        LocalDate.parse(since);
        LocalDate.parse(until);
    }

    private int sum(List<Map<String, Object>> results, String key) {
        return results.stream()
                .map(result -> result.get(key))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .mapToInt(Number::intValue)
                .sum();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
