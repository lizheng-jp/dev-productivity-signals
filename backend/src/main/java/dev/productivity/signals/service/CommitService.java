package dev.productivity.signals.service;

import dev.productivity.signals.dto.CommitMetricsResponseDTO;
import dev.productivity.signals.dto.MergeRequestActivityPeriodDTO;
import dev.productivity.signals.dto.WeeklyBreakdownDTO;
import dev.productivity.signals.dto.CommitStatsDTO;

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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class CommitService {

    private static final Pattern EMPLOYEE_NUMBER_PATTERN = Pattern.compile("\\d+");

    private final RestTemplate rt;
    private final GitHubRepositoryService gitHubRepositoryService;

    @Value("${gitlab.api.url}")
    private String base;

    @Value("${gitlab.api.token}")
    private String token;

    public CommitService(RestTemplate rt, GitHubRepositoryService gitHubRepositoryService) {
        this.rt = rt;
        this.gitHubRepositoryService = gitHubRepositoryService;
    }

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.set("PRIVATE-TOKEN", token);
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        return h;
    }

    /**
     * 指定されたプロジェクト・期間・ユーザーの総コミット数と週次内訳を取得
     *
     * @param projectId GitLabのプロジェクトID
     * @param since     ISO 8601形式の開始日時
     * @param until     ISO 8601形式の終了日時
     * @param author    対象ユーザー（nullまたは空で全ユーザー）
     * @param refName   ブランチ名またはタグ名（nullまたは空で全ブランチ）
     * @return CommitCountResponseDTO 総コミット数と週次内訳
     */
    public CommitMetricsResponseDTO getCommitCount(String projectId, String author, String since, String until,
            String refName) {
        // GitLabから取得したコミットの完全なJSONオブジェクトを保持するリスト
        List<JSONObject> fetchedCommits = new ArrayList<>();

        String apiSince = startOfDayIfDate(since);
        String apiUntil = endOfDayIfDate(until);

        // GitLabのauthor検索は氏名・メール・ユーザー名の不一致で漏れるため、取得後にローカルで照合する
        fetchAllCommits(projectId, apiSince, apiUntil, refName, fetchedCommits);
        LocalDate sinceDate = parseLocalDate(since);
        LocalDate untilDate = parseLocalDate(until);
        List<JSONObject> dateFilteredCommits = filterCommitsByDate(fetchedCommits, sinceDate, untilDate);
        List<JSONObject> allCommits = isBlank(author)
                ? dateFilteredCommits
                : dateFilteredCommits.stream()
                        .filter(commit -> isCommitAuthoredBy(commit, author))
                        .collect(Collectors.toList());

        // コミット日時を抽出
        List<String> commitDates = allCommits.stream()
                .map(commit -> commit.getString("committed_date"))
                .collect(Collectors.toList());

        // 週次内訳を計算
        List<WeeklyBreakdownDTO> weeklyBreakdown = calculateWeeklyBreakdown(commitDates, sinceDate, untilDate);

        // コード行数を集計
        int totalAdditions = 0;
        int totalDeletions = 0;
        int totalLines = 0;

        for (JSONObject commit : allCommits) {
            if (commit.has("stats")) {
                JSONObject stats = commit.getJSONObject("stats");
                totalAdditions += stats.getInt("additions");
                totalDeletions += stats.getInt("deletions");
                totalLines += stats.getInt("total");
            }
        }

        CommitStatsDTO commitStats = new CommitStatsDTO(totalAdditions, totalDeletions, totalLines);

        // レスポンスDTOを作成して返す
        return new CommitMetricsResponseDTO(allCommits.size(), weeklyBreakdown, commitStats);
    }

    public MergeRequestActivityPeriodDTO getCommitActivityPeriod(String projectId, String author, String refName) {
        List<JSONObject> fetchedCommits = new ArrayList<>();
        fetchAllCommits(projectId, null, null, refName, fetchedCommits);

        List<JSONObject> commits = isBlank(author)
                ? fetchedCommits
                : fetchedCommits.stream()
                        .filter(commit -> isCommitAuthoredBy(commit, author))
                        .collect(Collectors.toList());

        ZonedDateTime firstCommittedAt = null;
        ZonedDateTime lastCommittedAt = null;

        for (JSONObject commit : commits) {
            String committedDate = commit.optString("committed_date", "");
            if (committedDate.isBlank()) {
                committedDate = commit.optString("authored_date", "");
            }
            if (committedDate.isBlank()) {
                continue;
            }

            ZonedDateTime committedAt = ZonedDateTime.parse(committedDate);
            if (firstCommittedAt == null || committedAt.isBefore(firstCommittedAt)) {
                firstCommittedAt = committedAt;
            }
            if (lastCommittedAt == null || committedAt.isAfter(lastCommittedAt)) {
                lastCommittedAt = committedAt;
            }
        }

        if (firstCommittedAt == null || lastCommittedAt == null) {
            return null;
        }

        return new MergeRequestActivityPeriodDTO(
                firstCommittedAt.toLocalDate(),
                lastCommittedAt.toLocalDate(),
                firstCommittedAt.toLocalDate(),
                lastCommittedAt.toLocalDate());
    }

    public Set<String> getActiveCommitUserCodes(String projectId, Collection<String> candidateUserCodes,
            String since, String until, String refName) {
        Set<String> activeUserCodes = new HashSet<>();
        if (candidateUserCodes == null || candidateUserCodes.isEmpty()) {
            return activeUserCodes;
        }

        String apiSince = startOfDayIfDate(since);
        String apiUntil = endOfDayIfDate(until);
        LocalDate sinceDate = parseLocalDate(since);
        LocalDate untilDate = parseLocalDate(until);

        List<JSONObject> fetchedCommits = new ArrayList<>();
        fetchAllCommits(projectId, apiSince, apiUntil, refName, fetchedCommits);
        List<JSONObject> dateFilteredCommits = filterCommitsByDate(fetchedCommits, sinceDate, untilDate);

        for (String userCode : candidateUserCodes) {
            if (isBlank(userCode)) {
                continue;
            }
            boolean hasCommit = dateFilteredCommits.stream()
                    .anyMatch(commit -> isCommitAuthoredBy(commit, userCode));
            if (hasCommit) {
                activeUserCodes.add(userCode.toUpperCase(Locale.ROOT));
            }
        }
        return activeUserCodes;
    }

    public Map<String, CommitMetricsResponseDTO> getCommitMetricsByUser(
            String projectId,
            Collection<String> userCodes,
            String since,
            String until,
            String refName) {
        if (userCodes == null || userCodes.isEmpty()) {
            return Map.of();
        }

        String apiSince = startOfDayIfDate(since);
        String apiUntil = endOfDayIfDate(until);

        List<JSONObject> fetchedCommits = new ArrayList<>();
        fetchAllCommits(projectId, apiSince, apiUntil, refName, fetchedCommits);

        LocalDate sinceDate = parseLocalDate(since);
        LocalDate untilDate = parseLocalDate(until);
        List<JSONObject> dateFilteredCommits = filterCommitsByDate(fetchedCommits, sinceDate, untilDate);

        return userCodes.stream()
                .filter(userCode -> !isBlank(userCode))
                .collect(Collectors.toMap(
                        userCode -> userCode.toUpperCase(Locale.ROOT),
                        userCode -> calculateCommitMetrics(
                                dateFilteredCommits.stream()
                                        .filter(commit -> isCommitAuthoredBy(commit, userCode))
                                        .collect(Collectors.toList()),
                                sinceDate,
                                untilDate),
                        (left, right) -> left));
    }

    private CommitMetricsResponseDTO calculateCommitMetrics(List<JSONObject> commits, LocalDate sinceDate,
            LocalDate untilDate) {
        List<String> commitDates = commits.stream()
                .map(commit -> commit.getString("committed_date"))
                .collect(Collectors.toList());

        List<WeeklyBreakdownDTO> weeklyBreakdown = calculateWeeklyBreakdown(commitDates, sinceDate, untilDate);

        int totalAdditions = 0;
        int totalDeletions = 0;
        int totalLines = 0;

        for (JSONObject commit : commits) {
            if (commit.has("stats")) {
                JSONObject stats = commit.getJSONObject("stats");
                totalAdditions += stats.getInt("additions");
                totalDeletions += stats.getInt("deletions");
                totalLines += stats.getInt("total");
            }
        }

        return new CommitMetricsResponseDTO(
                commits.size(),
                weeklyBreakdown,
                new CommitStatsDTO(totalAdditions, totalDeletions, totalLines));
    }

    private LocalDate parseLocalDate(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            if (value.length() == 10) {
                return LocalDate.parse(value);
            }
            return ZonedDateTime.parse(value).toLocalDate();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String startOfDayIfDate(String value) {
        return value != null && value.length() == 10 ? value + "T00:00:00Z" : value;
    }

    private String endOfDayIfDate(String value) {
        return value != null && value.length() == 10 ? value + "T23:59:59Z" : value;
    }

    private List<JSONObject> filterCommitsByDate(List<JSONObject> commits, LocalDate sinceDate, LocalDate untilDate) {
        if (sinceDate == null && untilDate == null) {
            return commits;
        }
        return commits.stream()
                .filter(commit -> isCommitInDateRange(commit, sinceDate, untilDate))
                .collect(Collectors.toList());
    }

    private boolean isCommitInDateRange(JSONObject commit, LocalDate sinceDate, LocalDate untilDate) {
        String dateValue = commit.optString("committed_date", "");
        if (dateValue.isBlank()) {
            dateValue = commit.optString("authored_date", "");
        }
        if (dateValue.isBlank()) {
            return false;
        }

        try {
            LocalDate commitDate = ZonedDateTime.parse(dateValue).toLocalDate();
            if (sinceDate != null && commitDate.isBefore(sinceDate)) {
                return false;
            }
            return untilDate == null || !commitDate.isAfter(untilDate);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * GitLab APIからコミット情報を取得し、マップに格納するヘルパーメソッド
     */
    private void fetchAllCommits(String projectId, String since, String until, String refName,
            List<JSONObject> commitList) {
        if (gitHubRepositoryService.supports(projectId)) {
            commitList.addAll(gitHubRepositoryService.getCommits(projectId, since, until, refName));
            return;
        }
        if (isBlank(refName)) {
            fetchAllBranchCommits(projectId, since, until, commitList);
            return;
        }

        int page = 1;
        int perPage = 100;

        try {
            while (true) {
                StringBuilder urlBuilder = new StringBuilder(String.format(
                        "%s/projects/%s/repository/commits?page=%d&per_page=%d",
                        base,
                        URLEncoder.encode(projectId, StandardCharsets.UTF_8),
                        page,
                        perPage));
                urlBuilder.append("&with_stats=true"); // stats情報を取得するためにパラメータを追加

                if (since != null && !since.isEmpty()) {
                    urlBuilder.append("&since=").append(URLEncoder.encode(since, StandardCharsets.UTF_8));
                }
                if (until != null && !until.isEmpty()) {
                    urlBuilder.append("&until=").append(URLEncoder.encode(until, StandardCharsets.UTF_8));
                }
                if (refName != null && !refName.isEmpty()) {
                    urlBuilder.append("&ref_name=").append(URLEncoder.encode(refName, StandardCharsets.UTF_8));
                }

                String url = urlBuilder.toString();

                RequestEntity<Void> req = new RequestEntity<>(headers(), HttpMethod.GET, URI.create(url));
                ResponseEntity<String> res = rt.exchange(req, String.class);
                JSONArray commits = new JSONArray(res.getBody());

                if (commits.isEmpty()) {
                    break;
                }

                for (int i = 0; i < commits.length(); i++) {
                    JSONObject commit = commits.getJSONObject(i);
                    commitList.add(commit);
                }

                if (commits.length() < perPage) {
                    break;
                }
                page++;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void fetchAllBranchCommits(String projectId, String since, String until, List<JSONObject> commitList) {
        Set<String> seenCommitIds = new LinkedHashSet<>();
        List<String> branchNames = fetchBranchNames(projectId);

        if (branchNames.isEmpty()) {
            fetchCommitsForRef(projectId, since, until, null, commitList, seenCommitIds);
            return;
        }

        for (String branchName : branchNames) {
            if (isBlank(branchName)) {
                continue;
            }
            fetchCommitsForRef(projectId, since, until, branchName, commitList, seenCommitIds);
        }
    }

    private List<String> fetchBranchNames(String projectId) {
        List<String> branchNames = new ArrayList<>();
        int page = 1;
        int perPage = 100;

        try {
            while (true) {
                String url = String.format(
                        "%s/projects/%s/repository/branches?page=%d&per_page=%d",
                        base,
                        URLEncoder.encode(projectId, StandardCharsets.UTF_8),
                        page,
                        perPage);

                RequestEntity<Void> req = new RequestEntity<>(headers(), HttpMethod.GET, URI.create(url));
                ResponseEntity<String> res = rt.exchange(req, String.class);
                JSONArray branches = new JSONArray(res.getBody());

                if (branches.isEmpty()) {
                    break;
                }

                for (int i = 0; i < branches.length(); i++) {
                    String branchName = branches.getJSONObject(i).optString("name", "");
                    if (!branchName.isBlank()) {
                        branchNames.add(branchName);
                    }
                }

                if (branches.length() < perPage) {
                    break;
                }
                page++;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return branchNames;
    }

    private void fetchCommitsForRef(String projectId, String since, String until, String refName,
            List<JSONObject> commitList, Set<String> seenCommitIds) {
        List<JSONObject> branchCommits = new ArrayList<>();
        fetchCommitsPageByPage(projectId, since, until, refName, branchCommits);
        for (JSONObject commit : branchCommits) {
            String commitId = commit.optString("id", commit.optString("short_id", ""));
            if (commitId.isBlank() || seenCommitIds.add(commitId)) {
                commitList.add(commit);
            }
        }
    }

    private void fetchCommitsPageByPage(String projectId, String since, String until, String refName,
            List<JSONObject> commitList) {
        int page = 1;
        int perPage = 100;

        try {
            while (true) {
                StringBuilder urlBuilder = new StringBuilder(String.format(
                        "%s/projects/%s/repository/commits?page=%d&per_page=%d",
                        base,
                        URLEncoder.encode(projectId, StandardCharsets.UTF_8),
                        page,
                        perPage));
                urlBuilder.append("&with_stats=true");

                if (since != null && !since.isEmpty()) {
                    urlBuilder.append("&since=").append(URLEncoder.encode(since, StandardCharsets.UTF_8));
                }
                if (until != null && !until.isEmpty()) {
                    urlBuilder.append("&until=").append(URLEncoder.encode(until, StandardCharsets.UTF_8));
                }
                if (refName != null && !refName.isEmpty()) {
                    urlBuilder.append("&ref_name=").append(URLEncoder.encode(refName, StandardCharsets.UTF_8));
                }

                String url = urlBuilder.toString();

                RequestEntity<Void> req = new RequestEntity<>(headers(), HttpMethod.GET, URI.create(url));
                ResponseEntity<String> res = rt.exchange(req, String.class);
                JSONArray commits = new JSONArray(res.getBody());

                if (commits.isEmpty()) {
                    break;
                }

                for (int i = 0; i < commits.length(); i++) {
                    JSONObject commit = commits.getJSONObject(i);
                    commitList.add(commit);
                }

                if (commits.length() < perPage) {
                    break;
                }
                page++;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean isCommitAuthoredBy(JSONObject commit, String author) {
        String normalizedAuthor = normalizeUserIdentity(author);
        if (normalizedAuthor.isBlank()) {
            return false;
        }

        Set<String> authorEmployeeNumbers = extractEmployeeNumbers(normalizedAuthor);
        List<String> identities = new ArrayList<>(List.of(
                commit.optString("author_name", ""),
                commit.optString("author_email", ""),
                commit.optString("committer_name", ""),
                commit.optString("committer_email", "")));
        JSONObject authorObject = commit.optJSONObject("author");
        if (authorObject != null) {
            identities.add(authorObject.optString("username", authorObject.optString("login", "")));
        }
        return identities
                .stream()
                .map(this::normalizeUserIdentity)
                .anyMatch(value -> {
                    if (value.isBlank()) {
                        return false;
                    }
                    return value.equals(normalizedAuthor)
                            || emailLocalPart(value).equals(normalizedAuthor)
                            || hasSameEmployeeNumber(authorEmployeeNumbers, extractEmployeeNumbers(value));
                });
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String normalizeUserIdentity(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String emailLocalPart(String value) {
        int atIndex = value.indexOf('@');
        return atIndex > 0 ? value.substring(0, atIndex) : value;
    }

    private Set<String> extractEmployeeNumbers(String value) {
        Set<String> numbers = new LinkedHashSet<>();
        if (value == null) {
            return numbers;
        }

        Matcher matcher = EMPLOYEE_NUMBER_PATTERN.matcher(value);
        while (matcher.find()) {
            numbers.add(matcher.group());
        }
        return numbers;
    }

    private boolean hasSameEmployeeNumber(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        return left.stream().anyMatch(right::contains);
    }

    /**
     * コミット日時のコレクションから週次内訳を計算するヘルパーメソッド
     */
    private List<WeeklyBreakdownDTO> calculateWeeklyBreakdown(Collection<String> commitDates, LocalDate sinceDate,
            LocalDate untilDate) {
        // 期間が指定されていない場合は、コミットがあった日のみを元に集計する（旧ロジック）
        if (sinceDate == null || untilDate == null) {
            Map<LocalDate, Long> weeklyCounts = new TreeMap<>();
            for (String dateStr : commitDates) {
                LocalDate commitDate = ZonedDateTime.parse(dateStr).toLocalDate();
                LocalDate weekStart = commitDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
                weeklyCounts.merge(weekStart, 1L, Long::sum);
            }
            return weeklyCounts.entrySet().stream()
                    .map(entry -> {
                        LocalDate weekStart = entry.getKey();
                        LocalDate weekEnd = weekStart.plusDays(6);
                        return new WeeklyBreakdownDTO(
                                weekStart.format(DateTimeFormatter.ISO_LOCAL_DATE),
                                weekEnd.format(DateTimeFormatter.ISO_LOCAL_DATE),
                                entry.getValue());
                    })
                    .collect(Collectors.toList());
        }

        // --- 期間が指定されている場合の新しいロジック ---

        // 1. 週の開始日（日曜日）をキーとして、コミット数をカウントするマップを作成
        Map<LocalDate, Long> weeklyCounts = new TreeMap<>();

        // 2. 指定された期間内のすべての週をマップに初期値0で設定
        LocalDate weekIterator = sinceDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
        while (weekIterator.isBefore(untilDate.plusDays(1))) { // untilDateが含まれる週までループ
            weeklyCounts.put(weekIterator, 0L);
            weekIterator = weekIterator.plusWeeks(1);
        }

        // 3. コミット日に基づいてカウントをインクリメント
        for (String dateStr : commitDates) {
            LocalDate commitDate = ZonedDateTime.parse(dateStr).toLocalDate();
            LocalDate weekStart = commitDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));

            // マップにキーが存在する場合のみ（期間外のコミットは無視）
            if (weeklyCounts.containsKey(weekStart)) {
                weeklyCounts.merge(weekStart, 1L, Long::sum);
            }
        }

        // 4. マップからレスポンスDTOのリストに変換
        return weeklyCounts.entrySet().stream()
                .map(entry -> {
                    LocalDate weekStart = entry.getKey();
                    LocalDate weekEnd = weekStart.plusDays(6);
                    long count = entry.getValue();

                    // sinceDate に基づいて週の開始日を調整
                    if (weekStart.isBefore(sinceDate)) {
                        weekStart = sinceDate;
                    }

                    // untilDate に基づいて週の終了日を調整
                    if (weekEnd.isAfter(untilDate)) {
                        weekEnd = untilDate;
                    }

                    // 調整の結果、期間外になったものは除外
                    if (weekStart.isAfter(untilDate) || weekEnd.isBefore(sinceDate)) {
                        return null;
                    }

                    return new WeeklyBreakdownDTO(
                            weekStart.format(DateTimeFormatter.ISO_LOCAL_DATE),
                            weekEnd.format(DateTimeFormatter.ISO_LOCAL_DATE),
                            count);
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
}
