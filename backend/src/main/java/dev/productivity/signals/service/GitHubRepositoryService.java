package dev.productivity.signals.service;

import dev.productivity.signals.dto.BranchDTO;
import dev.productivity.signals.dto.ProjectDTO;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.productivity.signals.util.PerformanceTimingLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class GitHubRepositoryService {

    public static final String PROJECT_PREFIX = "github~";

    private static final Pattern HTTPS_REPOSITORY = Pattern.compile(
            "^https?://github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?/?$");
    private static final Pattern SSH_REPOSITORY = Pattern.compile(
            "^git@github\\.com:([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?$");
    private static final String ANONYMOUS_CREDENTIAL = "<anonymous>";
    private static final long DEFAULT_RATE_LIMIT_BACKOFF_MILLIS = 60_000L;
    private static final int MAX_CONCURRENT_REQUESTS = 8;

    private final RestTemplate restTemplate;
    private final Map<String, Long> blockedUntilByCredential = new ConcurrentHashMap<>();
    private final Semaphore requestSlots = new Semaphore(MAX_CONCURRENT_REQUESTS, true);
    private final Cache<GitHubRequestCacheKey, String> responseCache = Caffeine.newBuilder()
            .maximumSize(2_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    @Value("${github.api.url:https://api.github.com}")
    private String apiBase;

    @Value("${github.api.version:2026-03-10}")
    private String apiVersion;

    @Value("${github.api.token:}")
    private String configuredToken;

    @Value("${github.api.max-pages:10}")
    private int maxPages;

    @Value("${github.api.max-commit-details:100}")
    private int maxCommitDetails;

    @Value("${github.api.max-anonymous-commit-details:20}")
    private int maxAnonymousCommitDetails;

    @Value("${github.api.max-review-details:100}")
    private int maxReviewDetails;

    @Value("${github.api.max-anonymous-review-details:10}")
    private int maxAnonymousReviewDetails;

    public boolean supports(String projectId) {
        return projectId != null && projectId.startsWith(PROJECT_PREFIX);
    }

    public int getReviewDetailLimit() {
        return currentToken().isBlank() ? maxAnonymousReviewDetails : maxReviewDetails;
    }

    public ProjectDTO resolveProject(String repositoryUrl) {
        RepositoryCoordinates repository = parseRepositoryUrl(repositoryUrl);
        JSONObject source = getObject(repository.path());

        ProjectDTO project = new ProjectDTO();
        project.setId(toProjectId(repository));
        project.setName(source.optString("name", repository.repo()));
        project.setDescription(source.optString("description", ""));
        project.setDefaultBranch(source.optString("default_branch", "main"));
        project.setProvider("github");
        project.setFullName(source.optString("full_name", repository.owner() + "/" + repository.repo()));
        project.setWebUrl(source.optString("html_url", "https://github.com/" + repository.owner() + "/" + repository.repo()));
        return project;
    }

    public Map<String, Object> getRateLimit() {
        JSONObject response = getObject("/rate_limit");
        JSONObject core = response.optJSONObject("resources") == null
                ? null
                : response.getJSONObject("resources").optJSONObject("core");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authenticated", !currentToken().isBlank());
        if (core != null) {
            result.put("limit", core.optInt("limit"));
            result.put("remaining", core.optInt("remaining"));
            result.put("reset", core.optLong("reset"));
        }
        return result;
    }

    public List<String> getProjectMembers(String projectId) {
        RepositoryCoordinates repository = fromProjectId(projectId);
        Set<String> members = new LinkedHashSet<>();
        for (int page = 1; page <= maxPages; page++) {
            JSONArray contributors = getArray(repository.path() + "/contributors?per_page=100&page=" + page);
            for (int i = 0; i < contributors.length(); i++) {
                JSONObject contributor = contributors.getJSONObject(i);
                String login = contributor.optString("login", "");
                if (!login.isBlank() && !isBot(contributor)) {
                    members.add(login);
                }
            }
            if (contributors.length() < 100) {
                break;
            }
        }
        if (members.isEmpty()) {
            JSONObject repo = getObject(repository.path());
            JSONObject owner = repo.optJSONObject("owner");
            if (owner != null && !owner.optString("login", "").isBlank()) {
                members.add(owner.getString("login"));
            }
        }
        return new ArrayList<>(members);
    }

    public List<BranchDTO> getBranches(String projectId) {
        RepositoryCoordinates repository = fromProjectId(projectId);
        JSONObject repo = getObject(repository.path());
        String defaultBranch = repo.optString("default_branch", "main");
        List<BranchDTO> result = new ArrayList<>();

        for (int page = 1; page <= maxPages; page++) {
            JSONArray branches = getArray(repository.path() + "/branches?per_page=100&page=" + page);
            for (int i = 0; i < branches.length(); i++) {
                JSONObject source = branches.getJSONObject(i);
                BranchDTO branch = new BranchDTO();
                String name = source.optString("name", "");
                branch.setName(name);
                branch.setMerged(false);
                branch.setIsProtected(source.optBoolean("protected", false));
                branch.setIsDefault(defaultBranch.equals(name));
                branch.setWebUrl("https://github.com/" + repository.owner() + "/" + repository.repo() + "/tree/" + encode(name));
                result.add(branch);
            }
            if (branches.length() < 100) {
                break;
            }
        }
        return result;
    }

    public List<JSONObject> getCommits(String projectId, String since, String until, String refName) {
        RepositoryCoordinates repository = fromProjectId(projectId);
        List<JSONObject> sourceCommits = new ArrayList<>();
        List<JSONObject> result = new ArrayList<>();
        int detailCount = 0;
        int detailLimit = currentToken().isBlank() ? maxAnonymousCommitDetails : maxCommitDetails;

        for (int page = 1; page <= maxPages; page++) {
            StringBuilder path = new StringBuilder(repository.path())
                    .append("/commits?per_page=100&page=").append(page);
            appendQuery(path, "sha", refName);
            appendQuery(path, "since", since);
            appendQuery(path, "until", until);

            JSONArray commits = getArray(path.toString());
            for (int i = 0; i < commits.length(); i++) {
                sourceCommits.add(commits.getJSONObject(i));
            }
            if (commits.length() < 100) {
                break;
            }
        }
        Map<String, JSONObject> statsBySha = currentToken().isBlank()
                ? Map.of() : getCommitStatsBatch(sourceCommits, detailLimit);
        for (JSONObject source : sourceCommits) {
            JSONObject detail = source;
            if (detailCount < detailLimit) {
                String sha = source.optString("sha", "");
                if (!sha.isBlank()) {
                    JSONObject stats = statsBySha.get(sha);
                    detail = stats == null
                            ? getObject(repository.path() + "/commits/" + encode(sha))
                            : source.put("stats", stats);
                    detailCount++;
                }
            }
            result.add(normalizeCommit(detail));
        }
        return result;
    }

    private Map<String, JSONObject> getCommitStatsBatch(List<JSONObject> commits, int detailLimit) {
        List<String> nodeIds = new ArrayList<>();
        int selected = 0;
        for (JSONObject commit : commits) {
            if (selected >= detailLimit) break;
            if (commit.optString("sha", "").isBlank()) continue;
            selected++;
            String nodeId = commit.optString("node_id", "");
            if (!nodeId.isBlank()) nodeIds.add(nodeId);
        }
        Map<String, JSONObject> statsBySha = new LinkedHashMap<>();
        String token = currentToken();
        for (int offset = 0; offset < nodeIds.size(); offset += 50) {
            List<String> batch = nodeIds.subList(offset, Math.min(offset + 50, nodeIds.size()));
            String query = "query { nodes(ids:" + new JSONArray(batch)
                    + ") { ... on Commit { oid additions deletions } } }";
            JSONArray nodes = graphQl(query, token, "commits").getJSONArray("nodes");
            for (int index = 0; index < nodes.length(); index++) {
                JSONObject node = nodes.optJSONObject(index);
                if (node == null || !node.has("oid")) continue;
                int additions = node.getInt("additions");
                int deletions = node.getInt("deletions");
                statsBySha.put(node.getString("oid"), new JSONObject()
                        .put("additions", additions)
                        .put("deletions", deletions)
                        .put("total", additions + deletions));
            }
        }
        return statsBySha;
    }

    public List<JSONObject> getPullRequests(String projectId, String since, String until, String authorUserName,
            String refName, String filterType, String state) {
        RepositoryCoordinates repository = fromProjectId(projectId);
        List<JSONObject> result = new ArrayList<>();
        String githubState = "merged".equals(state) ? "closed" : "all";
        ZonedDateTime start = parseDate(since, false);
        ZonedDateTime end = parseDate(until, true);

        for (int page = 1; page <= maxPages; page++) {
            String path = repository.path() + "/pulls?state=" + githubState
                    + "&sort=updated&direction=desc&per_page=100&page=" + page;
            JSONArray pulls = getArray(path);
            for (int i = 0; i < pulls.length(); i++) {
                JSONObject source = pulls.getJSONObject(i);
                JSONObject normalized = normalizePullRequest(source);
                if (!matchesPullRequest(normalized, start, end, authorUserName, refName, filterType, state)) {
                    continue;
                }
                result.add(normalized);
            }
            if (pulls.length() < 100) {
                break;
            }
            if (start != null && ZonedDateTime.parse(pulls.getJSONObject(pulls.length() - 1)
                    .getString("updated_at")).isBefore(start)) {
                break;
            }
        }
        return result;
    }

    public PullRequestPage getRecentPullRequestsPage(String projectId, String since, String until, String refName) {
        RepositoryCoordinates repository = fromProjectId(projectId);
        JSONArray page = getArray(repository.path() + "/pulls?state=all&sort=updated&direction=desc&per_page=100&page=1");
        ZonedDateTime start = parseDate(since, false);
        ZonedDateTime end = parseDate(until, true);
        List<JSONObject> matches = new ArrayList<>();
        for (int index = 0; index < page.length(); index++) {
            JSONObject source = page.getJSONObject(index);
            JSONObject pull = normalizePullRequest(source);
            if (matchesPullRequest(pull, start, end, null, refName, "updated", "all")) {
                pull.put("body", source.optString("body", ""));
                matches.add(pull);
            }
        }
        boolean moreInPeriod = page.length() == 100 && (start == null
                || !ZonedDateTime.parse(page.getJSONObject(page.length() - 1).getString("updated_at")).isBefore(start));
        return new PullRequestPage(matches, moreInPeriod);
    }

    /** Every PR created in the window, newest first, read page by page up to the configured page limit. */
    public PullRequestPage getPullRequestsCreatedIn(String projectId, String since, String until, String refName) {
        RepositoryCoordinates repository = fromProjectId(projectId);
        ZonedDateTime start = parseDate(since, false);
        ZonedDateTime end = parseDate(until, true);
        List<JSONObject> matches = new ArrayList<>();
        for (int page = 1; page <= maxPages; page++) {
            JSONArray pulls = getArray(repository.path()
                    + "/pulls?state=all&sort=created&direction=desc&per_page=100&page=" + page);
            for (int index = 0; index < pulls.length(); index++) {
                JSONObject source = pulls.getJSONObject(index);
                JSONObject pull = normalizePullRequest(source);
                if (matchesPullRequest(pull, start, end, null, refName, "created", "all")) {
                    pull.put("body", source.optString("body", ""));
                    matches.add(pull);
                }
            }
            if (pulls.length() < 100 || ZonedDateTime.parse(pulls.getJSONObject(pulls.length() - 1)
                    .getString("created_at")).isBefore(start)) {
                return new PullRequestPage(matches, false);
            }
        }
        return new PullRequestPage(matches, true);
    }

    /** Every issue (not PR) created in the window, read page by page up to the configured page limit. */
    public IssuePage getIssuesCreatedIn(String projectId, String since, String until) {
        RepositoryCoordinates repository = fromProjectId(projectId);
        ZonedDateTime start = parseDate(since, false);
        ZonedDateTime end = parseDate(until, true);
        List<JSONObject> matches = new ArrayList<>();
        for (int page = 1; page <= maxPages; page++) {
            JSONArray items = getArray(repository.path()
                    + "/issues?state=all&sort=created&direction=desc&per_page=100&page=" + page);
            for (int index = 0; index < items.length(); index++) {
                JSONObject source = items.getJSONObject(index);
                ZonedDateTime created = ZonedDateTime.parse(source.getString("created_at"));
                if (source.has("pull_request") || created.isBefore(start) || created.isAfter(end)) continue;
                matches.add(issueWithDiscussionFields(source));
            }
            if (items.length() < 100 || ZonedDateTime.parse(items.getJSONObject(items.length() - 1)
                    .getString("created_at")).isBefore(start)) {
                return new IssuePage(matches, false);
            }
        }
        return new IssuePage(matches, true);
    }

    public JSONObject getPullRequestDetail(String projectId, int pullNumber) {
        RepositoryCoordinates repository = fromProjectId(projectId);
        JSONObject source = getObject(repository.path() + "/pulls/" + pullNumber);
        JSONObject result = normalizePullRequest(source);
        result.put("additions", source.optInt("additions"));
        result.put("deletions", source.optInt("deletions"));
        result.put("changedFiles", source.optInt("changed_files"));
        return result;
    }

    public record PullRequestPage(List<JSONObject> items, boolean hasMoreInPeriod) {
    }

    public IssuePage getRecentIssuesPage(String projectId, String since, String until) {
        RepositoryCoordinates repository = fromProjectId(projectId);
        JSONArray page = getArray(repository.path() + "/issues?state=all&sort=updated&direction=desc&per_page=100&page=1");
        ZonedDateTime start = parseDate(since, false);
        ZonedDateTime end = parseDate(until, true);
        List<JSONObject> matches = new ArrayList<>();
        for (int index = 0; index < page.length(); index++) {
            JSONObject source = page.getJSONObject(index);
            if (source.has("pull_request")) continue;
            ZonedDateTime updated = ZonedDateTime.parse(source.getString("updated_at"));
            if (updated.isBefore(start) || updated.isAfter(end)) continue;
            matches.add(issueWithDiscussionFields(source));
        }
        boolean moreInPeriod = page.length() == 100 &&
                !ZonedDateTime.parse(page.getJSONObject(99).getString("updated_at")).isBefore(start);
        return new IssuePage(matches, moreInPeriod);
    }

    public record IssuePage(List<JSONObject> items, boolean hasMoreInPeriod) {
    }

    private JSONObject issueWithDiscussionFields(JSONObject source) {
        JSONObject issue = normalizeIssue(source);
        issue.put("title", source.optString("title", ""));
        issue.put("body", source.optString("body", ""));
        issue.put("comments", source.optInt("comments", 0));
        issue.put("web_url", source.optString("html_url", ""));
        return issue;
    }

    public JSONArray getRecentPullRequestNotes(String projectId, int pullNumber) {
        RepositoryCoordinates repository = fromProjectId(projectId);
        JSONArray notes = new JSONArray();
        appendNotes(notes, repository.path() + "/issues/" + pullNumber + "/comments", false, 1);
        appendNotes(notes, repository.path() + "/pulls/" + pullNumber + "/reviews", true, 1);
        return notes;
    }

    public JSONArray getRecentIssueComments(String projectId, int issueNumber) {
        RepositoryCoordinates repository = fromProjectId(projectId);
        JSONArray notes = new JSONArray();
        appendNotes(notes, repository.path() + "/issues/" + issueNumber + "/comments", false, 1);
        return notes;
    }

    public JSONArray getPullRequestNotes(String projectId, int pullNumber) {
        RepositoryCoordinates repository = fromProjectId(projectId);
        JSONArray notes = new JSONArray();
        appendNotes(notes, repository.path() + "/issues/" + pullNumber + "/comments", false);
        appendNotes(notes, repository.path() + "/pulls/" + pullNumber + "/reviews", true);
        return notes;
    }

    public Map<Integer, JSONArray> getPullRequestNotesBatch(String projectId, List<Integer> pullNumbers) {
        Map<Integer, JSONArray> result = new LinkedHashMap<>();
        if (pullNumbers.isEmpty()) {
            return result;
        }
        String token = currentToken();
        if (token.isBlank()) {
            for (int number : pullNumbers) {
                result.put(number, getPullRequestNotes(projectId, number));
            }
            return result;
        }

        RepositoryCoordinates repository = fromProjectId(projectId);
        for (int offset = 0; offset < pullNumbers.size(); offset += 10) {
            List<Integer> batch = pullNumbers.subList(offset, Math.min(offset + 10, pullNumbers.size()));
            StringBuilder query = new StringBuilder("query { repository(owner:")
                    .append(JSONObject.quote(repository.owner())).append(",name:")
                    .append(JSONObject.quote(repository.repo())).append(") {");
            for (int index = 0; index < batch.size(); index++) {
                query.append(" p").append(index).append(":pullRequest(number:").append(batch.get(index))
                        .append(") { comments(first:50) { nodes { databaseId createdAt author { login __typename } } ")
                        .append("pageInfo { hasNextPage } } reviews(first:50) { nodes { databaseId submittedAt ")
                        .append("state author { login __typename } } pageInfo { hasNextPage } } }");
            }
            JSONObject repositoryData = graphQl(query.append(" } }").toString(), token, "notes")
                    .optJSONObject("repository");
            if (repositoryData == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub GraphQL repository is unavailable");
            }
            for (int index = 0; index < batch.size(); index++) {
                int number = batch.get(index);
                JSONObject pull = repositoryData.optJSONObject("p" + index);
                if (pull == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub GraphQL pull request is unavailable");
                }
                JSONObject comments = pull.getJSONObject("comments");
                JSONObject reviews = pull.getJSONObject("reviews");
                if (comments.getJSONObject("pageInfo").getBoolean("hasNextPage")
                        || reviews.getJSONObject("pageInfo").getBoolean("hasNextPage")) {
                    result.put(number, getPullRequestNotes(projectId, number));
                    continue;
                }
                JSONArray notes = new JSONArray();
                appendGraphQlNotes(notes, comments.getJSONArray("nodes"), false);
                appendGraphQlNotes(notes, reviews.getJSONArray("nodes"), true);
                result.put(number, notes);
            }
        }
        return result;
    }

    private void appendGraphQlNotes(JSONArray notes, JSONArray nodes, boolean review) {
        for (int index = 0; index < nodes.length(); index++) {
            JSONObject source = nodes.optJSONObject(index);
            if (source == null || (review && "PENDING".equalsIgnoreCase(source.optString("state")))) {
                continue;
            }
            String createdAt = source.optString(review ? "submittedAt" : "createdAt", "");
            if (createdAt.isBlank()) {
                continue;
            }
            JSONObject actor = source.optJSONObject("author");
            JSONObject user = actor == null ? null : new JSONObject()
                    .put("login", actor.optString("login", ""))
                    .put("type", actor.optString("__typename", ""));
            if (user != null && isBot(user)) {
                continue;
            }
            notes.put(new JSONObject()
                    .put("id", source.optLong("databaseId"))
                    .put("system", false)
                    .put("author", normalizeUser(user))
                    .put("created_at", createdAt)
                    .put("body", "")
                    .put("review", review));
        }
    }

    private JSONObject graphQl(String query, String token, String category) {
        GitHubRequestCacheKey cacheKey = new GitHubRequestCacheKey(credentialKey(token), "graphql:" + query);
        String cached = responseCache.getIfPresent(cacheKey);
        if (cached != null) {
            return new JSONObject(cached).getJSONObject("data");
        }
        assertRequestAllowed(token);
        requestSlots.acquireUninterruptibly();
        try {
            cached = responseCache.getIfPresent(cacheKey);
            if (cached != null) {
                return new JSONObject(cached).getJSONObject("data");
            }
            assertRequestAllowed(token);
            PerformanceTimingLog.incrementCount("github.api.graphql." + category);
            HttpHeaders requestHeaders = headers(token);
            requestHeaders.setContentType(MediaType.APPLICATION_JSON);
            RequestEntity<String> request = new RequestEntity<>(
                    new JSONObject().put("query", query).toString(), requestHeaders, HttpMethod.POST,
                    URI.create(apiBase + "/graphql"));
            ResponseEntity<String> response = restTemplate.exchange(request, String.class);
            recordExhaustedRateLimit(token, response.getHeaders());
            JSONObject body = new JSONObject(response.getBody());
            JSONArray errors = body.optJSONArray("errors");
            if (errors != null && !errors.isEmpty()) {
                String message = errors.optJSONObject(0).optString("message", "");
                if (message.toLowerCase(Locale.ROOT).contains("rate limit")) {
                    blockUntilReset(token, response.getHeaders());
                    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "GitHub API rate limit reached");
                }
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub GraphQL notes query failed");
            }
            responseCache.put(cacheKey, body.toString());
            return body.getJSONObject("data");
        } catch (HttpClientErrorException exception) {
            if (isRateLimitResponse(exception)) {
                blockUntilReset(token, exception.getResponseHeaders());
            }
            throw new ResponseStatusException(exception.getStatusCode(), githubErrorMessage(exception));
        } finally {
            requestSlots.release();
        }
    }

    public RepositoryDiff getPullRequestDiff(String projectId, int pullNumber) {
        RepositoryCoordinates repository = fromProjectId(projectId);
        StringBuilder diff = new StringBuilder();
        int lineCount = 0;
        int maxLines = 5000;

        for (int page = 1; page <= maxPages && lineCount < maxLines; page++) {
            JSONArray files = getArray(repository.path() + "/pulls/" + pullNumber + "/files?per_page=100&page=" + page);
            for (int i = 0; i < files.length() && lineCount < maxLines; i++) {
                JSONObject file = files.getJSONObject(i);
                String filename = file.optString("filename", "unknown");
                if (isGeneratedOrBinary(filename)) {
                    continue;
                }
                diff.append("File: ").append(filename).append('\n');
                String patch = file.optString("patch", "");
                for (String line : patch.split("\\n")) {
                    if (lineCount >= maxLines) {
                        diff.append("... (diff truncated)\n");
                        break;
                    }
                    diff.append(line).append('\n');
                    lineCount++;
                }
                diff.append('\n');
            }
            if (files.length() < 100) {
                break;
            }
        }
        return new RepositoryDiff(diff.toString(), lineCount);
    }

    public List<JSONObject> getIssues(String projectId, String state) {
        return getIssues(projectId, state, null);
    }

    public List<JSONObject> getIssues(String projectId, String state, String since) {
        RepositoryCoordinates repository = fromProjectId(projectId);
        List<JSONObject> result = new ArrayList<>();
        String githubState = state == null || state.isBlank() ? "all" : state;

        for (int page = 1; page <= maxPages; page++) {
            StringBuilder path = new StringBuilder(repository.path() + "/issues?state=" + encode(githubState)
                    + "&sort=updated&direction=desc&per_page=100&page=" + page);
            appendQuery(path, "since", since);
            JSONArray issues = getArray(path.toString());
            for (int i = 0; i < issues.length(); i++) {
                JSONObject source = issues.getJSONObject(i);
                if (source.has("pull_request")) {
                    continue;
                }
                result.add(normalizeIssue(source));
            }
            if (issues.length() < 100) {
                break;
            }
        }
        return result;
    }

    private JSONObject normalizeCommit(JSONObject source) {
        JSONObject commit = source.optJSONObject("commit");
        JSONObject gitAuthor = commit == null ? null : commit.optJSONObject("author");
        JSONObject gitCommitter = commit == null ? null : commit.optJSONObject("committer");
        JSONObject author = source.optJSONObject("author");
        JSONObject committer = source.optJSONObject("committer");
        JSONObject normalized = new JSONObject();
        String sha = source.optString("sha", "");
        normalized.put("id", sha);
        normalized.put("short_id", sha.length() > 8 ? sha.substring(0, 8) : sha);
        normalized.put("author_name", loginOrName(author, gitAuthor));
        normalized.put("author_email", gitAuthor == null ? "" : gitAuthor.optString("email", ""));
        normalized.put("committer_name", loginOrName(committer, gitCommitter));
        normalized.put("committer_email", gitCommitter == null ? "" : gitCommitter.optString("email", ""));
        normalized.put("authored_date", gitAuthor == null ? "" : gitAuthor.optString("date", ""));
        normalized.put("committed_date", gitCommitter == null ? "" : gitCommitter.optString("date", ""));
        JSONObject sourceStats = source.optJSONObject("stats");
        JSONObject stats = new JSONObject();
        stats.put("additions", sourceStats == null ? 0 : sourceStats.optInt("additions"));
        stats.put("deletions", sourceStats == null ? 0 : sourceStats.optInt("deletions"));
        stats.put("total", sourceStats == null ? 0 : sourceStats.optInt("total"));
        normalized.put("stats", stats);
        return normalized;
    }

    private JSONObject normalizePullRequest(JSONObject source) {
        JSONObject normalized = new JSONObject();
        normalized.put("id", source.optLong("id"));
        normalized.put("iid", source.optInt("number"));
        normalized.put("created_at", source.optString("created_at", ""));
        normalized.put("updated_at", source.optString("updated_at", ""));
        normalized.put("merged_at", nullableString(source, "merged_at"));
        normalized.put("state", source.isNull("merged_at") ? source.optString("state", "") : "merged");
        normalized.put("author", normalizeUser(source.optJSONObject("user")));
        JSONObject head = source.optJSONObject("head");
        JSONObject base = source.optJSONObject("base");
        normalized.put("source_branch", head == null ? "" : head.optString("ref", ""));
        normalized.put("target_branch", base == null ? "" : base.optString("ref", ""));
        normalized.put("web_url", source.optString("html_url", ""));
        normalized.put("title", source.optString("title", ""));
        return normalized;
    }

    private JSONObject normalizeIssue(JSONObject source) {
        JSONObject normalized = new JSONObject();
        normalized.put("id", source.optLong("id"));
        normalized.put("iid", source.optInt("number"));
        normalized.put("created_at", source.optString("created_at", ""));
        normalized.put("updated_at", source.optString("updated_at", ""));
        normalized.put("closed_at", nullableString(source, "closed_at"));
        normalized.put("state", source.optString("state", ""));
        normalized.put("author", normalizeUser(source.optJSONObject("user")));
        normalized.put("assignee", normalizeUser(source.optJSONObject("assignee")));

        JSONArray assignees = new JSONArray();
        JSONArray sourceAssignees = source.optJSONArray("assignees");
        if (sourceAssignees != null) {
            for (int i = 0; i < sourceAssignees.length(); i++) {
                assignees.put(normalizeUser(sourceAssignees.optJSONObject(i)));
            }
        }
        normalized.put("assignees", assignees);

        JSONArray labels = new JSONArray();
        JSONArray sourceLabels = source.optJSONArray("labels");
        if (sourceLabels != null) {
            for (int i = 0; i < sourceLabels.length(); i++) {
                JSONObject label = sourceLabels.optJSONObject(i);
                if (label != null) {
                    labels.put(label.optString("name", ""));
                }
            }
        }
        normalized.put("labels", labels);
        return normalized;
    }

    private boolean matchesPullRequest(JSONObject pull, ZonedDateTime start, ZonedDateTime end,
            String authorUserName, String refName, String filterType, String state) {
        if ("merged".equals(state) && pull.isNull("merged_at")) {
            return false;
        }
        if (authorUserName != null && !authorUserName.isBlank()) {
            String author = pull.getJSONObject("author").optString("username", "");
            if (!authorUserName.equalsIgnoreCase(author)) {
                return false;
            }
        }
        if (refName != null && !refName.isBlank()
                && !refName.equals(pull.optString("source_branch"))
                && !refName.equals(pull.optString("target_branch"))) {
            return false;
        }
        String field = switch (filterType == null ? "updated" : filterType) {
            case "created" -> "created_at";
            case "merged" -> "merged_at";
            default -> "updated_at";
        };
        if (pull.isNull(field) || pull.optString(field, "").isBlank()) {
            return false;
        }
        ZonedDateTime value = ZonedDateTime.parse(pull.getString(field));
        return (start == null || !value.isBefore(start)) && (end == null || !value.isAfter(end));
    }

    private void appendNotes(JSONArray target, String basePath, boolean review) {
        appendNotes(target, basePath, review, maxPages);
    }

    private void appendNotes(JSONArray target, String basePath, boolean review, int pageLimit) {
        for (int page = 1; page <= pageLimit; page++) {
            JSONArray sourceNotes = getArray(basePath + "?per_page=100&page=" + page);
            for (int i = 0; i < sourceNotes.length(); i++) {
                JSONObject source = sourceNotes.getJSONObject(i);
                if (review && "PENDING".equalsIgnoreCase(source.optString("state", ""))) {
                    continue;
                }
                JSONObject sourceUser = source.optJSONObject("user");
                if (sourceUser != null && isBot(sourceUser)) {
                    continue;
                }
                JSONObject note = new JSONObject();
                String createdAt = review
                        ? source.optString("submitted_at", source.optString("created_at", ""))
                        : source.optString("created_at", "");
                if (createdAt.isBlank()) {
                    continue;
                }
                note.put("id", source.optLong("id"));
                note.put("system", false);
                note.put("author", normalizeUser(source.optJSONObject("user")));
                note.put("created_at", createdAt);
                note.put("body", source.optString("body", ""));
                note.put("web_url", source.optString("html_url", ""));
                note.put("review", review);
                target.put(note);
            }
            if (sourceNotes.length() < 100) {
                break;
            }
        }
    }

    private JSONObject normalizeUser(JSONObject source) {
        JSONObject user = new JSONObject();
        if (source == null) {
            return user;
        }
        String login = source.optString("login", "");
        user.put("username", login);
        user.put("name", login);
        user.put("bot", isBot(source));
        return user;
    }

    /** GitHub Apps report type "Bot"; their logins also end with "[bot]". */
    private static boolean isBot(JSONObject user) {
        return "Bot".equalsIgnoreCase(user.optString("type", ""))
                || user.optString("login", "").endsWith("[bot]");
    }

    private String loginOrName(JSONObject user, JSONObject gitIdentity) {
        if (user != null && !user.optString("login", "").isBlank()) {
            return user.getString("login");
        }
        return gitIdentity == null ? "" : gitIdentity.optString("name", "");
    }

    private Object nullableString(JSONObject source, String key) {
        return source.has(key) && !source.isNull(key) ? source.optString(key) : JSONObject.NULL;
    }

    private JSONObject getObject(String path) {
        String body = exchange(path);
        return body == null || body.isBlank() ? new JSONObject() : new JSONObject(body);
    }

    private JSONArray getArray(String path) {
        String body = exchange(path);
        return body == null || body.isBlank() ? new JSONArray() : new JSONArray(body);
    }

    private String exchange(String path) {
        String token = currentToken();
        GitHubRequestCacheKey cacheKey = new GitHubRequestCacheKey(credentialKey(token), path);
        if (isCacheable(path)) {
            String cached = responseCache.getIfPresent(cacheKey);
            if (cached != null) {
                return cached;
            }
        }
        assertRequestAllowed(token);
        requestSlots.acquireUninterruptibly();
        try {
            if (isCacheable(path)) {
                String cached = responseCache.getIfPresent(cacheKey);
                if (cached != null) {
                    return cached;
                }
            }
            assertRequestAllowed(token);
            PerformanceTimingLog.incrementCount("github.api." + requestCategory(path));
            RequestEntity<Void> request = new RequestEntity<>(headers(token), HttpMethod.GET, URI.create(apiBase + path));
            ResponseEntity<String> response = restTemplate.exchange(request, String.class);
            recordExhaustedRateLimit(token, response.getHeaders());
            String body = response.getBody();
            if (isCacheable(path) && body != null) {
                responseCache.put(cacheKey, body);
            }
            return body;
        } catch (HttpClientErrorException exception) {
            if (isRateLimitResponse(exception)) {
                blockUntilReset(token, exception.getResponseHeaders());
            }
            log.warn("GitHub API request failed: status={}", exception.getStatusCode().value());
            throw new ResponseStatusException(exception.getStatusCode(), githubErrorMessage(exception));
        } finally {
            requestSlots.release();
        }
    }

    private String requestCategory(String path) {
        if (path.contains("/commits/")) return "commits.detail";
        if (path.contains("/commits?")) return "commits.list";
        if (path.contains("/pulls/") && path.contains("/reviews")) return "pulls.reviews";
        if (path.contains("/issues/") && path.contains("/comments")) return "pulls.comments";
        if (path.contains("/pulls?")) return "pulls.list";
        if (path.contains("/issues?")) return "issues.list";
        if (path.contains("/contributors?")) return "contributors";
        return "other";
    }

    private HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.valueOf("application/vnd.github+json")));
        headers.set("X-GitHub-Api-Version", apiVersion);
        headers.set("User-Agent", "dev-productivity-signals");
        if (!token.isBlank()) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    private void assertRequestAllowed(String token) {
        String credential = credentialKey(token);
        long blockedUntil = blockedUntilByCredential.getOrDefault(credential, 0L);
        long now = System.currentTimeMillis();
        if (blockedUntil <= now) {
            blockedUntilByCredential.remove(credential, blockedUntil);
            return;
        }
        long retryAfterSeconds = Math.max(1L, (blockedUntil - now + 999L) / 1000L);
        throw new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "GitHub API rate limit reached; retry after " + retryAfterSeconds + " seconds");
    }

    private void recordExhaustedRateLimit(String token, HttpHeaders headers) {
        if (headerLong(headers, "X-RateLimit-Remaining") == 0L) {
            blockUntilReset(token, headers);
        }
    }

    private boolean isRateLimitResponse(HttpClientErrorException exception) {
        int status = exception.getStatusCode().value();
        if (status == 429) {
            return true;
        }
        HttpHeaders headers = exception.getResponseHeaders();
        if (headerLong(headers, "X-RateLimit-Remaining") == 0L
                || (headers != null && headers.getFirst(HttpHeaders.RETRY_AFTER) != null)) {
            return true;
        }
        String body = exception.getResponseBodyAsString().toLowerCase(Locale.ROOT);
        return status == 403 && (body.contains("rate limit") || body.contains("abuse detection"));
    }

    private void blockUntilReset(String token, HttpHeaders headers) {
        long now = System.currentTimeMillis();
        long blockedUntil = now + DEFAULT_RATE_LIMIT_BACKOFF_MILLIS;
        long retryAfterSeconds = headerLong(headers, HttpHeaders.RETRY_AFTER);
        if (retryAfterSeconds > 0L) {
            blockedUntil = Math.max(blockedUntil, now + retryAfterSeconds * 1000L);
        }
        long resetEpochSeconds = headerLong(headers, "X-RateLimit-Reset");
        if (resetEpochSeconds > 0L) {
            blockedUntil = Math.max(blockedUntil, resetEpochSeconds * 1000L + 1000L);
        }
        blockedUntilByCredential.merge(credentialKey(token), blockedUntil, Math::max);
    }

    private long headerLong(HttpHeaders headers, String name) {
        if (headers == null) {
            return -1L;
        }
        String value = headers.getFirst(name);
        if (value == null || value.isBlank()) {
            return -1L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private String credentialKey(String token) {
        return token.isBlank() ? ANONYMOUS_CREDENTIAL : token;
    }

    private boolean isCacheable(String path) {
        return !"/rate_limit".equals(path);
    }

    private String currentToken() {
        return configuredToken == null ? "" : configuredToken.trim();
    }

    RepositoryCoordinates parseRepositoryUrl(String repositoryUrl) {
        String value = repositoryUrl == null ? "" : repositoryUrl.trim();
        Matcher httpsMatcher = HTTPS_REPOSITORY.matcher(value);
        if (httpsMatcher.matches()) {
            return new RepositoryCoordinates(httpsMatcher.group(1), httpsMatcher.group(2));
        }
        Matcher sshMatcher = SSH_REPOSITORY.matcher(value);
        if (sshMatcher.matches()) {
            return new RepositoryCoordinates(sshMatcher.group(1), sshMatcher.group(2));
        }
        throw new ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "Enter a GitHub repository URL such as https://github.com/owner/repository");
    }

    public RepositoryCoordinates fromProjectId(String projectId) {
        if (!supports(projectId)) {
            throw new IllegalArgumentException("Not a GitHub project id");
        }
        String encoded = projectId.substring(PROJECT_PREFIX.length());
        String[] parts = encoded.split("~", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("Invalid GitHub project id");
        }
        return new RepositoryCoordinates(decode(parts[0]), decode(parts[1]));
    }

    private String toProjectId(RepositoryCoordinates repository) {
        return PROJECT_PREFIX + encode(repository.owner()) + "~" + encode(repository.repo());
    }

    private void appendQuery(StringBuilder path, String name, String value) {
        if (value != null && !value.isBlank()) {
            path.append('&').append(name).append('=').append(encode(value));
        }
    }

    private ZonedDateTime parseDate(String value, boolean endOfDay) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.length() == 10
                ? value + (endOfDay ? "T23:59:59Z" : "T00:00:00Z")
                : value;
        return ZonedDateTime.parse(normalized);
    }

    private boolean isGeneratedOrBinary(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".lock") || lower.endsWith("-lock.json") || lower.endsWith(".bin");
    }

    private String githubErrorMessage(HttpClientErrorException exception) {
        int status = exception.getStatusCode().value();
        if (status == 401) {
            return "GitHub token is invalid or expired";
        }
        if (status == 403 || status == 429) {
            return "GitHub API rate limit exceeded or access was denied";
        }
        if (status == 404) {
            return "GitHub repository was not found or the token cannot access it";
        }
        return "GitHub API request failed";
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    public record RepositoryCoordinates(String owner, String repo) {
        String path() {
            return "/repos/" + owner + "/" + repo;
        }
    }

    public record RepositoryDiff(String diff, int lineCount) {
    }

    private record GitHubRequestCacheKey(String credential, String path) {
    }
}
