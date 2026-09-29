package dev.productivity.signals.controller;

import dev.productivity.signals.entity.AiMrEvaluation;
import dev.productivity.signals.repository.AiMrEvaluationRepository;
import dev.productivity.signals.service.GitHubRepositoryService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import dev.productivity.signals.service.MergeLeadTimeDistribution;
import dev.productivity.signals.service.MergeService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent/evidence/projects/{projectId}")
public class AgentEvidenceController {
    private static final int PR_LIMIT = 12;
    private static final int ISSUE_LIMIT = 20;
    private static final int COMMENTS_PER_ITEM = 8;
    private final GitHubRepositoryService github;
    private final AiMrEvaluationRepository evaluations;
    private final MergeService mergeService;

    @Value("${agent.internal-key:}")
    private String internalKey;

    public AgentEvidenceController(GitHubRepositoryService github, AiMrEvaluationRepository evaluations,
            MergeService mergeService) {
        this.github = github;
        this.evaluations = evaluations;
        this.mergeService = mergeService;
    }

    @GetMapping("/merge-lead-distribution")
    public Map<String, Object> mergeLeadDistribution(@PathVariable String projectId,
            @RequestParam LocalDate since, @RequestParam LocalDate until,
            @RequestParam(required = false) String refName,
            @RequestHeader(value = "X-Agent-Internal-Key", required = false) String key) {
        authorize(projectId, key);
        validatePeriod(since, until);
        long days = java.time.temporal.ChronoUnit.DAYS.between(since, until) + 1;
        LocalDate previousSince = since.minusDays(days);
        LocalDate previousUntil = since.minusDays(1);
        var current = mergeService.fetchAllMergedMergeRequests(projectId, since.toString(), until.toString(), refName);
        var previous = mergeService.fetchAllMergedMergeRequests(projectId, previousSince.toString(),
                previousUntil.toString(), refName);
        return Map.of("current", MergeLeadTimeDistribution.summarize(current, since, until),
                "previous", MergeLeadTimeDistribution.summarize(previous, previousSince, previousUntil),
                "periods", Map.of("current", Map.of("since", since, "until", until),
                        "previous", Map.of("since", previousSince, "until", previousUntil)),
                "coverageNote", "GitHub pagination is bounded; compare sampleCount with mergedCount. "
                        + "PR discussion evidence is separately sampled and does not establish causality.");
    }

    @GetMapping("/index-documents")
    public Map<String, Object> indexDocuments(@PathVariable String projectId,
            @RequestParam LocalDate since, @RequestParam LocalDate until,
            @RequestParam(required = false) String refName,
            @RequestParam(required = false) OffsetDateTime updatedAfter,
            @RequestHeader(value = "X-Agent-Internal-Key", required = false) String key) {
        authorize(projectId, key);
        validatePeriod(since, until);
        if (since.isBefore(until.minusDays(92))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Index window must be at most 93 days");
        }
        var pulls = github.getRecentPullRequestsPage(projectId, since.toString(), until.toString(), refName);
        var issues = github.getRecentIssuesPage(projectId, since.toString(), until.toString());
        List<JSONObject> changedPulls = pulls.items().stream()
                .filter(item -> changedAfter(item, updatedAfter)).toList();
        List<JSONObject> changedIssues = issues.items().stream()
                .filter(item -> changedAfter(item, updatedAfter)).toList();
        List<Map<String, Object>> documents = new ArrayList<>();
        boolean truncated = pulls.hasMoreInPeriod() || issues.hasMoreInPeriod()
                || changedPulls.size() > PR_LIMIT || changedIssues.size() > ISSUE_LIMIT
                || changedIssues.size() > 8;

        for (JSONObject mr : selectPullRequests(changedPulls, since, until)) {
            int number = mr.getInt("iid");
            String url = mr.optString("web_url", "");
            String mrEventAt = mr.optString("merged_at", "");
            if (!withinPeriod(mrEventAt, since, until)) mrEventAt = mr.optString("created_at", "");
            if (withinPeriod(mrEventAt, since, until)) {
                addDocument(documents, projectId, "mr_description", "mr:" + number + ":description", number,
                        mr.optString("title", "") + "\n" + mr.optString("body", ""),
                        mr.optJSONObject("author"), mr.optString("created_at"), mr.optString("updated_at"),
                        mrEventAt, url, List.of());
            }
            JSONArray notes = github.getRecentPullRequestNotes(projectId, number);
            List<JSONObject> selectedNotes = relevantNotes(notes, since, until);
            if (notes.length() >= 100 || selectedNotes.size() > COMMENTS_PER_ITEM) truncated = true;
            for (JSONObject note : selectedNotes.stream().limit(COMMENTS_PER_ITEM).toList()) {
                String noteType = note.optBoolean("review") ? "review" : "comment";
                addDocument(documents, projectId, "mr_discussion",
                        "mr:" + number + ":" + noteType + ":" + note.optLong("id"),
                        number, note.optString("body", ""), note.optJSONObject("author"),
                        note.optString("created_at"), note.optString("created_at"),
                        note.optString("created_at"),
                        note.optString("web_url", "").isBlank() ? url : note.optString("web_url"), List.of());
            }
        }

        int issueIndex = 0;
        for (JSONObject issue : changedIssues.stream().limit(ISSUE_LIMIT).toList()) {
            int number = issue.getInt("iid");
            String url = issue.optString("web_url", "");
            List<String> labels = new ArrayList<>();
            JSONArray rawLabels = issue.optJSONArray("labels");
            if (rawLabels != null) {
                for (int i = 0; i < rawLabels.length(); i++) labels.add(rawLabels.optString(i));
            }
            if (withinPeriod(issue.optString("created_at"), since, until)) {
                addDocument(documents, projectId, "issue", "issue:" + number + ":description", number,
                        issue.optString("title", "") + "\n" + issue.optString("body", ""),
                        issue.optJSONObject("author"), issue.optString("created_at"),
                        issue.optString("updated_at"), issue.optString("created_at"), url, labels);
            }
            if (issueIndex++ >= 8 || issue.optInt("comments", 0) == 0) continue;
            JSONArray notes = github.getRecentIssueComments(projectId, number);
            List<JSONObject> selectedNotes = relevantNotes(notes, since, until);
            if (notes.length() >= 100 || selectedNotes.size() > COMMENTS_PER_ITEM) truncated = true;
            for (JSONObject note : selectedNotes.stream().limit(COMMENTS_PER_ITEM).toList()) {
                addDocument(documents, projectId, "issue_comment", "issue:" + number + ":note:" + note.optLong("id"),
                        number, note.optString("body", ""), note.optJSONObject("author"),
                        note.optString("created_at"), note.optString("created_at"),
                        note.optString("created_at"),
                        note.optString("web_url", "").isBlank() ? url : note.optString("web_url"), labels);
            }
        }
        return Map.of("documents", documents, "truncated", truncated,
                "selection", "recent and long-lead-time PRs; recent issues; human comments from bounded first pages");
    }

    private boolean changedAfter(JSONObject item, OffsetDateTime updatedAfter) {
        return updatedAfter == null || OffsetDateTime.parse(item.optString("updated_at"))
                .isAfter(updatedAfter);
    }

    private List<JSONObject> selectPullRequests(List<JSONObject> pulls, LocalDate since, LocalDate until) {
        Map<Integer, JSONObject> selected = new LinkedHashMap<>();
        pulls.stream().filter(mr -> withinPeriod(mr.optString("merged_at", ""), since, until))
                .filter(mr -> leadHours(mr) >= 0)
                .sorted(Comparator.comparingLong(this::leadHours).reversed())
                .limit(PR_LIMIT / 2)
                .forEach(mr -> selected.put(mr.getInt("iid"), mr));
        for (JSONObject mr : pulls) {
            if (selected.size() >= PR_LIMIT) break;
            selected.putIfAbsent(mr.getInt("iid"), mr);
        }
        return new ArrayList<>(selected.values());
    }

    private long leadHours(JSONObject mr) {
        try {
            return Duration.between(OffsetDateTime.parse(mr.getString("created_at")),
                    OffsetDateTime.parse(mr.getString("merged_at"))).toHours();
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private List<JSONObject> relevantNotes(JSONArray notes, LocalDate since, LocalDate until) {
        List<JSONObject> selected = new ArrayList<>();
        for (int i = 0; i < notes.length(); i++) {
            JSONObject note = notes.getJSONObject(i);
            JSONObject author = note.optJSONObject("author");
            if (!withinPeriod(note.optString("created_at"), since, until)
                    || note.optString("body", "").isBlank()
                    || author != null && author.optBoolean("bot", false)) continue;
            selected.add(note);
        }
        return selected.stream().sorted(Comparator.comparing(
                (JSONObject note) -> note.optString("created_at", "")).reversed()).toList();
    }

    private boolean withinPeriod(String timestamp, LocalDate since, LocalDate until) {
        if (timestamp.length() < 10) return false;
        LocalDate day = LocalDate.parse(timestamp.substring(0, 10));
        return !day.isBefore(since) && !day.isAfter(until);
    }

    private void addDocument(List<Map<String, Object>> documents, String projectId, String sourceType,
            String sourceId, int entityId, String content, JSONObject author, String createdAt,
            String updatedAt, String eventAt, String url, List<String> labels) {
        if (content.isBlank() || url.isBlank() || author != null && author.optBoolean("bot", false)) return;
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("projectId", projectId);
        document.put("sourceType", sourceType);
        document.put("sourceId", sourceId);
        document.put("entityId", entityId);
        document.put("content", content);
        document.put("author", author == null ? "" : author.optString("username", ""));
        document.put("createdAt", createdAt);
        document.put("updatedAt", updatedAt);
        document.put("eventAt", eventAt);
        document.put("url", url);
        document.put("labels", labels);
        documents.add(document);
    }

    @GetMapping("/merge-requests")
    public Map<String, Object> listMergeRequests(@PathVariable String projectId,
            @RequestParam LocalDate since, @RequestParam LocalDate until,
            @RequestParam(required = false) String refName,
            @RequestHeader(value = "X-Agent-Internal-Key", required = false) String key) {
        authorize(projectId, key);
        validatePeriod(since, until);
        var page = github.getRecentPullRequestsPage(projectId, since.toString(), until.toString(), refName);
        List<Map<String, Object>> items = page.items().stream()
                .sorted(Comparator.comparing((JSONObject mr) -> mr.optString("updated_at", "")).reversed())
                .limit(20)
                .map(this::summary)
                .toList();
        return Map.of("items", items, "truncated", page.hasMoreInPeriod() || page.items().size() > 20,
                "selection", "updated in requested period; at most the first 100 recently updated GitHub PRs scanned");
    }

    @GetMapping("/merge-requests/{mrIid}")
    public Map<String, Object> getMergeRequest(@PathVariable String projectId, @PathVariable int mrIid,
            @RequestParam LocalDate since, @RequestParam LocalDate until,
            @RequestParam(required = false) String refName,
            @RequestHeader(value = "X-Agent-Internal-Key", required = false) String key) {
        authorize(projectId, key);
        validatePeriod(since, until);
        if (mrIid < 1 || mrIid > 10_000_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid MR number");
        }
        JSONObject mr = github.getPullRequestDetail(projectId, mrIid);
        String updated = mr.optString("updated_at", "");
        if (updated.isBlank() || LocalDate.parse(updated.substring(0, 10)).isBefore(since)
                || LocalDate.parse(updated.substring(0, 10)).isAfter(until)
                || refName != null && !refName.isBlank()
                && !refName.equals(mr.optString("source_branch"))
                && !refName.equals(mr.optString("target_branch"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MR outside selected context");
        }
        Map<String, Object> result = new LinkedHashMap<>(summary(mr));
        result.put("additions", mr.optInt("additions"));
        result.put("deletions", mr.optInt("deletions"));
        result.put("changedFiles", mr.optInt("changedFiles"));
        result.put("analysis", evaluations.findFirstByProjectIdAndMrIidOrderByAnalyzedAtDesc(projectId, mrIid)
                .map(this::analysis).orElse(null));
        return result;
    }

    private Map<String, Object> summary(JSONObject mr) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("iid", mr.optInt("iid"));
        result.put("title", mr.optString("title"));
        result.put("state", mr.optString("state"));
        result.put("author", mr.optJSONObject("author") == null ? ""
                : mr.getJSONObject("author").optString("username"));
        result.put("createdAt", mr.optString("created_at"));
        result.put("updatedAt", mr.optString("updated_at"));
        result.put("mergedAt", mr.isNull("merged_at") ? null : mr.optString("merged_at"));
        result.put("sourceBranch", mr.optString("source_branch"));
        result.put("targetBranch", mr.optString("target_branch"));
        result.put("url", mr.optString("web_url"));
        return result;
    }

    private Map<String, Object> analysis(AiMrEvaluation evaluation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("changeType", evaluation.getChangeType());
        result.put("complexity", evaluation.getComplexity());
        result.put("maintainability", evaluation.getMaintainability());
        result.put("hasBug", evaluation.getHasBug());
        result.put("contribution", evaluation.getContribution());
        result.put("reasoning", evaluation.getReasoning() == null ? null
                : evaluation.getReasoning().substring(0, Math.min(1500, evaluation.getReasoning().length())));
        result.put("analyzedAt", evaluation.getAnalyzedAt());
        return result;
    }

    private void authorize(String projectId, String key) {
        if (internalKey == null || internalKey.isBlank() || key == null || !MessageDigest.isEqual(
                internalKey.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        if (!projectId.matches("github~[A-Za-z0-9_.-]+~[A-Za-z0-9_.-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select a real GitHub project");
        }
    }

    private void validatePeriod(LocalDate since, LocalDate until) {
        if (until.isBefore(since) || until.isAfter(LocalDate.now().plusDays(1))
                || since.isBefore(until.minusDays(365))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date range");
        }
    }
}
