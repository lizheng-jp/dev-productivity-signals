package dev.productivity.signals.service;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MergeLeadTimeDistributionTest {
    @Test
    void summarizesLongTailAndExcludesOtherPeriods() throws Exception {
        LocalDate day = LocalDate.parse("2026-09-27");
        var merged = List.of(pr(1, "2026-09-26T00:00:00Z", "2026-09-27T00:00:00Z"),
                pr(2, "2026-09-17T00:00:00Z", "2026-09-27T00:00:00Z"),
                pr(3, "2026-09-27T00:00:00Z", "2026-09-27T01:00:00Z"),
                pr(4, "2026-09-20T00:00:00Z", "2026-09-21T00:00:00Z"));

        var result = MergeLeadTimeDistribution.summarize(merged, day, day);

        assertThat(result).containsEntry("sampleCount", 3).containsEntry("meanHours", 265.0 / 3)
                .containsEntry("p50Hours", 24.0).containsEntry("p90Hours", 240.0)
                .containsEntry("topFiveSharePercent", 100.0);
        @SuppressWarnings("unchecked")
        var slowest = (List<java.util.Map<String, Object>>) result.get("slowest");
        assertThat(slowest.get(0)).containsEntry("iid", 2)
                .containsEntry("contributionToMeanHours", 80.0);
    }

    private JSONObject pr(int iid, String created, String merged) throws Exception {
        return new JSONObject().put("iid", iid).put("created_at", created).put("merged_at", merged)
                .put("web_url", "https://github.com/example/project/pull/" + iid);
    }
}
