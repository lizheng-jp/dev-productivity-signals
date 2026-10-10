package dev.productivity.signals.service;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StratifiedSampleTest {
    private static JSONObject item(int number, String created) throws Exception {
        return new JSONObject().put("iid", number).put("created_at", created + "T00:00:00Z");
    }

    @Test
    void allocatesByMonthSizeWithAtLeastOnePerMonth() throws Exception {
        List<JSONObject> items = new ArrayList<>();
        for (int day = 1; day <= 30; day++) items.add(item(day, String.format("2026-09-%02d", day)));
        items.add(item(100, "2026-07-20"));
        for (int day = 1; day <= 9; day++) items.add(item(200 + day, String.format("2026-08-%02d", day)));

        List<JSONObject> sample = StratifiedSample.select(items, 8);

        assertThat(sample).hasSize(8);
        assertThat(sample).filteredOn(item -> item.optString("created_at").startsWith("2026-07")).hasSize(1);
        assertThat(sample).filteredOn(item -> item.optString("created_at").startsWith("2026-08")).hasSize(2);
        assertThat(sample).filteredOn(item -> item.optString("created_at").startsWith("2026-09")).hasSize(5);
        // Interleaved: the first three picks already cover all three months.
        assertThat(sample.subList(0, 3)).extracting(item -> item.optString("created_at").substring(0, 7))
                .containsExactly("2026-07", "2026-08", "2026-09");
    }

    @Test
    void isDeterministicAndKeepsEverythingUnderTheLimit() throws Exception {
        List<JSONObject> items = List.of(item(3, "2026-09-01"), item(1, "2026-09-02"), item(2, "2026-08-01"));
        assertThat(StratifiedSample.select(items, 10)).hasSize(3);
        assertThat(StratifiedSample.select(items, 2)).extracting(item -> item.optInt("iid"))
                .isEqualTo(StratifiedSample.select(items, 2).stream().map(item -> item.optInt("iid")).toList());
    }

    @Test
    void moreMonthsThanSlotsStillFitsTheLimit() throws Exception {
        List<JSONObject> items = List.of(item(1, "2026-06-01"), item(2, "2026-07-01"), item(3, "2026-08-01"));
        assertThat(StratifiedSample.select(items, 2)).hasSize(2);
        assertThat(StratifiedSample.select(items, 0)).isEmpty();
    }
}
