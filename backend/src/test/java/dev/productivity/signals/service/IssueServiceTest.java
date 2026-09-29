package dev.productivity.signals.service;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IssueServiceTest {
    private static final String PROJECT = "github~octocat~Hello-World";
    private static final String SINCE = "2026-09-01";
    private static final String UNTIL = "2026-09-07";

    @Test
    void countsJapaneseAndEnglishBugLabelsWithoutCountingDebug() throws Exception {
        GitHubRepositoryService github = mock(GitHubRepositoryService.class);
        IssueService service = new IssueService(mock(RestTemplate.class), github);
        when(github.supports(PROJECT)).thenReturn(true);
        when(github.getIssues(PROJECT, "all", SINCE + "T00:00:00Z")).thenReturn(List.of(
                issue(1, "バグ"), issue(2, "Bug"), issue(3, "type: bug"),
                issue(4, "debug"), issue(5, "bug-fix")));

        var single = service.getIssueStats(PROJECT, SINCE, UNTIL, "alice", "main");
        var members = service.getIssueStatsByUser(PROJECT, SINCE, UNTIL, List.of("alice"), "main");

        assertThat(single.getCreatedCount()).isEqualTo(5);
        assertThat(single.getBugFoundCount()).isEqualTo(4);
        assertThat(single.getBugCausedCount()).isEqualTo(4);
        assertThat(members.get("ALICE").getBugFoundCount()).isEqualTo(4);
        assertThat(members.get("ALICE").getBugCausedCount()).isEqualTo(4);
    }

    private JSONObject issue(int number, String label) throws Exception {
        JSONObject alice = new JSONObject().put("username", "alice");
        return new JSONObject().put("iid", number)
                .put("created_at", "2026-09-03T10:00:00Z")
                .put("author", alice).put("assignee", alice)
                .put("labels", new JSONArray().put(label));
    }
}
