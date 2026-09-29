package dev.productivity.signals.service;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import dev.productivity.signals.dto.IssueStatsDTO;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class IssueService {

    private static final Logger log = LoggerFactory.getLogger(IssueService.class);
    private static final Pattern ENGLISH_BUG_LABEL = Pattern.compile("(^|[^a-z])bugs?($|[^a-z])");

    private final RestTemplate rt;
    private final GitHubRepositoryService gitHubRepositoryService;

    @Value("${gitlab.api.url}")
    private String base;

    @Value("${gitlab.api.token}")
    private String token;

    private String bugLabelKeyword = "バグ";

    public IssueService(RestTemplate rt, GitHubRepositoryService gitHubRepositoryService) {
        this.rt = rt;
        this.gitHubRepositoryService = gitHubRepositoryService;
    }

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.set("PRIVATE-TOKEN", token);
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        return h;
    }

    public IssueStatsDTO getIssueStats(String projectId, String since, String until, String userName, String refName) {
        String apiSince = since;
        String apiUntil = until;
        if (apiUntil != null && apiUntil.length() == 10) {
            apiUntil = until + "T23:59:59Z";
        }
        if (apiSince != null && apiSince.length() == 10) {
            apiSince = since + "T00:00:00Z";
        }

        ZonedDateTime startRange = (apiSince != null && !apiSince.isEmpty()) ? ZonedDateTime.parse(apiSince) : null;
        ZonedDateTime endRange = (apiUntil != null && !apiUntil.isEmpty()) ? ZonedDateTime.parse(apiUntil) : null;

        if (gitHubRepositoryService.supports(projectId)) {
            return calculateGitHubIssueStats(projectId, userName, startRange, endRange);
        }

        long createdCount = countIssues(projectId, apiSince, apiUntil, userName, null, "all", null, "created", startRange, endRange);
        long completedCount = countIssues(projectId, apiSince, apiUntil, null, userName, "closed", null, "closed", startRange, endRange);

        long bugFoundCount = countIssues(projectId, apiSince, apiUntil, userName, null, "all", bugLabelKeyword, "created", startRange, endRange);
        long bugCausedCount = countIssues(projectId, apiSince, apiUntil, null, userName, "all", bugLabelKeyword, "created", startRange, endRange);

        BugFixStats bugFixStats = calculateBugStats(projectId, apiSince, apiUntil, userName, startRange, endRange);

        return new IssueStatsDTO(createdCount, completedCount, bugFoundCount, bugCausedCount, bugFixStats.count(), bugFixStats.avgHours());
    }

    public Map<String, IssueStatsDTO> getIssueStatsByUser(
            String projectId,
            String since,
            String until,
            Collection<String> userNames,
            String refName) {
        if (userNames == null || userNames.isEmpty()) {
            return Map.of();
        }

        String apiSince = startOfDayIfDate(since);
        String apiUntil = endOfDayIfDate(until);
        ZonedDateTime startRange = (apiSince != null && !apiSince.isEmpty()) ? ZonedDateTime.parse(apiSince) : null;
        ZonedDateTime endRange = (apiUntil != null && !apiUntil.isEmpty()) ? ZonedDateTime.parse(apiUntil) : null;

        boolean github = gitHubRepositoryService.supports(projectId);
        List<JSONObject> createdIssues = fetchIssuesForPeriod(projectId, apiSince, apiUntil, "created", "all");
        List<JSONObject> closedIssues = github
                ? createdIssues
                : fetchIssuesForPeriod(projectId, apiSince, apiUntil, "closed", "closed");

        Map<String, IssueStatsAccumulator> statsByUser = initializeIssueStats(userNames);

        for (JSONObject issue : createdIssues) {
            if (!isIssueDateInRange(issue, "created_at", startRange, endRange)) {
                continue;
            }
            boolean bug = matchesLabel(issue, bugLabelKeyword);
            String authorKey = issueAuthorKey(issue, statsByUser);
            if (authorKey != null) {
                IssueStatsAccumulator stats = statsByUser.get(authorKey);
                stats.createdCount++;
                if (bug) {
                    stats.bugFoundCount++;
                }
            }

            if (bug) {
                for (String assigneeKey : issueAssigneeKeys(issue, statsByUser)) {
                    statsByUser.get(assigneeKey).bugCausedCount++;
                }
            }
        }

        for (JSONObject issue : closedIssues) {
            if (!isIssueDateInRange(issue, "closed_at", startRange, endRange)) {
                continue;
            }
            boolean bug = matchesLabel(issue, bugLabelKeyword);
            Long bugFixSeconds = bug ? bugFixSeconds(issue) : null;
            for (String assigneeKey : issueAssigneeKeys(issue, statsByUser)) {
                IssueStatsAccumulator stats = statsByUser.get(assigneeKey);
                stats.completedCount++;
                if (!bug) {
                    continue;
                }
                stats.bugFixedCount++;
                if (bugFixSeconds != null) {
                    stats.totalBugFixSeconds += bugFixSeconds;
                    stats.validBugFixDurationCount++;
                }
            }
        }

        return toIssueStatsDtoMap(statsByUser);
    }

    private record BugFixStats(long count, double avgHours) {}

    private BugFixStats calculateBugStats(String projectId, String since, String until, String assigneeUsername, ZonedDateTime start, ZonedDateTime end) {
        StringBuilder url = new StringBuilder(String.format("%s/projects/%s/issues?", base, urlEncode(projectId)));
        if (since != null) url.append("updated_after=").append(urlEncode(since)).append("&");
        if (until != null) url.append("updated_before=").append(urlEncode(until)).append("&");
        if (assigneeUsername != null) url.append("assignee_username=").append(urlEncode(assigneeUsername)).append("&");
        url.append("state=closed&");

        Map<Integer, JSONObject> issues = new HashMap<>();
        fetchIssues(url.toString(), issues, bugLabelKeyword);

        if (issues.isEmpty()) {
            return new BugFixStats(0, 0.0);
        }

        long totalSeconds = 0;
        int count = 0;

        for (JSONObject issue : issues.values()) {
            String closedAtStr = issue.optString("closed_at");
            if (closedAtStr != null && !closedAtStr.isEmpty()) {
                ZonedDateTime closed = ZonedDateTime.parse(closedAtStr);
                // Java側で日付チェック
                if (start != null && closed.isBefore(start)) continue;
                if (end != null && closed.isAfter(end)) continue;

                String createdAtStr = issue.optString("created_at");
                if (createdAtStr != null && !createdAtStr.isEmpty()) {
                    ZonedDateTime created = ZonedDateTime.parse(createdAtStr);
                    long seconds = java.time.temporal.ChronoUnit.SECONDS.between(created, closed);
                    if (seconds >= 0) {
                        totalSeconds += seconds;
                        count++;
                    }
                }
            }
        }

        double avgHours = 0.0;
        if (count > 0) {
            avgHours = (double) totalSeconds / (60L * 60 * count);
        }

        return new BugFixStats(count, avgHours);
    }

    private long countIssues(String projectId, String since, String until,
                            String authorUsername, String assigneeUsername, String state, String labelKeyword, String periodType,
                            ZonedDateTime start, ZonedDateTime end) {
        StringBuilder url = new StringBuilder(String.format("%s/projects/%s/issues?", base, urlEncode(projectId)));
        if ("created".equals(periodType)) {
            if (since != null) url.append("created_after=").append(urlEncode(since)).append("&");
            if (until != null) url.append("created_before=").append(urlEncode(until)).append("&");
        } else if ("updated".equals(periodType) || "closed".equals(periodType)) {
            if (since != null) url.append("updated_after=").append(urlEncode(since)).append("&");
            if (until != null) url.append("updated_before=").append(urlEncode(until)).append("&");
        }
        if (authorUsername != null) url.append("author_username=").append(urlEncode(authorUsername)).append("&");
        if (assigneeUsername != null) url.append("assignee_username=").append(urlEncode(assigneeUsername)).append("&");
        if (state != null) url.append("state=").append(state).append("&");

        Map<Integer, JSONObject> issues = new HashMap<>();
        fetchIssues(url.toString(), issues, labelKeyword);

        // Java側での日付再チェック
        long finalCount = 0;
        for (JSONObject issue : issues.values()) {
            ZonedDateTime targetDate;
            if ("created".equals(periodType)) {
                targetDate = ZonedDateTime.parse(issue.getString("created_at"));
            } else if ("closed".equals(periodType)) {
                String closedAtStr = issue.optString("closed_at");
                if (closedAtStr == null || closedAtStr.isEmpty()) continue;
                targetDate = ZonedDateTime.parse(closedAtStr);
            } else {
                targetDate = ZonedDateTime.parse(issue.getString("updated_at"));
            }
            if (start != null && targetDate.isBefore(start)) continue;
            if (end != null && targetDate.isAfter(end)) continue;
            finalCount++;
        }
        return finalCount;
    }

    private void fetchIssues(String baseUrl, Map<Integer, JSONObject> issueMap, String labelKeyword) {
        int page = 1;
        int perPage = 100;
        String separator = baseUrl.contains("?") ? "&" : "?";

        try {
            while (true) {
                String url = String.format("%s%spage=%d&per_page=%d", baseUrl, separator, page, perPage);
                RequestEntity<Void> req = new RequestEntity<>(headers(), HttpMethod.GET, URI.create(url));
                ResponseEntity<String> res = rt.exchange(req, String.class);
                JSONArray issues = new JSONArray(res.getBody());

                if (issues.isEmpty()) break;

                for (int i = 0; i < issues.length(); i++) {
                    JSONObject issue = issues.getJSONObject(i);
                    if (labelKeyword == null || matchesLabel(issue, labelKeyword)) {
                        issueMap.put(issue.getInt("iid"), issue);
                    }
                }
                if (issues.length() < perPage) break;
                page++;
            }
        } catch (Exception e) {
            log.error("Error fetching issues: {}", e.getMessage());
        }
    }

    private List<JSONObject> fetchIssuesForPeriod(String projectId, String since, String until, String periodType,
            String state) {
        if (gitHubRepositoryService.supports(projectId)) {
            return gitHubRepositoryService.getIssues(projectId, state, since);
        }
        StringBuilder url = new StringBuilder(String.format("%s/projects/%s/issues?", base, urlEncode(projectId)));
        if ("created".equals(periodType)) {
            if (since != null) url.append("created_after=").append(urlEncode(since)).append("&");
            if (until != null) url.append("created_before=").append(urlEncode(until)).append("&");
        } else {
            if (since != null) url.append("updated_after=").append(urlEncode(since)).append("&");
            if (until != null) url.append("updated_before=").append(urlEncode(until)).append("&");
        }
        if (state != null) url.append("state=").append(state).append("&");

        Map<Integer, JSONObject> issues = new HashMap<>();
        fetchIssues(url.toString(), issues, null);
        return new ArrayList<>(issues.values());
    }

    private boolean isIssueDateInRange(JSONObject issue, String fieldName, ZonedDateTime start, ZonedDateTime end) {
        String dateValue = issue.optString(fieldName);
        if (dateValue == null || dateValue.isEmpty()) {
            return false;
        }
        ZonedDateTime date = ZonedDateTime.parse(dateValue);
        if (start != null && date.isBefore(start)) {
            return false;
        }
        return end == null || !date.isAfter(end);
    }

    private Map<String, IssueStatsAccumulator> initializeIssueStats(Collection<String> userNames) {
        Map<String, IssueStatsAccumulator> statsByUser = new HashMap<>();
        for (String userName : userNames) {
            String key = normalizeUsername(userName);
            if (!key.isBlank()) {
                statsByUser.putIfAbsent(key, new IssueStatsAccumulator());
            }
        }
        return statsByUser;
    }

    private Map<String, IssueStatsDTO> toIssueStatsDtoMap(Map<String, IssueStatsAccumulator> statsByUser) {
        Map<String, IssueStatsDTO> result = new HashMap<>();
        for (Map.Entry<String, IssueStatsAccumulator> entry : statsByUser.entrySet()) {
            IssueStatsAccumulator stats = entry.getValue();
            double avgBugFixedHours = stats.validBugFixDurationCount > 0
                    ? (double) stats.totalBugFixSeconds / (60L * 60 * stats.validBugFixDurationCount)
                    : 0.0;
            result.put(entry.getKey(), new IssueStatsDTO(
                    stats.createdCount,
                    stats.completedCount,
                    stats.bugFoundCount,
                    stats.bugCausedCount,
                    stats.bugFixedCount,
                    avgBugFixedHours));
        }
        return result;
    }

    private String issueAuthorKey(JSONObject issue, Map<String, IssueStatsAccumulator> statsByUser) {
        JSONObject author = issue.optJSONObject("author");
        if (author == null) {
            return null;
        }
        String key = normalizeUsername(author.optString("username", ""));
        return statsByUser.containsKey(key) ? key : null;
    }

    private Set<String> issueAssigneeKeys(JSONObject issue, Map<String, IssueStatsAccumulator> statsByUser) {
        Set<String> keys = new LinkedHashSet<>();
        JSONObject assignee = issue.optJSONObject("assignee");
        addAssigneeKey(keys, assignee, statsByUser);

        JSONArray assignees = issue.optJSONArray("assignees");
        if (assignees != null) {
            for (int i = 0; i < assignees.length(); i++) {
                addAssigneeKey(keys, assignees.optJSONObject(i), statsByUser);
            }
        }
        return keys;
    }

    private void addAssigneeKey(Set<String> keys, JSONObject assignee,
            Map<String, IssueStatsAccumulator> statsByUser) {
        if (assignee == null) {
            return;
        }
        String key = normalizeUsername(assignee.optString("username", ""));
        if (statsByUser.containsKey(key)) {
            keys.add(key);
        }
    }

    private Long bugFixSeconds(JSONObject issue) {
        String createdAtStr = issue.optString("created_at");
        String closedAtStr = issue.optString("closed_at");
        if (createdAtStr == null || createdAtStr.isEmpty() || closedAtStr == null || closedAtStr.isEmpty()) {
            return null;
        }
        try {
            long seconds = ChronoUnit.SECONDS.between(
                    ZonedDateTime.parse(createdAtStr),
                    ZonedDateTime.parse(closedAtStr));
            return seconds >= 0 ? seconds : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeUsername(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    private static class IssueStatsAccumulator {
        long createdCount;
        long completedCount;
        long bugFoundCount;
        long bugCausedCount;
        long bugFixedCount;
        long totalBugFixSeconds;
        long validBugFixDurationCount;
    }

    private String startOfDayIfDate(String value) {
        return value != null && value.length() == 10 ? value + "T00:00:00Z" : value;
    }

    private String endOfDayIfDate(String value) {
        return value != null && value.length() == 10 ? value + "T23:59:59Z" : value;
    }

    private boolean matchesLabel(JSONObject issue, String keyword) {
        if (!issue.has("labels")) return false;
        JSONArray labels = issue.getJSONArray("labels");
        for (int i = 0; i < labels.length(); i++) {
            String label = labels.getString(i);
            if (label == null) continue;
            String normalized = label.toLowerCase(Locale.ROOT);
            if (label.contains(keyword) || ENGLISH_BUG_LABEL.matcher(normalized).find()
                    || normalized.contains("bugfix")) return true;
        }
        return false;
    }

    private IssueStatsDTO calculateGitHubIssueStats(String projectId, String userName,
            ZonedDateTime start, ZonedDateTime end) {
        long createdCount = 0;
        long completedCount = 0;
        long bugFoundCount = 0;
        long bugCausedCount = 0;
        long bugFixedCount = 0;
        long totalBugFixSeconds = 0;

        for (JSONObject issue : gitHubRepositoryService.getIssues(projectId, "all",
                start == null ? null : start.toInstant().toString())) {
            boolean authoredByUser = userName == null || userName.isBlank() || userMatches(
                    issue.optJSONObject("author"), userName);
            boolean assignedToUser = userName == null || userName.isBlank() || issueAssignedTo(issue, userName);
            boolean bug = matchesLabel(issue, bugLabelKeyword);
            boolean createdInRange = isIssueDateInRange(issue, "created_at", start, end);
            boolean closedInRange = isIssueDateInRange(issue, "closed_at", start, end);

            if (createdInRange && authoredByUser) {
                createdCount++;
                if (bug) {
                    bugFoundCount++;
                }
            }
            if (createdInRange && assignedToUser && bug) {
                bugCausedCount++;
            }
            if (closedInRange && assignedToUser) {
                completedCount++;
                if (bug) {
                    bugFixedCount++;
                    Long seconds = bugFixSeconds(issue);
                    if (seconds != null) {
                        totalBugFixSeconds += seconds;
                    }
                }
            }
        }

        double averageBugFixHours = bugFixedCount == 0
                ? 0.0
                : (double) totalBugFixSeconds / (60L * 60 * bugFixedCount);
        return new IssueStatsDTO(
                createdCount, completedCount, bugFoundCount, bugCausedCount, bugFixedCount, averageBugFixHours);
    }

    private boolean issueAssignedTo(JSONObject issue, String userName) {
        if (userMatches(issue.optJSONObject("assignee"), userName)) {
            return true;
        }
        JSONArray assignees = issue.optJSONArray("assignees");
        if (assignees == null) {
            return false;
        }
        for (int i = 0; i < assignees.length(); i++) {
            if (userMatches(assignees.optJSONObject(i), userName)) {
                return true;
            }
        }
        return false;
    }

    private boolean userMatches(JSONObject user, String userName) {
        return user != null && userName != null
                && userName.equalsIgnoreCase(user.optString("username", ""));
    }

    private String urlEncode(String value) {
        if (value == null) return "";
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }
}
