package dev.productivity.signals.service;

import org.json.JSONObject;

import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Picks a reproducible sample spread over a time window: items are grouped by creation month, each month gets
 * a share of the limit proportional to its size (at least one), and items are taken at even steps by number
 * within the month. The result interleaves months so a later cut-off still covers the whole window.
 */
public final class StratifiedSample {
    private StratifiedSample() {
    }

    public static List<JSONObject> select(List<JSONObject> items, int limit) {
        Map<YearMonth, List<JSONObject>> byMonth = new TreeMap<>();
        for (JSONObject item : items) {
            YearMonth month = YearMonth.from(ZonedDateTime.parse(item.getString("created_at")));
            byMonth.computeIfAbsent(month, ignored -> new ArrayList<>()).add(item);
        }
        byMonth.values().forEach(group -> group.sort(Comparator.comparingInt(item -> item.getInt("iid"))));
        Map<YearMonth, Integer> quotas = quotas(byMonth, Math.min(limit, items.size()));

        List<List<JSONObject>> picked = new ArrayList<>();
        byMonth.forEach((month, group) -> {
            int quota = quotas.get(month);
            List<JSONObject> chosen = new ArrayList<>();
            for (int index = 0; index < quota; index++) {
                chosen.add(group.get((int) ((long) index * group.size() / quota)));
            }
            picked.add(chosen);
        });
        List<JSONObject> result = new ArrayList<>();
        for (int round = 0; result.size() < quotas.values().stream().mapToInt(Integer::intValue).sum(); round++) {
            for (List<JSONObject> chosen : picked) {
                if (round < chosen.size()) result.add(chosen.get(round));
            }
        }
        return result;
    }

    private static Map<YearMonth, Integer> quotas(Map<YearMonth, List<JSONObject>> byMonth, int limit) {
        Map<YearMonth, Integer> quotas = new TreeMap<>();
        if (limit <= 0) {
            byMonth.keySet().forEach(month -> quotas.put(month, 0));
            return quotas;
        }
        int total = byMonth.values().stream().mapToInt(List::size).sum();
        byMonth.forEach((month, group) -> quotas.put(month,
                Math.min(group.size(), Math.max(1, (int) Math.round((double) limit * group.size() / total)))));
        // Rounding and the one-per-month floor can miss the limit; adjust the largest months first.
        int floor = byMonth.size() > limit ? 0 : 1;
        while (quotas.values().stream().mapToInt(Integer::intValue).sum() > limit) {
            YearMonth largest = quotas.entrySet().stream().filter(entry -> entry.getValue() > floor)
                    .max(Map.Entry.comparingByValue()).orElseThrow().getKey();
            quotas.merge(largest, -1, Integer::sum);
        }
        while (quotas.values().stream().mapToInt(Integer::intValue).sum() < limit) {
            YearMonth roomiest = quotas.entrySet().stream()
                    .filter(entry -> entry.getValue() < byMonth.get(entry.getKey()).size())
                    .max(Comparator.comparingInt(entry -> byMonth.get(entry.getKey()).size() - entry.getValue()))
                    .orElseThrow().getKey();
            quotas.merge(roomiest, 1, Integer::sum);
        }
        return quotas;
    }
}
