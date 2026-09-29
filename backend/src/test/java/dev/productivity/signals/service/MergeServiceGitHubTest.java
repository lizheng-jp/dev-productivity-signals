package dev.productivity.signals.service;

import dev.productivity.signals.dto.MergeRequestStatsDTO;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MergeServiceGitHubTest {

    @Test
    void skipsReviewerDetailRequestsForProjectWideMetrics() throws Exception {
        GitHubRepositoryService github = mock(GitHubRepositoryService.class);
        MergeService service = new MergeService(
                mock(RestTemplate.class),
                mock(CommitService.class),
                github);

        MergeRequestStatsDTO result = service.calculateReviewStatsFromFetchedLists(
                "github~openai~openai-java",
                null,
                List.of(pullRequest(1), pullRequest(2)),
                null,
                null);

        assertThat(result.getGivenReviewCount()).isZero();
        assertThat(result.getGivenCommentCount()).isZero();
        verify(github, never()).getPullRequestNotes(anyString(), any(Integer.class));
    }

    @Test
    void limitsProjectWideGitHubReviewDetails() throws Exception {
        GitHubRepositoryService github = mock(GitHubRepositoryService.class);
        when(github.supports("github~openai~openai-java")).thenReturn(true);
        when(github.getReviewDetailLimit()).thenReturn(1);
        when(github.getPullRequests(anyString(), anyString(), anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(pullRequest(1), pullRequest(2)));
        when(github.getPullRequestNotes(anyString(), any(Integer.class))).thenReturn(new JSONArray());

        MergeService service = new MergeService(
                mock(RestTemplate.class),
                mock(CommitService.class),
                github);

        MergeRequestStatsDTO result = service.getMergeRequestStatsForUser(
                "github~openai~openai-java",
                null,
                "2026-08-27",
                "2026-09-26",
                "main",
                List.of());

        assertThat(result.getCreatedCount()).isEqualTo(2);
        verify(github).getPullRequestNotes("github~openai~openai-java", 1);
        verify(github, never()).getPullRequestNotes("github~openai~openai-java", 2);
    }

    @Test
    void calculatesFromPrefetchedNotesWithoutMoreRequests() throws Exception {
        GitHubRepositoryService github = mock(GitHubRepositoryService.class);
        when(github.supports("github~openai~openai-java")).thenReturn(true);
        when(github.getReviewDetailLimit()).thenReturn(1);
        when(github.getPullRequestNotesBatch(anyString(), any())).thenReturn(Map.of(1, new JSONArray()));
        MergeService service = new MergeService(
                mock(RestTemplate.class), mock(CommitService.class), github);
        List<JSONObject> created = List.of(pullRequest(1), pullRequest(2));

        Map<Integer, JSONArray> notes = service.fetchNotesByMergeRequest(
                "github~openai~openai-java", created);
        MergeRequestStatsDTO result = service.getMergeRequestStatsForUser(
                "github~openai~openai-java", null, "2026-09-01", "2026-09-07", "main",
                List.of(), created, notes);

        assertThat(result.getCreatedCount()).isEqualTo(2);
        assertThat(notes).containsOnlyKeys(1, 2);
        verify(github, never()).getPullRequestNotes(anyString(), any(Integer.class));
        verify(github, never()).getPullRequests(anyString(), anyString(), anyString(), any(),
                anyString(), anyString(), anyString());
    }

    private JSONObject pullRequest(int iid) throws Exception {
        return new JSONObject()
                .put("iid", iid)
                .put("created_at", "2026-09-01T00:00:00Z")
                .put("updated_at", "2026-09-02T00:00:00Z")
                .put("merged_at", JSONObject.NULL)
                .put("author", new JSONObject().put("username", "developer-" + iid));
    }
}
