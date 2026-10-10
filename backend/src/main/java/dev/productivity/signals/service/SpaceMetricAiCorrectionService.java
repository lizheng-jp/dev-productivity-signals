package dev.productivity.signals.service;

import dev.productivity.signals.entity.AiMrEvaluation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.productivity.signals.util.SpaceMetricConstants.*;

@Service
@RequiredArgsConstructor
public class SpaceMetricAiCorrectionService {

    private static final Set<String> RAW_METRIC_KEYS = Set.of(
            coreContributorCount,
            bugDataAvailable,
            mergedLeadTimeMedianHours,
            mergedCount,
            mergedLeadTimeHours,
            bugCausedCount,
            bugFixLeadTimeHours,
            commitCount,
            issueCreatedCount,
            bugFoundCount,
            bugFixedCount,
            linesAdded,
            linesDeleted,
            linesTotal,
            reviewedCount,
            commentCount,
            reviewWaitTime,
            uninterruptedFocusTimeHours,
            contextSwitchFrequency,
            reviewCommentCount);

    private static final List<String> SATISFACTION_KEYS = List.of(
            satisfactionSurveyScore,
            satisfactionResponseCount,
            satisfactionJobMeaning,
            satisfactionDeveloperEfficacy,
            satisfactionSustainability,
            satisfactionImprovementPotential,
            "satisfactionSurvey",
            satisfactionSource,
            contributorRetentionRate,
            retainedContributorCount,
            previousActiveContributorCount);

    private final AiCorrectionService aiCorrectionService;
    private final SpaceMetricScoringService scoringService;

    public Map<String, Object> correct(
            Map<String, Object> rawResults,
            String projectId,
            String since,
            String until,
            String userName,
            String refName) {
        List<AiMrEvaluation> evaluations = aiCorrectionService.findAnalyzedMRs(
                projectId, since, until, null, refName);
        return correct(rawResults, since, until, userName, evaluations);
    }

    public Map<String, Object> correct(
            Map<String, Object> rawResults,
            String since,
            String until,
            String userName,
            List<AiMrEvaluation> allEvaluations) {
        Map<String, Object> results = new HashMap<>(rawResults);
        results.remove("aiCorrected");
        results.remove("aiEvaluations");

        List<AiMrEvaluation> userEvaluations = userName == null || userName.isBlank()
                ? allEvaluations
                : allEvaluations.stream()
                        .filter(evaluation -> userName.equalsIgnoreCase(evaluation.getAuthorUsername()))
                        .toList();

        Map<String, Number> rawMetrics = extractRawMetrics(rawResults);
        Map<String, Number> correctedMetrics = aiCorrectionService.getCorrectedMetrics(userEvaluations, rawMetrics);
        Map<String, Object> correctedResults = scoringService.calculateScores(
                correctedMetrics, calculateWeeks(since, until));

        if (correctedResults != null) {
            copySatisfaction(rawResults, correctedResults);
            results.put("aiCorrected", correctedResults);
        }
        results.put("aiEvaluations", userEvaluations);
        return results;
    }

    private Map<String, Number> extractRawMetrics(Map<String, Object> rawResults) {
        Map<String, Number> metrics = new HashMap<>();
        RAW_METRIC_KEYS.forEach(key -> {
            Object value = rawResults.get(key);
            if (value instanceof Number number) {
                metrics.put(key, number);
            }
        });
        return metrics;
    }

    private void copySatisfaction(Map<String, Object> rawResults, Map<String, Object> correctedResults) {
        SATISFACTION_KEYS.forEach(key -> {
            if (rawResults.containsKey(key)) {
                correctedResults.put(key, rawResults.get(key));
            }
        });
        // Satisfaction is not AI-corrected; keep the raw score, whether it came from the survey or retention.
        Object satisfactionValue = rawResults.get(satisfactionSurveyScore);
        if (!(satisfactionValue instanceof Number) && "retention".equals(rawResults.get(satisfactionSource))) {
            satisfactionValue = rawResults.get(contributorRetentionRate);
        }
        if (satisfactionValue instanceof Number score) {
            scoringService.applyDimensionScoreOverride(correctedResults, "satisfaction", score.doubleValue());
        }
    }

    private double calculateWeeks(String since, String until) {
        if (since == null || until == null || since.isEmpty() || until.isEmpty()) {
            return 1.0;
        }
        try {
            LocalDate startDate = LocalDate.parse(since, DateTimeFormatter.ISO_DATE);
            LocalDate endDate = LocalDate.parse(until, DateTimeFormatter.ISO_DATE);
            long days = ChronoUnit.DAYS.between(startDate, endDate) + 1;
            return days < 1 ? 1.0 / 7.0 : days / 7.0;
        } catch (Exception ignored) {
            return 1.0;
        }
    }
}
