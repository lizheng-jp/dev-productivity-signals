package dev.productivity.signals.service;

import org.json.JSONObject;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MergeLeadTimeDistribution {
    private MergeLeadTimeDistribution() {
    }

    public static Map<String, Object> summarize(List<JSONObject> merged, LocalDate since, LocalDate until) {
        List<LeadTime> items = new ArrayList<>();
        for (JSONObject mr : merged) {
            try {
                OffsetDateTime created = OffsetDateTime.parse(mr.getString("created_at"));
                OffsetDateTime mergedAt = OffsetDateTime.parse(mr.getString("merged_at"));
                LocalDate mergedDay = mergedAt.toLocalDate();
                long seconds = Duration.between(created, mergedAt).toSeconds();
                if (seconds < 0 || mergedDay.isBefore(since) || mergedDay.isAfter(until)) continue;
                items.add(new LeadTime(mr.optInt("iid"), mr.optString("web_url", ""),
                        created.toString(), mergedAt.toString(), seconds / 3600.0));
            } catch (RuntimeException ignored) {
                // The distribution uses only PRs with valid creation and merge timestamps.
            }
        }
        items.sort(Comparator.comparingDouble(LeadTime::hours).reversed());
        double totalHours = items.stream().mapToDouble(LeadTime::hours).sum();
        List<Double> ascending = items.stream().map(LeadTime::hours).sorted().toList();
        List<Map<String, Object>> slowest = new ArrayList<>();
        for (LeadTime item : items.stream().limit(5).toList()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("iid", item.iid());
            row.put("url", item.url());
            row.put("createdAt", item.createdAt());
            row.put("mergedAt", item.mergedAt());
            row.put("leadHours", item.hours());
            row.put("contributionToMeanHours", item.hours() / items.size());
            slowest.add(row);
        }
        double topFiveHours = items.stream().limit(5).mapToDouble(LeadTime::hours).sum();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sampleCount", items.size());
        result.put("meanHours", items.isEmpty() ? null : totalHours / items.size());
        result.put("p50Hours", percentile(ascending, 0.50));
        result.put("p90Hours", percentile(ascending, 0.90));
        result.put("topFiveSharePercent", totalHours == 0 ? null : topFiveHours / totalHours * 100);
        result.put("slowest", slowest);
        return result;
    }

    private static Double percentile(List<Double> ascending, double fraction) {
        return ascending.isEmpty() ? null : ascending.get((int) Math.ceil(fraction * ascending.size()) - 1);
    }

    private record LeadTime(int iid, String url, String createdAt, String mergedAt, double hours) {
    }
}
