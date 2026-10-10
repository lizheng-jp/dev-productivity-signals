package dev.productivity.signals.service;

import dev.productivity.signals.dto.CommitMetricsResponseDTO;
import dev.productivity.signals.dto.CommitStatsDTO;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActiveMemberServiceTest {

    private static final String PROJECT = "github~octocat~Hello-World";

    @Test
    void countsOnlyContributorsWithRepeatedContributions() throws Exception {
        var git = mock(GitService.class);
        var commits = mock(CommitService.class);
        var merges = mock(MergeService.class);
        when(git.getAllProjectUserCodes(PROJECT)).thenReturn(List.of("alice", "bob", "carol", "dave"));
        when(commits.getCommitMetricsByUser(eq(PROJECT), any(), eq("2026-09-01"), eq("2026-09-30"), eq("main")))
                .thenReturn(Map.of(
                        "ALICE", commitCount(5), // regular committer
                        "BOB", commitCount(1),   // one squash-merged pull request: one commit and one merge
                        "CAROL", commitCount(0),
                        "DAVE", commitCount(1)));
        when(merges.fetchAllMergedMergeRequests(PROJECT, "2026-09-01", "2026-09-30", "main"))
                .thenReturn(List.of(mergedBy("bob"), mergedBy("carol"), mergedBy("carol")));

        var service = new ActiveMemberService(git, mock(GitHubRepositoryService.class), mock(GroupService.class), commits, merges);

        // alice (5 commits) and carol (2 merged PRs) are core; bob's single PR is not counted twice.
        assertThat(service.countCoreContributors(PROJECT, "2026-09-01", "2026-09-30", "main", 2)).isEqualTo(2);
    }

    @Test
    void countsAtLeastOneContributor() {
        var git = mock(GitService.class);
        var commits = mock(CommitService.class);
        var merges = mock(MergeService.class);
        when(git.getAllProjectUserCodes(PROJECT)).thenReturn(List.of("alice"));
        when(commits.getCommitMetricsByUser(eq(PROJECT), any(), any(), any(), any()))
                .thenReturn(Map.of("ALICE", commitCount(1)));
        when(merges.fetchAllMergedMergeRequests(any(), any(), any(), any())).thenReturn(List.of());

        var service = new ActiveMemberService(git, mock(GitHubRepositoryService.class), mock(GroupService.class), commits, merges);

        assertThat(service.countCoreContributors(PROJECT, "2026-09-01", "2026-09-30", "main", 2)).isEqualTo(1);
    }

    private static CommitMetricsResponseDTO commitCount(int count) {
        return new CommitMetricsResponseDTO(count, List.of(), new CommitStatsDTO(0, 0, 0));
    }

    private static JSONObject mergedBy(String author) throws Exception {
        return new JSONObject().put("author", new JSONObject().put("username", author));
    }
}
