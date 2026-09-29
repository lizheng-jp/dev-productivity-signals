package dev.productivity.signals.controller;

import dev.productivity.signals.entity.AiMrEvaluation;
import dev.productivity.signals.repository.AiMrEvaluationRepository;
import dev.productivity.signals.service.GitHubRepositoryService;
import dev.productivity.signals.service.MergeService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentEvidenceControllerTest {
    private static final String PROJECT = "github~octocat~Hello-World";
    private static final String KEY = "test-internal-key";
    private final GitHubRepositoryService github = mock(GitHubRepositoryService.class);
    private final AiMrEvaluationRepository evaluations = mock(AiMrEvaluationRepository.class);
    private final MergeService mergeService = mock(MergeService.class);
    private final AgentEvidenceController controller = new AgentEvidenceController(github, evaluations, mergeService);
    private final LocalDate since = LocalDate.now().minusDays(7);
    private final LocalDate until = LocalDate.now();

    AgentEvidenceControllerTest() {
        ReflectionTestUtils.setField(controller, "internalKey", KEY);
    }

    @Test
    void requiresInternalKeyBeforeGitHubAccess() {
        assertThatThrownBy(() -> controller.listMergeRequests(PROJECT, since, until, "main", null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void leadDistributionUsesEqualLengthPreviousPeriodAndRequiresKey() throws Exception {
        assertThatThrownBy(() -> controller.mergeLeadDistribution(PROJECT, since, until, "main", null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        JSONObject current = new JSONObject().put("iid", 17)
                .put("created_at", since + "T00:00:00Z")
                .put("merged_at", until + "T00:00:00Z");
        LocalDate previousSince = since.minusDays(8);
        LocalDate previousUntil = since.minusDays(1);
        when(mergeService.fetchAllMergedMergeRequests(PROJECT, since.toString(), until.toString(), "main"))
                .thenReturn(List.of(current));
        when(mergeService.fetchAllMergedMergeRequests(PROJECT, previousSince.toString(),
                previousUntil.toString(), "main")).thenReturn(List.of());

        var result = controller.mergeLeadDistribution(PROJECT, since, until, "main", KEY);

        @SuppressWarnings("unchecked")
        var currentSummary = (java.util.Map<String, Object>) result.get("current");
        assertThat(currentSummary).containsEntry("sampleCount", 1);
        @SuppressWarnings("unchecked")
        var previousSummary = (java.util.Map<String, Object>) result.get("previous");
        assertThat(previousSummary).containsEntry("sampleCount", 0).containsEntry("meanHours", null);
    }

    @Test
    void listReturnsBoundedSummaryAndCoverageFlag() throws Exception {
        JSONObject mr = new JSONObject().put("iid", 17).put("title", "Fix parser")
                .put("state", "merged").put("author", new JSONObject().put("username", "octocat"))
                .put("created_at", since + "T10:00:00Z").put("updated_at", until + "T10:00:00Z")
                .put("merged_at", until + "T09:00:00Z").put("source_branch", "fix")
                .put("target_branch", "main").put("web_url", "https://github.com/octocat/Hello-World/pull/17")
                .put("raw_response", "must not leak");
        when(github.getRecentPullRequestsPage(PROJECT, since.toString(), until.toString(), "main"))
                .thenReturn(new GitHubRepositoryService.PullRequestPage(List.of(mr), true));

        var result = controller.listMergeRequests(PROJECT, since, until, "main", KEY);

        assertThat(result.get("truncated")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        var items = (List<java.util.Map<String, Object>>) result.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("title", "Fix parser").doesNotContainKey("raw_response");
    }

    @Test
    void detailIncludesOnlySavedAnalysisAndSelectedPeriod() throws Exception {
        JSONObject mr = new JSONObject().put("iid", 17).put("title", "Fix parser")
                .put("state", "merged").put("author", new JSONObject().put("username", "octocat"))
                .put("created_at", since + "T10:00:00Z").put("updated_at", until + "T10:00:00Z")
                .put("merged_at", until + "T09:00:00Z").put("source_branch", "fix")
                .put("target_branch", "main").put("web_url", "https://github.com/octocat/Hello-World/pull/17")
                .put("additions", 12).put("deletions", 3).put("changedFiles", 2);
        AiMrEvaluation evaluation = new AiMrEvaluation();
        evaluation.setComplexity("medium");
        evaluation.setReasoning("Existing analysis");
        evaluation.setRawResponse("must not leak");
        when(github.getPullRequestDetail(PROJECT, 17)).thenReturn(mr);
        when(evaluations.findFirstByProjectIdAndMrIidOrderByAnalyzedAtDesc(PROJECT, 17))
                .thenReturn(Optional.of(evaluation));

        var detail = controller.getMergeRequest(PROJECT, 17, since, until, "main", KEY);

        assertThat(detail).containsEntry("changedFiles", 2).doesNotContainKey("rawResponse");
        @SuppressWarnings("unchecked")
        var analysis = (java.util.Map<String, Object>) detail.get("analysis");
        assertThat(analysis).containsEntry("complexity", "medium").doesNotContainKey("rawResponse");
        verify(evaluations).findFirstByProjectIdAndMrIidOrderByAnalyzedAtDesc(PROJECT, 17);
    }

    @Test
    void detailRejectsMrOutsideSelectedPeriod() throws Exception {
        JSONObject mr = new JSONObject().put("iid", 17)
                .put("updated_at", since.minusDays(1) + "T10:00:00Z");
        when(github.getPullRequestDetail(PROJECT, 17)).thenReturn(mr);

        assertThatThrownBy(() -> controller.getMergeRequest(PROJECT, 17, since, until, "main", KEY))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void indexFeedReturnsOnlyGitHubTextAndDoesNotReadSavedAiAnalysis() throws Exception {
        JSONObject mr = new JSONObject().put("iid", 17).put("title", "Review delay")
                .put("body", "Waiting for maintainer approval")
                .put("author", new JSONObject().put("username", "octocat"))
                .put("created_at", since + "T10:00:00Z").put("updated_at", until + "T10:00:00Z")
                .put("web_url", "https://github.com/octocat/Hello-World/pull/17");
        JSONObject issue = new JSONObject().put("iid", 23).put("title", "CI failure")
                .put("body", "Pipeline is slow")
                .put("author", new JSONObject().put("username", "maintainer"))
                .put("created_at", since + "T10:00:00Z").put("updated_at", until + "T10:00:00Z")
                .put("web_url", "https://github.com/octocat/Hello-World/issues/23")
                .put("labels", new JSONArray().put("performance")).put("comments", 1);
        JSONObject mrNote = new JSONObject().put("id", 3).put("body", "Review queue is long")
                .put("author", new JSONObject().put("username", "reviewer"))
                .put("created_at", until + "T12:00:00Z")
                .put("web_url", "https://github.com/octocat/Hello-World/pull/17#issuecomment-3");
        JSONObject review = new JSONObject().put("id", 3).put("review", true)
                .put("body", "Needs changes before approval")
                .put("created_at", until + "T11:00:00Z")
                .put("web_url", "https://github.com/octocat/Hello-World/pull/17#pullrequestreview-3");
        JSONObject oldNote = new JSONObject().put("id", 4).put("body", "Old unrelated note")
                .put("created_at", since.minusDays(1) + "T12:00:00Z");
        JSONObject issueNote = new JSONObject().put("id", 5).put("body", "Builds need cache")
                .put("created_at", until + "T12:00:00Z")
                .put("web_url", "https://github.com/octocat/Hello-World/issues/23#issuecomment-5");
        when(github.getRecentPullRequestsPage(PROJECT, since.toString(), until.toString(), "main"))
                .thenReturn(new GitHubRepositoryService.PullRequestPage(List.of(mr), false));
        when(github.getRecentIssuesPage(PROJECT, since.toString(), until.toString()))
                .thenReturn(new GitHubRepositoryService.IssuePage(List.of(issue), false));
        when(github.getRecentPullRequestNotes(PROJECT, 17))
                .thenReturn(new JSONArray().put(mrNote).put(review).put(oldNote));
        when(github.getRecentIssueComments(PROJECT, 23))
                .thenReturn(new JSONArray().put(issueNote));

        var feed = controller.indexDocuments(PROJECT, since, until, "main", null, KEY);

        @SuppressWarnings("unchecked")
        var documents = (List<java.util.Map<String, Object>>) feed.get("documents");
        assertThat(documents).hasSize(5);
        assertThat(documents).extracting(item -> item.get("sourceType"))
                .containsExactlyInAnyOrder("mr_description", "mr_discussion", "mr_discussion",
                        "issue", "issue_comment");
        assertThat(documents).extracting(item -> item.get("sourceId"))
                .contains("mr:17:comment:3", "mr:17:review:3");
        assertThat(documents).allSatisfy(item -> {
            assertThat(item.get("projectId")).isEqualTo(PROJECT);
            assertThat(item.get("url").toString()).startsWith("https://github.com/");
            assertThat(item).doesNotContainKey("analysis");
        });
        verifyNoInteractions(evaluations);
    }

    @Test
    void indexFeedIgnoresBotCommentsBeforeApplyingCommentLimit() throws Exception {
        JSONObject mr = new JSONObject().put("iid", 17).put("title", "Review delay")
                .put("created_at", since + "T10:00:00Z").put("updated_at", until + "T10:00:00Z")
                .put("web_url", "https://github.com/octocat/Hello-World/pull/17");
        JSONArray notes = new JSONArray().put(new JSONObject().put("id", 1)
                .put("body", "Human review was delayed by CI")
                .put("author", new JSONObject().put("username", "reviewer").put("bot", false))
                .put("created_at", since + "T11:00:00Z")
                .put("web_url", "https://github.com/octocat/Hello-World/pull/17#issuecomment-1"));
        for (int i = 2; i <= 10; i++) {
            notes.put(new JSONObject().put("id", i).put("body", "Automated review completed")
                    .put("author", new JSONObject().put("username", "reviewer[bot]").put("bot", true))
                    .put("created_at", until + "T12:00:00Z")
                    .put("web_url", "https://github.com/octocat/Hello-World/pull/17#issuecomment-" + i));
        }
        when(github.getRecentPullRequestsPage(PROJECT, since.toString(), until.toString(), "main"))
                .thenReturn(new GitHubRepositoryService.PullRequestPage(List.of(mr), false));
        when(github.getRecentIssuesPage(PROJECT, since.toString(), until.toString()))
                .thenReturn(new GitHubRepositoryService.IssuePage(List.of(), false));
        when(github.getRecentPullRequestNotes(PROJECT, 17)).thenReturn(notes);

        var feed = controller.indexDocuments(PROJECT, since, until, "main", null, KEY);

        @SuppressWarnings("unchecked")
        var documents = (List<java.util.Map<String, Object>>) feed.get("documents");
        assertThat(documents).filteredOn(item -> item.get("sourceType").equals("mr_discussion"))
                .extracting(item -> item.get("sourceId")).containsExactly("mr:17:comment:1");
        assertThat(feed.get("truncated")).isEqualTo(false);
    }

    @Test
    void indexFeedDoesNotIndexBotAuthoredDescription() throws Exception {
        JSONObject mr = new JSONObject().put("iid", 17).put("title", "Automated release")
                .put("body", "Generated release summary")
                .put("author", new JSONObject().put("username", "release[bot]").put("bot", true))
                .put("created_at", since + "T10:00:00Z").put("updated_at", until + "T10:00:00Z")
                .put("web_url", "https://github.com/octocat/Hello-World/pull/17");
        when(github.getRecentPullRequestsPage(PROJECT, since.toString(), until.toString(), "main"))
                .thenReturn(new GitHubRepositoryService.PullRequestPage(List.of(mr), false));
        when(github.getRecentIssuesPage(PROJECT, since.toString(), until.toString()))
                .thenReturn(new GitHubRepositoryService.IssuePage(List.of(), false));
        when(github.getRecentPullRequestNotes(PROJECT, 17)).thenReturn(new JSONArray());

        var feed = controller.indexDocuments(PROJECT, since, until, "main", null, KEY);

        assertThat((List<?>) feed.get("documents")).isEmpty();
    }

    @Test
    void indexFeedIncludesLongLeadTimePullRequestAmongRecentOnes() throws Exception {
        List<JSONObject> pulls = new java.util.ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            pulls.add(new JSONObject().put("iid", i).put("title", "Recent PR " + i)
                    .put("created_at", until.minusDays(1) + "T08:00:00Z")
                    .put("merged_at", until + "T08:00:00Z")
                    .put("updated_at", until + "T09:00:00Z")
                    .put("web_url", "https://github.com/octocat/Hello-World/pull/" + i));
        }
        pulls.add(new JSONObject().put("iid", 99).put("title", "Long review")
                .put("body", "Waiting for compatibility testing")
                .put("created_at", since.minusDays(20) + "T08:00:00Z")
                .put("merged_at", since + "T08:00:00Z")
                .put("updated_at", since + "T09:00:00Z")
                .put("web_url", "https://github.com/octocat/Hello-World/pull/99"));
        when(github.getRecentPullRequestsPage(PROJECT, since.toString(), until.toString(), "main"))
                .thenReturn(new GitHubRepositoryService.PullRequestPage(pulls, false));
        when(github.getRecentIssuesPage(PROJECT, since.toString(), until.toString()))
                .thenReturn(new GitHubRepositoryService.IssuePage(List.of(), false));
        when(github.getRecentPullRequestNotes(eq(PROJECT), anyInt())).thenReturn(new JSONArray());

        var feed = controller.indexDocuments(PROJECT, since, until, "main", null, KEY);

        @SuppressWarnings("unchecked")
        var documents = (List<java.util.Map<String, Object>>) feed.get("documents");
        assertThat(documents).extracting(item -> item.get("sourceId"))
                .contains("mr:99:description");
        assertThat(feed.get("truncated")).isEqualTo(true);
        verify(github).getRecentPullRequestNotes(PROJECT, 99);
    }

    @Test
    void indexFeedRejectsMissingKeyBeforeFetchingGitHub() {
        assertThatThrownBy(() -> controller.indexDocuments(PROJECT, since, until, "main", null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(github);
    }

    @Test
    void incrementalFeedSkipsCommentsForUnchangedPullRequests() throws Exception {
        JSONObject mr = new JSONObject().put("iid", 17).put("title", "Old review")
                .put("body", "Already indexed")
                .put("updated_at", since + "T10:00:00Z")
                .put("web_url", "https://github.com/octocat/Hello-World/pull/17");
        when(github.getRecentPullRequestsPage(PROJECT, since.toString(), until.toString(), "main"))
                .thenReturn(new GitHubRepositoryService.PullRequestPage(List.of(mr), false));
        when(github.getRecentIssuesPage(PROJECT, since.toString(), until.toString()))
                .thenReturn(new GitHubRepositoryService.IssuePage(List.of(), false));

        var feed = controller.indexDocuments(PROJECT, since, until, "main",
                OffsetDateTime.parse(until + "T00:00:00Z"), KEY);

        assertThat((List<?>) feed.get("documents")).isEmpty();
        verify(github, never()).getRecentPullRequestNotes(PROJECT, 17);
    }

    @Test
    void recentCommentDoesNotMakeOldIssueDescriptionRecentEvidence() throws Exception {
        JSONObject issue = new JSONObject().put("iid", 23).put("title", "Old CI failure")
                .put("body", "Historical issue description")
                .put("created_at", since.minusDays(30) + "T10:00:00Z")
                .put("updated_at", until + "T10:00:00Z")
                .put("web_url", "https://github.com/octocat/Hello-World/issues/23")
                .put("comments", 1);
        JSONObject comment = new JSONObject().put("id", 5).put("body", "Current status update")
                .put("created_at", until + "T09:00:00Z")
                .put("web_url", "https://github.com/octocat/Hello-World/issues/23#issuecomment-5");
        when(github.getRecentPullRequestsPage(PROJECT, since.toString(), until.toString(), "main"))
                .thenReturn(new GitHubRepositoryService.PullRequestPage(List.of(), false));
        when(github.getRecentIssuesPage(PROJECT, since.toString(), until.toString()))
                .thenReturn(new GitHubRepositoryService.IssuePage(List.of(issue), false));
        when(github.getRecentIssueComments(PROJECT, 23)).thenReturn(new JSONArray().put(comment));

        var feed = controller.indexDocuments(PROJECT, since, until, "main", null, KEY);

        @SuppressWarnings("unchecked")
        var documents = (List<java.util.Map<String, Object>>) feed.get("documents");
        assertThat(documents).hasSize(1);
        assertThat(documents.get(0)).containsEntry("sourceType", "issue_comment")
                .containsEntry("eventAt", until + "T09:00:00Z");
    }
}
