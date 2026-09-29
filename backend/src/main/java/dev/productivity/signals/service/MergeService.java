package dev.productivity.signals.service;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import dev.productivity.signals.dto.MergeRequestActivityPeriodDTO;
import dev.productivity.signals.dto.MergeRequestStatsDTO;
import lombok.AllArgsConstructor;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class MergeService {

    private final RestTemplate rt;
    private final CommitService commitService;
    private final GitHubRepositoryService gitHubRepositoryService;

    @Value("${gitlab.api.url}")
    private String base;

    @Value("${gitlab.api.token}")
    private String token;

    public MergeService(RestTemplate rt, CommitService commitService,
            GitHubRepositoryService gitHubRepositoryService) {
        this.rt = rt;
        this.commitService = commitService;
        this.gitHubRepositoryService = gitHubRepositoryService;
    }

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.set("PRIVATE-TOKEN", token);
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        return h;
    }

    private boolean fetchNotesEnabled = true;

    public MergeRequestStatsDTO getMergeRequestStatsForUser(String projectId, String userName, String since,
            String until, String refName) {
        return getMergeRequestStatsForUser(projectId, userName, since, until, refName, null);
    }

    public MergeRequestStatsDTO getMergeRequestStatsForUser(String projectId, String userName, String since,
            String until, String refName, List<JSONObject> prefetchedMergedMRs) {
        return getMergeRequestStatsForUser(projectId, userName, since, until, refName,
                prefetchedMergedMRs, null, null);
    }

    public MergeRequestStatsDTO getMergeRequestStatsForUser(String projectId, String userName, String since,
            String until, String refName, List<JSONObject> prefetchedMergedMRs,
            List<JSONObject> prefetchedCreatedMRs, Map<Integer, JSONArray> prefetchedNotesByMrIid) {
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

        // (1) 作成したMR (created_after/before でフィルタ)
        List<JSONObject> createdByUser = prefetchedCreatedMRs != null ? prefetchedCreatedMRs : new ArrayList<>();
        if (prefetchedCreatedMRs == null) {
            fetchPaginatedMergeRequests(projectId, apiSince, apiUntil, userName, refName, createdByUser, "created", "all");
        }

        int createdAndMergedCount = 0;
        long createdLeadTimeSum = 0;
        int totalReviewerCount = 0;
        int totalCommentCount = 0;
        long firstReviewWaitSum = 0;
        int validFirstReviewMRCount = 0;
        int totalCreatedCount = 0;
        int githubNoteLimit = gitHubRepositoryService.supports(projectId)
                ? gitHubRepositoryService.getReviewDetailLimit()
                : Integer.MAX_VALUE;
        int fetchedGitHubNotes = 0;

        for (JSONObject mr : createdByUser) {
            if (userName != null && !userName.isBlank() && !userName.equalsIgnoreCase(mrAuthorUsername(mr))) {
                continue;
            }
            // Java側でも日付チェック (APIフィルタが不完全な場合の保険)
            if (mr.has("created_at")) {
                ZonedDateTime createdAt = ZonedDateTime.parse(mr.getString("created_at"));
                if (startRange != null && createdAt.isBefore(startRange))
                    continue;
                if (endRange != null && createdAt.isAfter(endRange))
                    continue;
            }
            totalCreatedCount++;

            if (mr.has("iid")) {
                int iid = mr.getInt("iid");
                if (fetchNotesEnabled && fetchedGitHubNotes < githubNoteLimit) {
                    JSONArray notes = notesForMr(projectId, mr, prefetchedNotesByMrIid);
                    fetchedGitHubNotes++;
                    int reviewCommentCount = 0;
                    Set<String> reviewCommenters = new HashSet<>();
                    ZonedDateTime earliest = null;
                    String mrAuthorUsername = mrAuthorUsername(mr);

                    for (int i = 0; i < notes.length(); i++) {
                        JSONObject note = notes.getJSONObject(i);
                        if (note.optBoolean("system", false))
                            continue;

                        if (isHumanReviewComment(note, mrAuthorUsername)
                                && note.has("created_at") && !note.isNull("created_at")) {
                            ZonedDateTime commentAt = ZonedDateTime.parse(note.getString("created_at"));
                            if (startRange != null && commentAt.isBefore(startRange))
                                continue;
                            if (endRange != null && commentAt.isAfter(endRange))
                                continue;
                            reviewCommentCount++;
                            JSONObject noteAuthor = note.optJSONObject("author");
                            if (noteAuthor != null) {
                                String noteAuthorUsername = noteAuthor.optString("username", "");
                                if (!noteAuthorUsername.isBlank()) {
                                    reviewCommenters.add(noteAuthorUsername.toLowerCase(Locale.ROOT));
                                }
                            }
                            if (earliest == null || commentAt.isBefore(earliest)) {
                                earliest = commentAt;
                            }
                        }
                    }
                    totalCommentCount += reviewCommentCount;
                    totalReviewerCount += reviewCommenters.size();

                    if (earliest != null && mr.has("created_at")) {
                        ZonedDateTime createdAt = ZonedDateTime.parse(mr.getString("created_at"));
                        long waitSeconds = Duration.between(createdAt, earliest).toSeconds();
                        if (waitSeconds < 0)
                            waitSeconds = 0;
                        firstReviewWaitSum += waitSeconds;
                        validFirstReviewMRCount++;
                    }
                }
            }

            if (mr.has("merged_at") && !mr.isNull("merged_at")) {
                ZonedDateTime createdAt = ZonedDateTime.parse(mr.getString("created_at"));
                ZonedDateTime mergedAt = ZonedDateTime.parse(mr.getString("merged_at"));
                createdLeadTimeSum += Duration.between(createdAt, mergedAt).toSeconds();
                createdAndMergedCount++;
            }
        }

        double avgHoursToMergeByCreated = (createdAndMergedCount > 0)
                ? createdLeadTimeSum / (double) createdAndMergedCount / 3600.0
                : 0;
        double avgHoursToFirstReview = (validFirstReviewMRCount > 0)
                ? firstReviewWaitSum / (double) validFirstReviewMRCount / 3600.0
                : 0;

        // (2) マージされたMR (merged_after/before でフィルタ)
        List<JSONObject> allMergedMRs = prefetchedMergedMRs != null
                ? prefetchedMergedMRs
                : fetchAllMergedMergeRequests(projectId, since, until, refName);
        MergeRequestStatsDTO mergedStats = calculateMergedStats(userName, allMergedMRs, startRange, endRange);

        return new MergeRequestStatsDTO(
                totalCreatedCount,
                createdAndMergedCount,
                mergedStats.getMergedCount(),
                totalCommentCount,
                totalReviewerCount,
                avgHoursToMergeByCreated,
                mergedStats.getAvgHoursToMergeByMerged(),
                avgHoursToFirstReview,
                0, // レビュー数は別で計算
                0 // コメント数も別で計算
        );
    }

    public MergeRequestActivityPeriodDTO getMergeRequestActivityPeriod(String projectId, String refName) {
        return getMergeRequestActivityPeriod(projectId, refName, null);
    }

    public MergeRequestActivityPeriodDTO getMergeRequestActivityPeriod(String projectId, String refName, String userName) {
        List<JSONObject> mergeRequests = new ArrayList<>();
        fetchPaginatedMergeRequests(projectId, null, null, userName, refName, mergeRequests, "created", "all");

        ZonedDateTime firstCreatedAt = null;
        ZonedDateTime lastMergedAt = null;

        for (JSONObject mr : mergeRequests) {
            if (mr.has("created_at") && !mr.isNull("created_at")) {
                ZonedDateTime createdAt = ZonedDateTime.parse(mr.getString("created_at"));
                if (firstCreatedAt == null || createdAt.isBefore(firstCreatedAt)) {
                    firstCreatedAt = createdAt;
                }
            }

            if (mr.has("merged_at") && !mr.isNull("merged_at")) {
                ZonedDateTime mergedAt = ZonedDateTime.parse(mr.getString("merged_at"));
                if (lastMergedAt == null || mergedAt.isAfter(lastMergedAt)) {
                    lastMergedAt = mergedAt;
                }
            }
        }

        LocalDate today = LocalDate.now();
        if (lastMergedAt == null) {
            MergeRequestActivityPeriodDTO commitPeriod = commitService.getCommitActivityPeriod(projectId, userName, refName);
            if (commitPeriod != null) {
                return commitPeriod;
            }
        }

        LocalDate periodEnd = lastMergedAt != null ? lastMergedAt.toLocalDate() : today;
        LocalDate periodStart = firstCreatedAt != null ? firstCreatedAt.toLocalDate() : periodEnd;

        return new MergeRequestActivityPeriodDTO(
                firstCreatedAt != null ? firstCreatedAt.toLocalDate() : null,
                lastMergedAt != null ? lastMergedAt.toLocalDate() : null,
                periodStart,
                periodEnd);
    }

    /**
     * プロジェクト全体のMRリストを一度だけ取得するためのメソッド
     */
    public List<JSONObject> fetchAllCreatedMergeRequests(String projectId, String since, String until,
            String refName) {
        String apiSince = since;
        String apiUntil = until;
        if (apiUntil != null && apiUntil.length() == 10) {
            apiUntil = until + "T23:59:59Z";
        }
        if (apiSince != null && apiSince.length() == 10) {
            apiSince = since + "T00:00:00Z";
        }

        List<JSONObject> allCreatedMRs = new ArrayList<>();
        fetchPaginatedMergeRequests(projectId, apiSince, apiUntil, null, refName, allCreatedMRs, "created", "all");
        return allCreatedMRs;
    }

    /**
     * プロジェクト全体で一括取得済みのMRから、作成者別のMR統計を計算する。
     */
    public Map<String, MergeRequestStatsDTO> calculateAuthorStatsByUser(
            String projectId,
            Collection<String> userNames,
            List<JSONObject> allCreatedMRs,
            ZonedDateTime startRange,
            ZonedDateTime endRange) {
        return calculateAuthorStatsByUser(projectId, userNames, allCreatedMRs, startRange, endRange, null);
    }

    public Map<String, MergeRequestStatsDTO> calculateAuthorStatsByUser(
            String projectId,
            Collection<String> userNames,
            List<JSONObject> allCreatedMRs,
            ZonedDateTime startRange,
            ZonedDateTime endRange,
            Map<Integer, JSONArray> prefetchedNotesByMrIid) {
        Map<String, AuthorMergeStatsAccumulator> statsByUser = new HashMap<>();
        if (userNames == null || userNames.isEmpty()) {
            return Map.of();
        }

        for (String userName : userNames) {
            if (userName != null && !userName.isBlank()) {
                statsByUser.put(userName.toUpperCase(Locale.ROOT), new AuthorMergeStatsAccumulator());
            }
        }

        for (JSONObject mr : allCreatedMRs) {
            String author = mrAuthorUsername(mr);
            if (author.isBlank()) {
                continue;
            }
            AuthorMergeStatsAccumulator stats = statsByUser.get(author.toUpperCase(Locale.ROOT));
            if (stats == null || !isMrDateInRange(mr, "created_at", startRange, endRange)) {
                continue;
            }

            stats.totalCreatedCount++;

            if (fetchNotesEnabled && mr.has("iid")) {
                JSONArray notes = notesForMr(projectId, mr, prefetchedNotesByMrIid);
                int reviewCommentCount = 0;
                Set<String> reviewCommenters = new HashSet<>();
                ZonedDateTime earliest = null;

                for (int i = 0; i < notes.length(); i++) {
                    JSONObject note = notes.getJSONObject(i);
                    if (!isHumanReviewComment(note, author)
                            || !note.has("created_at")
                            || note.isNull("created_at")) {
                        continue;
                    }
                    ZonedDateTime commentAt = ZonedDateTime.parse(note.getString("created_at"));
                    if (startRange != null && commentAt.isBefore(startRange)) {
                        continue;
                    }
                    if (endRange != null && commentAt.isAfter(endRange)) {
                        continue;
                    }

                    reviewCommentCount++;
                    JSONObject noteAuthor = note.optJSONObject("author");
                    if (noteAuthor != null) {
                        String noteAuthorUsername = noteAuthor.optString("username", "");
                        if (!noteAuthorUsername.isBlank()) {
                            reviewCommenters.add(noteAuthorUsername.toLowerCase(Locale.ROOT));
                        }
                    }
                    if (earliest == null || commentAt.isBefore(earliest)) {
                        earliest = commentAt;
                    }
                }

                stats.totalCommentCount += reviewCommentCount;
                stats.totalReviewerCount += reviewCommenters.size();
                if (earliest != null && mr.has("created_at")) {
                    ZonedDateTime createdAt = ZonedDateTime.parse(mr.getString("created_at"));
                    long waitSeconds = Duration.between(createdAt, earliest).toSeconds();
                    stats.firstReviewWaitSum += Math.max(waitSeconds, 0);
                    stats.validFirstReviewMRCount++;
                }
            }

            if (mr.has("merged_at") && !mr.isNull("merged_at")) {
                ZonedDateTime createdAt = ZonedDateTime.parse(mr.getString("created_at"));
                ZonedDateTime mergedAt = ZonedDateTime.parse(mr.getString("merged_at"));
                stats.createdLeadTimeSum += Duration.between(createdAt, mergedAt).toSeconds();
                stats.createdAndMergedCount++;
            }
        }

        Map<String, MergeRequestStatsDTO> result = new HashMap<>();
        for (Map.Entry<String, AuthorMergeStatsAccumulator> entry : statsByUser.entrySet()) {
            AuthorMergeStatsAccumulator stats = entry.getValue();
            double avgHoursToMergeByCreated = stats.createdAndMergedCount > 0
                    ? stats.createdLeadTimeSum / (double) stats.createdAndMergedCount / 3600.0
                    : 0;
            double avgHoursToFirstReview = stats.validFirstReviewMRCount > 0
                    ? stats.firstReviewWaitSum / (double) stats.validFirstReviewMRCount / 3600.0
                    : 0;
            result.put(entry.getKey(), new MergeRequestStatsDTO(
                    stats.totalCreatedCount,
                    stats.createdAndMergedCount,
                    0,
                    stats.totalCommentCount,
                    stats.totalReviewerCount,
                    avgHoursToMergeByCreated,
                    0,
                    avgHoursToFirstReview,
                    0,
                    0));
        }

        return result;
    }

    private boolean isMrDateInRange(JSONObject mr, String fieldName, ZonedDateTime startRange,
            ZonedDateTime endRange) {
        if (!mr.has(fieldName) || mr.isNull(fieldName)) {
            return false;
        }
        ZonedDateTime value = ZonedDateTime.parse(mr.getString(fieldName));
        if (startRange != null && value.isBefore(startRange)) {
            return false;
        }
        return endRange == null || !value.isAfter(endRange);
    }

    private static class AuthorMergeStatsAccumulator {
        int totalCreatedCount;
        int createdAndMergedCount;
        long createdLeadTimeSum;
        int totalReviewerCount;
        int totalCommentCount;
        long firstReviewWaitSum;
        int validFirstReviewMRCount;
    }

    /**
     * プロジェクト全体のMRリストを一度だけ取得するためのメソッド
     */
    public List<JSONObject> fetchAllUpdatedMergeRequests(String projectId, String since, String until,
            String refName) {
        String apiSince = since;
        String apiUntil = until;
        if (apiUntil != null && apiUntil.length() == 10) {
            apiUntil = until + "T23:59:59Z";
        }
        if (apiSince != null && apiSince.length() == 10) {
            apiSince = since + "T00:00:00Z";
        }

        List<JSONObject> allUpdatedMRs = new ArrayList<>();
        fetchPaginatedMergeRequests(projectId, apiSince, apiUntil, null, refName, allUpdatedMRs, "updated", "all");
        return allUpdatedMRs;
    }

    /**
     * プロジェクト全体のマージ済みMRリストを一度だけ取得するためのメソッド
     */
    public List<JSONObject> fetchAllMergedMergeRequests(String projectId, String since, String until,
            String refName) {
        String apiSince = since;
        String apiUntil = until;
        if (apiUntil != null && apiUntil.length() == 10) {
            apiUntil = until + "T23:59:59Z";
        }
        if (apiSince != null && apiSince.length() == 10) {
            apiSince = since + "T00:00:00Z";
        }

        List<JSONObject> allMergedMRs = new ArrayList<>();
        fetchPaginatedMergeRequests(projectId, apiSince, apiUntil, null, refName, allMergedMRs, "merged", "merged");
        return allMergedMRs;
    }

    /**
     * 一括取得済みのマージ済みMRから、作成者別のマージ統計を計算する。
     */
    public Map<String, MergeRequestStatsDTO> calculateMergedStatsByAuthor(
            Collection<String> userNames,
            List<JSONObject> mergedMRs,
            ZonedDateTime startRange,
            ZonedDateTime endRange) {
        Map<String, MergedStatsAccumulator> statsByUser = new HashMap<>();
        boolean restrictToUsers = userNames != null && !userNames.isEmpty();

        if (restrictToUsers) {
            for (String userName : userNames) {
                if (userName != null && !userName.isBlank()) {
                    statsByUser.put(userName.toUpperCase(Locale.ROOT), new MergedStatsAccumulator());
                }
            }
        }

        for (JSONObject mr : mergedMRs) {
            if (!isMrDateInRange(mr, "merged_at", startRange, endRange)) {
                continue;
            }

            String author = mrAuthorUsername(mr);
            if (author.isBlank() || !mr.has("created_at") || mr.isNull("created_at")) {
                continue;
            }

            String key = author.toUpperCase(Locale.ROOT);
            MergedStatsAccumulator stats = statsByUser.get(key);
            if (stats == null) {
                if (restrictToUsers) {
                    continue;
                }
                stats = new MergedStatsAccumulator();
                statsByUser.put(key, stats);
            }

            ZonedDateTime createdAt = ZonedDateTime.parse(mr.getString("created_at"));
            ZonedDateTime mergedAt = ZonedDateTime.parse(mr.getString("merged_at"));
            stats.mergedLeadTimeSum += Duration.between(createdAt, mergedAt).toSeconds();
            stats.mergedCount++;
        }

        Map<String, MergeRequestStatsDTO> result = new HashMap<>();
        for (Map.Entry<String, MergedStatsAccumulator> entry : statsByUser.entrySet()) {
            result.put(entry.getKey(), toMergedStatsDTO(entry.getValue()));
        }
        return result;
    }

    public MergeRequestStatsDTO calculateMergedStats(
            String userName,
            List<JSONObject> mergedMRs,
            ZonedDateTime startRange,
            ZonedDateTime endRange) {
        if (userName != null && !userName.isBlank()) {
            return calculateMergedStatsByAuthor(List.of(userName), mergedMRs, startRange, endRange)
                    .getOrDefault(userName.toUpperCase(Locale.ROOT), emptyStats());
        }

        MergedStatsAccumulator stats = new MergedStatsAccumulator();
        for (JSONObject mr : mergedMRs) {
            if (!isMrDateInRange(mr, "merged_at", startRange, endRange)
                    || !mr.has("created_at")
                    || mr.isNull("created_at")) {
                continue;
            }

            ZonedDateTime createdAt = ZonedDateTime.parse(mr.getString("created_at"));
            ZonedDateTime mergedAt = ZonedDateTime.parse(mr.getString("merged_at"));
            stats.mergedLeadTimeSum += Duration.between(createdAt, mergedAt).toSeconds();
            stats.mergedCount++;
        }
        return toMergedStatsDTO(stats);
    }

    public MergeRequestStatsDTO calculateReviewStatsFromFetchedLists(
            String projectId,
            String userName,
            List<JSONObject> allUpdatedMRs,
            ZonedDateTime startRange,
            ZonedDateTime endRange,
            Map<Integer, JSONArray> prefetchedNotesByMrIid) {

        if (userName == null || userName.isBlank()) {
            return emptyStats();
        }

        int givenReviewCount = 0;
        int givenCommentCount = 0;

        for (JSONObject mr : allUpdatedMRs) {
            String author = mr.has("author") && !mr.isNull("author")
                    ? mr.getJSONObject("author").optString("username", "")
                    : "";
            boolean isMyOwnMr = userName != null && userName.equalsIgnoreCase(author);

            boolean reviewedByMe = false;

            if (mr.has("iid")) {
                if (fetchNotesEnabled) {
                    JSONArray notes = notesForMr(projectId, mr, prefetchedNotesByMrIid);
                    boolean commentedByMe = false;
                    for (int i = 0; i < notes.length(); i++) {
                        JSONObject note = notes.getJSONObject(i);
                        if (note.optBoolean("system", false))
                            continue;

                        JSONObject noteAuthor = note.optJSONObject("author");
                        if (!isMyOwnMr && noteAuthor != null && userName != null
                                && userName.equalsIgnoreCase(noteAuthor.optString("username", ""))) {
                            // コメントが期間内かチェック
                            if (note.has("created_at") && !note.isNull("created_at")) {
                                ZonedDateTime commentAt = ZonedDateTime.parse(note.getString("created_at"));
                                if (startRange != null && commentAt.isBefore(startRange))
                                    continue;
                                if (endRange != null && commentAt.isAfter(endRange))
                                    continue;
                            }
                            givenCommentCount++;
                            commentedByMe = true;
                        }
                    }
                    if (!isMyOwnMr && commentedByMe) {
                        reviewedByMe = true; // 他人のMRにコメントを残していれば実質レビューしたと扱う
                    }
                }
            }
            if (reviewedByMe) {
                givenReviewCount++;
            }
        }

        return new MergeRequestStatsDTO(
                0, // createdCountは別で計算
                0, // createdAndMergedCountは別で計算
                0,
                0,
                0, // totalCommentCount and totalReviewerCount are calculated elsewhere
                0, // avgHoursToMergeByCreatedは別で計算
                0,
                0, // avgHoursToFirstReviewは別で計算
                givenReviewCount, givenCommentCount);
    }

    public Map<String, MergeRequestStatsDTO> calculateReviewStatsByReviewer(
            String projectId,
            Collection<String> userNames,
            List<JSONObject> allUpdatedMRs,
            ZonedDateTime startRange,
            ZonedDateTime endRange,
            Map<Integer, JSONArray> prefetchedNotesByMrIid) {
        Map<String, ReviewStatsAccumulator> statsByUser = new HashMap<>();
        boolean restrictToUsers = userNames != null && !userNames.isEmpty();

        if (restrictToUsers) {
            for (String userName : userNames) {
                if (userName != null && !userName.isBlank()) {
                    statsByUser.put(userName.toUpperCase(Locale.ROOT), new ReviewStatsAccumulator());
                }
            }
        }

        for (JSONObject mr : allUpdatedMRs) {
            String author = mrAuthorUsername(mr);
            if (!mr.has("iid") || mr.isNull("iid") || !fetchNotesEnabled) {
                continue;
            }

            Set<String> reviewersForMr = new HashSet<>();
            JSONArray notes = notesForMr(projectId, mr, prefetchedNotesByMrIid);
            for (int i = 0; i < notes.length(); i++) {
                JSONObject note = notes.getJSONObject(i);
                if (note.optBoolean("system", false)) {
                    continue;
                }

                JSONObject noteAuthor = note.optJSONObject("author");
                if (noteAuthor == null) {
                    continue;
                }

                String reviewer = noteAuthor.optString("username", "");
                if (reviewer.isBlank() || reviewer.equalsIgnoreCase(author)) {
                    continue;
                }

                if (note.has("created_at") && !note.isNull("created_at")) {
                    ZonedDateTime commentAt = ZonedDateTime.parse(note.getString("created_at"));
                    if (startRange != null && commentAt.isBefore(startRange)) {
                        continue;
                    }
                    if (endRange != null && commentAt.isAfter(endRange)) {
                        continue;
                    }
                }

                String key = reviewer.toUpperCase(Locale.ROOT);
                ReviewStatsAccumulator stats = statsByUser.get(key);
                if (stats == null) {
                    if (restrictToUsers) {
                        continue;
                    }
                    stats = new ReviewStatsAccumulator();
                    statsByUser.put(key, stats);
                }

                stats.givenCommentCount++;
                reviewersForMr.add(key);
            }

            for (String reviewer : reviewersForMr) {
                ReviewStatsAccumulator stats = statsByUser.get(reviewer);
                if (stats != null) {
                    stats.givenReviewCount++;
                }
            }
        }

        Map<String, MergeRequestStatsDTO> result = new HashMap<>();
        for (Map.Entry<String, ReviewStatsAccumulator> entry : statsByUser.entrySet()) {
            ReviewStatsAccumulator stats = entry.getValue();
            result.put(entry.getKey(), new MergeRequestStatsDTO(
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    stats.givenReviewCount,
                    stats.givenCommentCount));
        }
        return result;
    }

    public MergeRequestStatsDTO calculateReviewStatsFromFetchedLists(
            String projectId,
            String userName,
            List<JSONObject> allUpdatedMRs,
            ZonedDateTime startRange,
            ZonedDateTime endRange) {
        return calculateReviewStatsFromFetchedLists(projectId, userName, allUpdatedMRs, startRange, endRange, null);
    }

    private MergeRequestStatsDTO toMergedStatsDTO(MergedStatsAccumulator stats) {
        double avgHoursToMergeByMerged = stats.mergedCount > 0
                ? stats.mergedLeadTimeSum / (double) stats.mergedCount / 3600.0
                : 0;
        return new MergeRequestStatsDTO(
                0,
                0,
                stats.mergedCount,
                0,
                0,
                0,
                avgHoursToMergeByMerged,
                0,
                0,
                0);
    }

    private MergeRequestStatsDTO emptyStats() {
        return new MergeRequestStatsDTO();
    }

    private static class MergedStatsAccumulator {
        int mergedCount;
        long mergedLeadTimeSum;
    }

    private static class ReviewStatsAccumulator {
        int givenReviewCount;
        int givenCommentCount;
    }

    private String mrAuthorUsername(JSONObject mr) {
        JSONObject author = mr.optJSONObject("author");
        return author != null ? author.optString("username", "") : "";
    }

    private boolean isHumanReviewComment(JSONObject note, String mrAuthorUsername) {
        if (note == null || note.optBoolean("system", false)) {
            return false;
        }

        JSONObject noteAuthor = note.optJSONObject("author");
        if (noteAuthor == null || isBotAuthor(noteAuthor)) {
            return false;
        }

        String noteAuthorUsername = noteAuthor.optString("username", "");
        return mrAuthorUsername == null
                || mrAuthorUsername.isBlank()
                || !mrAuthorUsername.equalsIgnoreCase(noteAuthorUsername);
    }

    private boolean isBotAuthor(JSONObject author) {
        if (author.optBoolean("bot", false)) {
            return true;
        }

        String username = author.optString("username", "").toLowerCase(Locale.ROOT);
        String name = author.optString("name", "").toLowerCase(Locale.ROOT);
        return isBotLike(username) || isBotLike(name);
    }

    private boolean isBotLike(String value) {
        return value.contains("[bot]")
                || value.endsWith("-bot")
                || value.endsWith("_bot")
                || value.endsWith(" bot");
    }

    private void fetchPaginatedMergeRequests(String projectId, String since, String until, String authorUserName,
            String refName, List<JSONObject> allMergeRequests, String filterType, String state) {
        if (gitHubRepositoryService.supports(projectId)) {
            allMergeRequests.addAll(gitHubRepositoryService.getPullRequests(
                    projectId, since, until, authorUserName, refName, filterType, state));
            return;
        }
        Set<String> seenMergeRequestIds = new HashSet<>();
        if (refName != null && !refName.isEmpty()) {
            fetchPaginatedMergeRequests(projectId, since, until, authorUserName, "target_branch", refName,
                    allMergeRequests, filterType, state, seenMergeRequestIds);
            fetchPaginatedMergeRequests(projectId, since, until, authorUserName, "source_branch", refName,
                    allMergeRequests, filterType, state, seenMergeRequestIds);
            return;
        }

        fetchPaginatedMergeRequests(projectId, since, until, authorUserName, null, null,
                allMergeRequests, filterType, state, seenMergeRequestIds);
    }

    private void fetchPaginatedMergeRequests(String projectId, String since, String until, String authorUserName,
            String branchParamName, String branchName, List<JSONObject> allMergeRequests, String filterType,
            String state, Set<String> seenMergeRequestIds) {
        int page = 1;
        int perPage = 100;

        try {
            while (true) {
                StringBuilder urlBuilder = new StringBuilder(String.format(
                        "%s/projects/%s/merge_requests?page=%d&per_page=%d",
                        base, urlEncode(projectId), page, perPage));

                if (state != null && !state.isEmpty()) {
                    urlBuilder.append("&state=").append(state);
                }

                if (branchParamName != null && branchName != null && !branchName.isEmpty()) {
                    urlBuilder.append("&").append(branchParamName).append("=").append(urlEncode(branchName));
                }

                // 片方だけでもフィルタするように修正
                if ("created".equals(filterType)) {
                    if (since != null && !since.isEmpty())
                        urlBuilder.append("&created_after=").append(urlEncode(since));
                    if (until != null && !until.isEmpty())
                        urlBuilder.append("&created_before=").append(urlEncode(until));
                } else if ("merged".equals(filterType)) {
                    if (since != null && !since.isEmpty())
                        urlBuilder.append("&merged_after=").append(urlEncode(since));
                    if (until != null && !until.isEmpty())
                        urlBuilder.append("&merged_before=").append(urlEncode(until));
                } else if ("updated".equals(filterType)) {
                    if (since != null && !since.isEmpty())
                        urlBuilder.append("&updated_after=").append(urlEncode(since));
                    if (until != null && !until.isEmpty())
                        urlBuilder.append("&updated_before=").append(urlEncode(until));
                }

                if (authorUserName != null && !authorUserName.isEmpty()) {
                    urlBuilder.append("&author_username=").append(urlEncode(authorUserName));
                }

                String url = urlBuilder.toString();
                RequestEntity<Void> req = new RequestEntity<>(headers(), HttpMethod.GET, URI.create(url));
                ResponseEntity<String> res = rt.exchange(req, String.class);
                JSONArray mergeRequests = new JSONArray(res.getBody());

                if (mergeRequests.isEmpty())
                    break;

                for (int i = 0; i < mergeRequests.length(); i++) {
                    JSONObject mergeRequest = mergeRequests.getJSONObject(i);
                    String mergeRequestId = mergeRequest.optString("id", mergeRequest.optString("iid", ""));
                    if (mergeRequestId.isBlank() || seenMergeRequestIds.add(mergeRequestId)) {
                        allMergeRequests.add(mergeRequest);
                    }
                }

                if (mergeRequests.length() < perPage)
                    break;
                page++;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public Map<Integer, JSONArray> fetchNotesByMergeRequest(String projectId, List<JSONObject> mergeRequests) {
        Map<Integer, JSONArray> notesByMrIid = new HashMap<>();
        if (!fetchNotesEnabled || mergeRequests == null || mergeRequests.isEmpty()) {
            return notesByMrIid;
        }

        int githubDetailLimit = gitHubRepositoryService.supports(projectId)
                ? gitHubRepositoryService.getReviewDetailLimit()
                : Integer.MAX_VALUE;
        List<Integer> detailIids = new ArrayList<>();
        for (JSONObject mr : mergeRequests) {
            if (!mr.has("iid") || mr.isNull("iid")) {
                continue;
            }
            int iid = mr.getInt("iid");
            if (notesByMrIid.containsKey(iid)) {
                continue;
            }
            notesByMrIid.put(iid, new JSONArray());
            if (detailIids.size() < githubDetailLimit) {
                detailIids.add(iid);
            }
        }
        if (gitHubRepositoryService.supports(projectId)) {
            notesByMrIid.putAll(gitHubRepositoryService.getPullRequestNotesBatch(projectId, detailIids));
        } else {
            for (int iid : detailIids) {
                notesByMrIid.put(iid, fetchNotes(projectId, iid));
            }
        }
        return notesByMrIid;
    }

    private JSONArray notesForMr(String projectId, JSONObject mr, Map<Integer, JSONArray> prefetchedNotesByMrIid) {
        if (!mr.has("iid") || mr.isNull("iid")) {
            return new JSONArray();
        }
        int iid = mr.getInt("iid");
        if (prefetchedNotesByMrIid != null) {
            JSONArray notes = prefetchedNotesByMrIid.get(iid);
            if (notes == null) {
                throw new IllegalStateException("MR notes were not prefetched for " + iid);
            }
            return notes;
        }
        return fetchNotes(projectId, iid);
    }

    private JSONArray fetchNotes(String projectId, int mrIid) {
        if (gitHubRepositoryService.supports(projectId)) {
            return gitHubRepositoryService.getPullRequestNotes(projectId, mrIid);
        }
        JSONArray allNotes = new JSONArray();
        int page = 1;
        int perPage = 100;
        try {
            while (true) {
                String url = String.format("%s/projects/%s/merge_requests/%d/notes?page=%d&per_page=%d",
                        base, urlEncode(projectId), mrIid, page, perPage);

                RequestEntity<Void> req = new RequestEntity<>(headers(), HttpMethod.GET, URI.create(url));
                ResponseEntity<String> res = rt.exchange(req, String.class);
                JSONArray pageNotes = new JSONArray(res.getBody());

                for (int i = 0; i < pageNotes.length(); i++) {
                    allNotes.put(pageNotes.getJSONObject(i));
                }
                if (pageNotes.length() < perPage)
                    break;
                page++;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return allNotes;
    }

    private String urlEncode(String value) {
        if (value == null)
            return "";
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    @AllArgsConstructor
    public static class MergeRequestDiff {
        public final String diff;
        public final int lineCount;
    }

    /**
     * Merge Requestの差分(Diff)を取得します。
     * 行数制限を設けて、Gemini APIのコンテキスト制限を超えないようにします。
     */
    public MergeRequestDiff getMergeRequestDiff(String projectId, int mrIid) {
        if (gitHubRepositoryService.supports(projectId)) {
            GitHubRepositoryService.RepositoryDiff diff = gitHubRepositoryService.getPullRequestDiff(projectId, mrIid);
            return new MergeRequestDiff(diff.diff(), diff.lineCount());
        }
        try {
            String url = String.format("%s/projects/%s/merge_requests/%d/diffs",
                    base, urlEncode(projectId), mrIid);

            RequestEntity<Void> req = new RequestEntity<>(headers(), HttpMethod.GET, URI.create(url));
            ResponseEntity<String> res = rt.exchange(req, String.class);
            JSONArray diffs = new JSONArray(res.getBody());

            StringBuilder diffBuilder = new StringBuilder();
            int maxLines = 5000; // 合計最大5000行に制限
            int currentLines = 0;

            for (int i = 0; i < diffs.length(); i++) {
                JSONObject diff = diffs.getJSONObject(i);
                String fileName = diff.optString("new_path", diff.optString("old_path", "unknown"));

                // 自動生成ファイルやバイナリファイルはスキップ
                if (fileName.endsWith(".lock") || fileName.endsWith("-lock.json") || fileName.endsWith(".bin")) {
                    continue;
                }

                String diffText = diff.optString("diff", "");
                String[] lines = diffText.split("\n");

                diffBuilder.append("File: ").append(fileName).append("\n");
                for (String line : lines) {
                    if (currentLines >= maxLines) {
                        diffBuilder.append("... (diff truncated)\n");
                        return new MergeRequestDiff(diffBuilder.toString(), currentLines);
                    }
                    diffBuilder.append(line).append("\n");
                    currentLines++;
                }
                diffBuilder.append("\n");
            }
            return new MergeRequestDiff(diffBuilder.toString(), currentLines);
        } catch (Exception e) {
            e.printStackTrace();
            return new MergeRequestDiff("", 0);
        }
    }
}
