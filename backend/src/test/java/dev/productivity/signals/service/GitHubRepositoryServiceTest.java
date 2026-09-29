package dev.productivity.signals.service;

import dev.productivity.signals.dto.ProjectDTO;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class GitHubRepositoryServiceTest {

    private GitHubRepositoryService service;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        service = new GitHubRepositoryService(restTemplate);
        ReflectionTestUtils.setField(service, "apiBase", "https://api.github.test");
        ReflectionTestUtils.setField(service, "apiVersion", "2026-03-10");
        ReflectionTestUtils.setField(service, "configuredToken", "test-token");
        ReflectionTestUtils.setField(service, "maxPages", 2);
        ReflectionTestUtils.setField(service, "maxCommitDetails", 0);
        ReflectionTestUtils.setField(service, "maxAnonymousCommitDetails", 0);
        ReflectionTestUtils.setField(service, "maxReviewDetails", 100);
        ReflectionTestUtils.setField(service, "maxAnonymousReviewDetails", 10);
    }

    @Test
    void resolvesRepositoryAndBuildsStableProjectId() {
        server.expect(once(), requestTo("https://api.github.test/repos/octocat/Hello-World"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(header("X-GitHub-Api-Version", "2026-03-10"))
                .andRespond(withSuccess("""
                        {
                          "id": 1296269,
                          "name": "Hello-World",
                          "full_name": "octocat/Hello-World",
                          "description": "Example repository",
                          "default_branch": "main",
                          "html_url": "https://github.com/octocat/Hello-World"
                        }
                        """, MediaType.APPLICATION_JSON));

        ProjectDTO project = service.resolveProject("https://github.com/octocat/Hello-World.git");

        assertThat(project.getId()).isEqualTo("github~octocat~Hello-World");
        assertThat(project.getProvider()).isEqualTo("github");
        assertThat(project.getDefaultBranch()).isEqualTo("main");
        assertThat(project.getFullName()).isEqualTo("octocat/Hello-World");
        server.verify();
    }

    @Test
    void agentMrListReadsOnlyFirstPage() throws Exception {
        server.expect(once(), requestTo(
                        "https://api.github.test/repos/octocat/Hello-World/pulls?state=all&sort=updated&direction=desc&per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [{"id":1,"number":17,"title":"Fix parser","body":"Review blocked on API design","state":"closed",
                          "created_at":"2026-09-21T10:00:00Z","updated_at":"2026-09-22T10:00:00Z",
                          "merged_at":"2026-09-22T09:00:00Z","head":{"ref":"fix"},
                          "base":{"ref":"main"},"user":{"login":"octocat"}}]
                        """, MediaType.APPLICATION_JSON));

        var page = service.getRecentPullRequestsPage(
                "github~octocat~Hello-World", "2026-09-20", "2026-09-26", "main");

        assertThat(page.items()).hasSize(1);
        assertThat(page.hasMoreInPeriod()).isFalse();
        assertThat(page.items().get(0).getInt("iid")).isEqualTo(17);
        assertThat(page.items().get(0).getString("body")).isEqualTo("Review blocked on API design");
        server.verify();
    }

    @Test
    void recentIssuePageExcludesPullRequestsAndKeepsDescription() throws Exception {
        server.expect(once(), requestTo(
                        "https://api.github.test/repos/octocat/Hello-World/issues?state=all&sort=updated&direction=desc&per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [
                          {"number":23,"title":"Slow CI","body":"Build queue is long",
                           "created_at":"2026-09-20T10:00:00Z","updated_at":"2026-09-22T10:00:00Z",
                           "html_url":"https://github.com/octocat/Hello-World/issues/23",
                           "user":{"login":"octocat"},"labels":[{"name":"performance"}]},
                          {"number":17,"updated_at":"2026-09-22T10:00:00Z","pull_request":{}}
                        ]
                        """, MediaType.APPLICATION_JSON));

        var page = service.getRecentIssuesPage("github~octocat~Hello-World", "2026-09-20", "2026-09-26");

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).getString("title")).isEqualTo("Slow CI");
        assertThat(page.items().get(0).getString("body")).isEqualTo("Build queue is long");
        assertThat(page.items().get(0).getString("web_url"))
                .isEqualTo("https://github.com/octocat/Hello-World/issues/23");
        server.verify();
    }

    @Test
    void reusesSuccessfulResponsesForTheSameCredentialAndPath() {
        server.expect(once(), requestTo("https://api.github.test/repos/octocat/Hello-World"))
                .andRespond(withSuccess("""
                        {
                          "name": "Hello-World",
                          "full_name": "octocat/Hello-World",
                          "default_branch": "main"
                        }
                        """, MediaType.APPLICATION_JSON));

        ProjectDTO first = service.resolveProject("https://github.com/octocat/Hello-World");
        ProjectDTO second = service.resolveProject("https://github.com/octocat/Hello-World");

        assertThat(first.getId()).isEqualTo("github~octocat~Hello-World");
        assertThat(second.getId()).isEqualTo(first.getId());
        server.verify();
    }

    @Test
    void normalizesCommitIdentityAndStatsForSharedScoring() throws Exception {
        server.expect(once(), requestTo(
                        "https://api.github.test/repos/octocat/Hello-World/commits?per_page=100&page=1&sha=main&since=2026-09-01T00%3A00%3A00Z&until=2026-09-30T23%3A59%3A59Z"))
                .andRespond(withSuccess("""
                        [{
                          "sha": "0123456789abcdef",
                          "author": {"login": "octocat"},
                          "committer": {"login": "octocat"},
                          "commit": {
                            "author": {"name": "Octo Cat", "email": "octo@example.invalid", "date": "2026-09-10T10:00:00Z"},
                            "committer": {"name": "Octo Cat", "email": "octo@example.invalid", "date": "2026-09-10T10:00:00Z"}
                          }
                        }]
                        """, MediaType.APPLICATION_JSON));

        List<JSONObject> commits = service.getCommits(
                "github~octocat~Hello-World",
                "2026-09-01T00:00:00Z",
                "2026-09-30T23:59:59Z",
                "main");

        assertThat(commits).hasSize(1);
        JSONObject commit = commits.get(0);
        assertThat(commit.getString("author_name")).isEqualTo("octocat");
        assertThat(commit.getString("committed_date")).isEqualTo("2026-09-10T10:00:00Z");
        assertThat(commit.getJSONObject("stats").getInt("total")).isZero();
        server.verify();
    }

    @Test
    void batchesCommitStatsWithoutPerCommitRestRequests() throws Exception {
        ReflectionTestUtils.setField(service, "maxCommitDetails", 2);
        server.expect(once(), requestTo(
                        "https://api.github.test/repos/octocat/Hello-World/commits?per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [
                          {"sha":"sha-one","node_id":"node-one","commit":{"author":{"date":"2026-09-01T00:00:00Z"},"committer":{"date":"2026-09-01T00:00:00Z"}}},
                          {"sha":"sha-two","node_id":"node-two","commit":{"author":{"date":"2026-09-02T00:00:00Z"},"committer":{"date":"2026-09-02T00:00:00Z"}}}
                        ]
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.github.test/graphql"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"data":{"nodes":[
                          {"oid":"sha-one","additions":3,"deletions":1},
                          {"oid":"sha-two","additions":5,"deletions":2}
                        ]}}
                        """, MediaType.APPLICATION_JSON));

        List<JSONObject> commits = service.getCommits("github~octocat~Hello-World", null, null, null);

        assertThat(commits).hasSize(2);
        assertThat(commits.get(0).getJSONObject("stats").getInt("total")).isEqualTo(4);
        assertThat(commits.get(1).getJSONObject("stats").getInt("total")).isEqualTo(7);
        server.verify();
    }

    @Test
    void parsesHttpsAndSshRepositoryUrls() {
        GitHubRepositoryService.RepositoryCoordinates https = service.parseRepositoryUrl(
                "https://github.com/openai/openai-java");
        GitHubRepositoryService.RepositoryCoordinates ssh = service.parseRepositoryUrl(
                "git@github.com:openai/openai-java.git");

        assertThat(https.owner()).isEqualTo("openai");
        assertThat(https.repo()).isEqualTo("openai-java");
        assertThat(ssh).isEqualTo(https);
        assertThat(service.fromProjectId("github~openai~openai-java")).isEqualTo(https);
    }

    @Test
    void normalizesPullRequestsForExistingMergeCalculations() throws Exception {
        server.expect(once(), requestTo(
                        "https://api.github.test/repos/octocat/Hello-World/pulls?state=closed&sort=updated&direction=desc&per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [{
                          "id": 1001,
                          "number": 42,
                          "state": "closed",
                          "title": "Improve scoring",
                          "created_at": "2026-09-02T10:00:00Z",
                          "updated_at": "2026-09-04T12:00:00Z",
                          "merged_at": "2026-09-04T12:00:00Z",
                          "html_url": "https://github.com/octocat/Hello-World/pull/42",
                          "user": {"login": "octocat", "type": "User"},
                          "head": {"ref": "feature/scoring"},
                          "base": {"ref": "main"}
                        }]
                        """, MediaType.APPLICATION_JSON));

        List<JSONObject> pulls = service.getPullRequests(
                "github~octocat~Hello-World",
                "2026-09-01",
                "2026-09-30",
                "octocat",
                "main",
                "merged",
                "merged");

        assertThat(pulls).hasSize(1);
        assertThat(pulls.get(0).getInt("iid")).isEqualTo(42);
        assertThat(pulls.get(0).getJSONObject("author").getString("username")).isEqualTo("octocat");
        assertThat(pulls.get(0).getString("target_branch")).isEqualTo("main");
        server.verify();
    }

    @Test
    void batchesPullRequestCommentsAndReviewsInOneGraphQlRequest() throws Exception {
        server.expect(once(), requestTo("https://api.github.test/graphql"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess("""
                        {"data":{"repository":{
                          "p0":{"comments":{"nodes":[{"databaseId":11,"createdAt":"2026-09-02T10:00:00Z","author":{"login":"reviewer","__typename":"User"}}],"pageInfo":{"hasNextPage":false}},
                                "reviews":{"nodes":[{"databaseId":12,"submittedAt":"2026-09-02T11:00:00Z","state":"APPROVED","author":{"login":"reviewer","__typename":"User"}}],"pageInfo":{"hasNextPage":false}}},
                          "p1":{"comments":{"nodes":[],"pageInfo":{"hasNextPage":false}},
                                "reviews":{"nodes":[],"pageInfo":{"hasNextPage":false}}}
                        }}}
                        """, MediaType.APPLICATION_JSON));

        var notes = service.getPullRequestNotesBatch("github~octocat~Hello-World", List.of(1, 2));

        assertThat(notes).containsOnlyKeys(1, 2);
        assertThat(notes.get(1).length()).isEqualTo(2);
        assertThat(notes.get(1).getJSONObject(0).getJSONObject("author").getString("username"))
                .isEqualTo("reviewer");
        assertThat(notes.get(1).getJSONObject(1).getBoolean("review")).isTrue();
        assertThat(notes.get(2).length()).isZero();
        server.verify();
    }

    @Test
    void fallsBackToPaginatedRestForOverflowingPullRequest() {
        server.expect(once(), requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":{"repository":{"p0":{
                          "comments":{"nodes":[],"pageInfo":{"hasNextPage":true}},
                          "reviews":{"nodes":[],"pageInfo":{"hasNextPage":false}}
                        }}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(
                        "https://api.github.test/repos/octocat/Hello-World/issues/1/comments?per_page=100&page=1"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(
                        "https://api.github.test/repos/octocat/Hello-World/pulls/1/reviews?per_page=100&page=1"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        var notes = service.getPullRequestNotesBatch("github~octocat~Hello-World", List.of(1));

        assertThat(notes.get(1).length()).isZero();
        server.verify();
    }

    @Test
    void excludesPullRequestsFromNormalizedIssues() throws Exception {
        server.expect(once(), requestTo(
                        "https://api.github.test/repos/octocat/Hello-World/issues?state=all&sort=updated&direction=desc&per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [
                          {
                            "id": 2001,
                            "number": 7,
                            "state": "closed",
                            "created_at": "2026-09-05T10:00:00Z",
                            "updated_at": "2026-09-06T10:00:00Z",
                            "closed_at": "2026-09-06T10:00:00Z",
                            "user": {"login": "octocat"},
                            "assignee": {"login": "maintainer"},
                            "assignees": [{"login": "maintainer"}],
                            "labels": [{"name": "bug"}]
                          },
                          {
                            "id": 2002,
                            "number": 8,
                            "pull_request": {"url": "https://api.github.test/pulls/8"}
                          }
                        ]
                        """, MediaType.APPLICATION_JSON));

        List<JSONObject> issues = service.getIssues("github~octocat~Hello-World", "all");

        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).getJSONArray("labels").getString(0)).isEqualTo("bug");
        assertThat(issues.get(0).getJSONObject("assignee").getString("username")).isEqualTo("maintainer");
        server.verify();
    }

    @Test
    void stopsCallingGitHubDuringRateLimitBackoff() {
        server.expect(once(), requestTo("https://api.github.test/rate_limit"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, "60")
                        .body("{\"message\":\"secondary rate limit exceeded\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(service::getRateLimit)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(429));
        assertThatThrownBy(service::getRateLimit)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(429));

        server.verify();
    }

    @Test
    void doesNotTreatOrdinaryForbiddenResponseAsRateLimit() {
        server.expect(once(), requestTo("https://api.github.test/repos/octocat/private-repository"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .body("{\"message\":\"Resource not accessible by personal access token\"}")
                        .contentType(MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.github.test/rate_limit"))
                .andRespond(withSuccess("""
                        {"resources":{"core":{"limit":5000,"remaining":4999,"reset":1780000000}}}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.resolveProject("https://github.com/octocat/private-repository"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(403));
        assertThat(service.getRateLimit()).containsEntry("remaining", 4999);

        server.verify();
    }
}
