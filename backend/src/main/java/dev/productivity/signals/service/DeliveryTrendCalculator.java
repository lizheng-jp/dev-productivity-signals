package dev.productivity.signals.service;

import dev.productivity.signals.dto.CommitMetricsResponseDTO;
import dev.productivity.signals.dto.WeeklyBreakdownDTO;
import org.json.JSONObject;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DeliveryTrendCalculator {

    private DeliveryTrendCalculator() {
    }

    public static List<Map<String, Object>> calculate(
            CommitMetricsResponseDTO commitMetrics,
            List<JSONObject> mergedMergeRequests) {
        if (commitMetrics == null || commitMetrics.getWeeklyBreakdown() == null) {
            return List.of();
        }

        List<MergeObservation> merges = parseMerges(mergedMergeRequests);
        List<Map<String, Object>> points = new ArrayList<>();
        for (WeeklyBreakdownDTO week : commitMetrics.getWeeklyBreakdown()) {
            LocalDate since = LocalDate.parse(week.getWeekStart());
            LocalDate until = LocalDate.parse(week.getWeekEnd());
            List<Double> leadTimes = merges.stream()
                    .filter(merge -> !merge.mergedDate().isBefore(since) && !merge.mergedDate().isAfter(until))
                    .map(MergeObservation::leadTimeHours)
                    .filter(value -> value != null)
                    .sorted(Comparator.naturalOrder())
                    .toList();
            long mergedCount = merges.stream()
                    .filter(merge -> !merge.mergedDate().isBefore(since) && !merge.mergedDate().isAfter(until))
                    .count();

            Map<String, Object> point = new LinkedHashMap<>();
            point.put("since", week.getWeekStart());
            point.put("until", week.getWeekEnd());
            point.put("commitCount", week.getCount());
            point.put("mergedCount", mergedCount);
            point.put("averageLeadTimeHours", average(leadTimes));
            point.put("medianLeadTimeHours", median(leadTimes));
            point.put("sampleCount", leadTimes.size());
            points.add(point);
        }
        return points;
    }

    /**
     * Median hours from creation to merge for merge requests merged within [start, end],
     * limited to one author when {@code authorUsername} is given. Returns null without samples.
     */
    public static Double medianLeadTimeHours(
            List<JSONObject> mergedMergeRequests,
            ZonedDateTime start,
            ZonedDateTime end,
            String authorUsername) {
        if (mergedMergeRequests == null) {
            return null;
        }
        List<JSONObject> selected = mergedMergeRequests.stream()
                .filter(mergeRequest -> authorUsername == null || authorUsername.isBlank()
                        || authorUsername.equalsIgnoreCase(authorOf(mergeRequest)))
                .toList();
        List<Double> leadTimes = parseMerges(selected).stream()
                .filter(merge -> merge.leadTimeHours() != null)
                .filter(merge -> start == null || !merge.mergedAt().isBefore(start))
                .filter(merge -> end == null || !merge.mergedAt().isAfter(end))
                .map(MergeObservation::leadTimeHours)
                .sorted(Comparator.naturalOrder())
                .toList();
        return median(leadTimes);
    }

    private static String authorOf(JSONObject mergeRequest) {
        JSONObject author = mergeRequest.optJSONObject("author");
        return author == null ? "" : author.optString("username", "");
    }

    private static List<MergeObservation> parseMerges(List<JSONObject> mergeRequests) {
        if (mergeRequests == null) {
            return List.of();
        }
        List<MergeObservation> observations = new ArrayList<>();
        for (JSONObject mergeRequest : mergeRequests) {
            try {
                ZonedDateTime mergedAt = ZonedDateTime.parse(mergeRequest.getString("merged_at"));
                Double leadTimeHours = null;
                if (mergeRequest.has("created_at") && !mergeRequest.isNull("created_at")) {
                    ZonedDateTime createdAt = ZonedDateTime.parse(mergeRequest.getString("created_at"));
                    double value = Duration.between(createdAt, mergedAt).toSeconds() / 3600.0;
                    if (value >= 0 && Double.isFinite(value)) {
                        leadTimeHours = value;
                    }
                }
                observations.add(new MergeObservation(mergedAt, leadTimeHours));
            } catch (Exception ignored) {
                // Ignore malformed or incomplete upstream records without hiding the rest of the trend.
            }
        }
        return observations;
    }

    private static Double average(List<Double> values) {
        return values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static Double median(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        int middle = values.size() / 2;
        return values.size() % 2 == 0
                ? (values.get(middle - 1) + values.get(middle)) / 2.0
                : values.get(middle);
    }

    private record MergeObservation(ZonedDateTime mergedAt, Double leadTimeHours) {
        LocalDate mergedDate() {
            return mergedAt.toLocalDate();
        }
    }
}
