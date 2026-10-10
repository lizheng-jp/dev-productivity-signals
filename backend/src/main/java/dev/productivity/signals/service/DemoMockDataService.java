package dev.productivity.signals.service;

import dev.productivity.signals.dto.AiAnalysisProgressDTO;
import dev.productivity.signals.dto.AiEvaluationResponseDTO;
import dev.productivity.signals.dto.BranchDTO;
import dev.productivity.signals.dto.CommitMetricsResponseDTO;
import dev.productivity.signals.dto.CommitStatsDTO;
import dev.productivity.signals.dto.ComparisonAnalysisResponseDTO;
import dev.productivity.signals.dto.GroupCreateDTO;
import dev.productivity.signals.dto.GroupDTO;
import dev.productivity.signals.dto.IssueStatsDTO;
import dev.productivity.signals.dto.MergeRequestActivityPeriodDTO;
import dev.productivity.signals.dto.MergeRequestStatsDTO;
import dev.productivity.signals.dto.MetricWeightDTO;
import dev.productivity.signals.dto.ProjectDTO;
import dev.productivity.signals.dto.ProjectMemberGroupDTO;
import dev.productivity.signals.dto.ProjectSatisfactionSummaryDTO;
import dev.productivity.signals.dto.ProjectSatisfactionSurveyResponseDTO;
import dev.productivity.signals.dto.UserDTO;
import dev.productivity.signals.dto.UserCreateDTO;
import dev.productivity.signals.dto.WeeklyBreakdownDTO;
import dev.productivity.signals.entity.AiMrEvaluation;
import dev.productivity.signals.entity.MetricWeightManagement;
import lombok.RequiredArgsConstructor;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static dev.productivity.signals.util.SpaceMetricConstants.*;

@Service
@RequiredArgsConstructor
public class DemoMockDataService {

    public static final String DEMO_PROJECT_ID = "demo~productivity";
    public static final String AI_REVIEW_DEMO_PROJECT_ID = "demo~ai-review";
    private static final double DEMO_PERIOD_WEEKS = 5.0;
    private static final String DEMO_AI_GUARDRAIL = "AIは最終評価ではなく、改善提案の補助としてのみ利用します。";

    @Value("${demo.mock.enabled:false}")
    private boolean enabled;

    private final SpaceMetricScoringService scoringService;
    private final GeminiService geminiService;
    private final List<GroupDTO> demoGroups = new CopyOnWriteArrayList<>(defaultGroups());

    public boolean isEnabled() {
        return enabled;
    }

    public boolean shouldUseMockProject(String projectId) {
        return enabled && (DEMO_PROJECT_ID.equals(projectId) || AI_REVIEW_DEMO_PROJECT_ID.equals(projectId));
    }

    public List<ProjectDTO> getProjects() {
        ProjectDTO productivity = new ProjectDTO();
        productivity.setId(DEMO_PROJECT_ID);
        productivity.setName("開発生産性可視化デモ");
        productivity.setDescription("GitLab連携を前提にした、開発生産性とSPACE指標の公開デモ用プロジェクトです。");
        productivity.setDefaultBranch("main");
        productivity.setProvider("mock");

        ProjectDTO aiReview = new ProjectDTO();
        aiReview.setId(AI_REVIEW_DEMO_PROJECT_ID);
        aiReview.setName("AIレビュー支援サービス");
        aiReview.setDescription("MR品質分析とAIレビュー補助を確認するためのデモ用サービスです。");
        aiReview.setDefaultBranch("main");
        aiReview.setProvider("mock");

        return List.of(productivity, aiReview);
    }

    public CommitMetricsResponseDTO getCommitMetrics(
            String projectId,
            String author,
            String since,
            String until) {
        Map<String, Object> metrics = getSpaceMetrics(projectId, author);
        int totalCommits = Math.max(0, (int) getNumber(metrics, commitCount));
        List<WeeklyBreakdownDTO> breakdown = new ArrayList<>();
        try {
            LocalDate start = LocalDate.parse(since);
            LocalDate end = LocalDate.parse(until);
            if (end.isBefore(start)) {
                return new CommitMetricsResponseDTO(totalCommits, breakdown, new CommitStatsDTO(0, 0, 0));
            }
            LocalDate weekStart = start.minusDays(start.getDayOfWeek().getValue() % 7);
            List<LocalDate> weekStarts = new ArrayList<>();
            while (!weekStart.isAfter(end)) {
                weekStarts.add(weekStart);
                weekStart = weekStart.plusWeeks(1);
            }
            int baseCount = weekStarts.isEmpty() ? 0 : totalCommits / weekStarts.size();
            int remainder = weekStarts.isEmpty() ? 0 : totalCommits % weekStarts.size();
            for (int index = 0; index < weekStarts.size(); index++) {
                LocalDate bucketStart = weekStarts.get(index).isBefore(start) ? start : weekStarts.get(index);
                LocalDate bucketEnd = weekStarts.get(index).plusDays(6).isAfter(end)
                        ? end
                        : weekStarts.get(index).plusDays(6);
                breakdown.add(new WeeklyBreakdownDTO(
                        bucketStart.toString(),
                        bucketEnd.toString(),
                        baseCount + (index < remainder ? 1 : 0)));
            }
        } catch (Exception ignored) {
            // Return the total with an empty breakdown when the optional range is invalid.
        }
        return new CommitMetricsResponseDTO(totalCommits, breakdown, new CommitStatsDTO(0, 0, 0));
    }

    public List<ProjectMemberGroupDTO> getProjectMembers(String projectId) {
        if (AI_REVIEW_DEMO_PROJECT_ID.equals(projectId)) {
            return List.of(
                    new ProjectMemberGroupDTO("MAYA", "鈴木 真彩", "platform", "プラットフォーム"),
                    new ProjectMemberGroupDTO("KEN", "渡辺 健", "application", "アプリケーション"),
                    new ProjectMemberGroupDTO("SORA", "金 空", "qa", "品質保証"));
        }
        return List.of(
                new ProjectMemberGroupDTO("ALICE", "田中 亜里沙", "platform", "プラットフォーム"),
                new ProjectMemberGroupDTO("BOB", "佐藤 恒一", "application", "アプリケーション"),
                new ProjectMemberGroupDTO("CHEN", "陳 亮", "qa", "品質保証"));
    }

    public List<BranchDTO> getBranches(String projectId) {
        if (AI_REVIEW_DEMO_PROJECT_ID.equals(projectId)) {
            return List.of(
                    branch("main", true, true, true, "2026-05-12T01:20:00Z", "2026-06-26T07:45:00Z"),
                    branch("feature/guardrail-evaluator", false, false, false, "2026-06-11T02:10:00Z", "2026-06-25T05:35:00Z"),
                    branch("feature/rag-context-ranking", false, false, false, "2026-06-14T03:15:00Z", "2026-06-23T09:05:00Z"));
        }
        return List.of(
                branch("main", true, true, true, "2026-05-06T02:10:00Z", "2026-06-24T09:30:00Z"),
                branch("feature/ai-review-summary", false, false, false, "2026-06-03T04:20:00Z", "2026-06-20T10:10:00Z"),
                branch("fix/score-calculation", true, false, false, "2026-06-10T01:40:00Z", "2026-06-18T06:25:00Z"));
    }

    public List<ProjectMemberGroupDTO> getActiveProjectMembers(String projectId) {
        return getProjectMembers(projectId);
    }

    public List<Map<String, Object>> getMembersSpaceMetrics(String projectId) {
        return getProjectMembers(projectId).stream()
                .map(member -> getSpaceMetrics(projectId, member.getUserCode()))
                .toList();
    }

    public Map<String, Object> getSpaceMetrics(String projectId, String userName) {
        String user = normalizeUser(userName);
        Map<String, Number> rawMetrics = buildRawSpaceMetrics(projectId, userName);
        Map<String, Object> metrics = new LinkedHashMap<>(scoringService.calculateScores(
                rawMetrics,
                DEMO_PERIOD_WEEKS,
                getMetricWeightEntities()));

        if (userName != null && !userName.isBlank()) {
            metrics.put("userCode", user);
        }
        metrics.put("demoData", true);
        metrics.put("aiCorrected", aiCorrected(metrics));
        return metrics;
    }

    public AiEvaluationResponseDTO getAiEvaluation(String projectId, String userName) {
        Map<String, Object> metrics = getSpaceMetrics(projectId, userName);
        List<AiMrEvaluation> evaluations = getMockMrEvaluations(projectId, userName);
        metrics.put("aiEvaluations", evaluations);

        String responseJson = geminiService.evaluatePerformance(metrics, evaluations);
        if (responseJson == null || responseJson.isBlank()) {
            return null;
        }

        try {
            JSONObject response = new JSONObject(responseJson);
            return new AiEvaluationResponseDTO(
                    jsonStringList(response.getJSONArray("strengths")),
                    jsonStringList(response.getJSONArray("weaknesses")),
                    jsonStringList(response.getJSONArray("suggestions")));
        } catch (Exception e) {
            throw new IllegalStateException("Gemini returned an invalid performance evaluation", e);
        }
    }

    public ComparisonAnalysisResponseDTO getComparisonAnalysis(String projectId, String userName) {
        return new ComparisonAnalysisResponseDTO(getSpaceMetrics(projectId, userName), getAiEvaluation(projectId, userName));
    }

    private List<String> jsonStringList(org.json.JSONArray values) {
        List<String> result = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            result.add(values.getString(index));
        }
        return result;
    }

    public AiAnalysisProgressDTO getAiAnalysisProgress(String projectId) {
        return new AiAnalysisProgressDTO(6, 6, 6, null, "completed", "Demo AI analysis completed for 6 sanitized merge requests.");
    }

    public List<GroupDTO> getGroups() {
        return demoGroups.stream()
                .map(this::copyGroup)
                .toList();
    }

    public GroupDTO createGroup(GroupCreateDTO dto) {
        GroupDTO group = new GroupDTO(
                dto.getGroupId(),
                dto.getGroupName(),
                new ArrayList<>());
        demoGroups.removeIf(item -> item.getGroupId().equalsIgnoreCase(dto.getGroupId()));
        demoGroups.add(group);
        return copyGroup(group);
    }

    public UserDTO addUserToGroup(String groupId, UserCreateDTO dto) {
        GroupDTO group = findOrCreateGroup(groupId);
        group.getUsers().removeIf(user -> user.getUserCode().equalsIgnoreCase(dto.getUserCode()));
        UserDTO user = new UserDTO(dto.getUserCode(), dto.getUserName());
        group.getUsers().add(user);
        return new UserDTO(user.getUserCode(), user.getUserName());
    }

    public void deleteGroup(String groupId) {
        demoGroups.removeIf(group -> group.getGroupId().equalsIgnoreCase(groupId));
    }

    public void deleteUserFromGroup(String groupId, String userCode) {
        demoGroups.stream()
                .filter(group -> group.getGroupId().equalsIgnoreCase(groupId))
                .findFirst()
                .ifPresent(group -> group.getUsers().removeIf(user -> user.getUserCode().equalsIgnoreCase(userCode)));
    }

    public List<ProjectSatisfactionSurveyResponseDTO> getSurveys(String projectId, String userName) {
        List<ProjectSatisfactionSurveyResponseDTO> surveys = new ArrayList<>();
        surveys.add(survey(1L, projectId, "ALICE", "田中 亜里沙", 82.0, "レビュー導線は改善しているが、大きいMRにはもう少し早い初回フィードバックが欲しい。"));
        surveys.add(survey(2L, projectId, "BOB", "佐藤 恒一", 72.0, "ダッシュボードで詰まりは見えるが、アラートはもう少し行動につながる形にしたい。"));
        surveys.add(survey(3L, projectId, "CHEN", "陳 亮", 78.0, "リリース前にMR分析が見えると、QAへの引き継ぎがかなりやりやすい。"));
        String user = normalizeUser(userName);
        if (userName == null || userName.isBlank()) {
            return surveys;
        }
        return surveys.stream().filter(s -> user.equalsIgnoreCase(s.getUserCode())).toList();
    }

    public ProjectSatisfactionSummaryDTO getSatisfactionSummary(String projectId, String userName) {
        String user = normalizeUser(userName);
        String name = switch (user) {
            case "ALICE" -> "田中 亜里沙";
            case "BOB" -> "佐藤 恒一";
            case "CHEN" -> "陳 亮";
            case "MAYA" -> "鈴木 真彩";
            case "KEN" -> "渡辺 健";
            case "SORA" -> "金 空";
            default -> null;
        };
        Map<String, Number> rawMetrics = buildRawSpaceMetrics(projectId, userName);
        double satisfactionScoreValue = rawMetrics.getOrDefault(satisfactionSurveyScore, 0).doubleValue();
        double jobMeaning = rawMetrics.getOrDefault(satisfactionJobMeaning, 0).doubleValue();
        double efficacy = rawMetrics.getOrDefault(satisfactionDeveloperEfficacy, 0).doubleValue();
        double sustainability = rawMetrics.getOrDefault(satisfactionSustainability, 0).doubleValue();
        double improvement = rawMetrics.getOrDefault(satisfactionImprovementPotential, 0).doubleValue();
        int responseCount = rawMetrics.getOrDefault(satisfactionResponseCount, 0).intValue();
        return ProjectSatisfactionSummaryDTO.builder()
                .projectId(projectId)
                .userCode(userName == null || userName.isBlank() ? null : user)
                .userName(name)
                .responseCount(responseCount)
                .satisfactionScore(satisfactionScoreValue)
                .s1JobSatisfaction(jobMeaning)
                .s2DeveloperEfficacy(efficacy)
                .s3Sustainability(sustainability)
                .s4ImprovementPotential(improvement)
                .q1WorkValue(4.0)
                .q2WorkMeaning(4.0)
                .q3TeamSatisfaction(4.0)
                .q4RecommendTeam(4.0)
                .q5InformationAccess(3.7)
                .q6EnvironmentSupport(3.8)
                .q7SupportFlow(3.6)
                .q8OutcomeConfidence(4.1)
                .q9SustainableWorkload(3.4)
                .q10Fatigue(2.4)
                .q11Detachment(2.1)
                .q12Pressure(2.8)
                .q13PsychologicalSafety(4.0)
                .q14ImprovementExpectation(4.1)
                .build();
    }

    public List<MetricWeightDTO> getMetricWeights() {
        return List.of(
                metricWeight(1, "performance", null, 0.24, 0, 100, true, "成果物の品質と安定性に関する指標群"),
                metricWeight(2, "activity", null, 0.22, 0, 100, true, "開発活動量に関する指標群"),
                metricWeight(3, "communication", null, 0.18, 0, 100, true, "レビューと協業に関する指標群"),
                metricWeight(4, "efficiency", null, 0.20, 0, 100, true, "リードタイム、集中時間、作業分断に関する指標群"),
                metricWeight(5, "satisfaction", null, 0.16, 0, 100, true, "開発者体験と満足度に関する指標群"),
                metricWeight(101, mergedCount, "performance", 0.40, 0, 4, true, "週あたりのマージ完了数"),
                metricWeight(102, bugCausedCount, "performance", 0.25, 0, 2, false, "週あたりの起因バグ件数"),
                metricWeight(103, bugFixLeadTimeHours, "performance", 0.35, 8, 48, false, "バグ修正完了までの平均時間"),
                // Lines changed are capped at 15% of Activity: a weak signal that is easy to inflate.
                metricWeight(201, commitCount, "activity", 0.40, 2, 12, true, "週あたりのコミット数"),
                metricWeight(202, issueCreatedCount, "activity", 0.20, 0, 6, true, "週あたりの起票件数"),
                // Rewarding bugs found would conflict with Performance, which treats bugs as negative.
                inactive(metricWeight(203, bugFoundCount, "activity", 0.0, 0, 5, true, "週あたりのバグ検知件数（採点対象外）")),
                metricWeight(204, bugFixedCount, "activity", 0.25, 0, 4, true, "週あたりのバグ修正件数"),
                metricWeight(205, linesAdded, "activity", 0.10, 80, 420, true, "週あたりの追加行数"),
                metricWeight(206, linesDeleted, "activity", 0.05, 20, 220, true, "週あたりの削除行数"),
                // Added plus deleted lines; scoring it as well would count the same change twice.
                inactive(metricWeight(207, linesTotal, "activity", 0.0, 120, 600, true, "週あたりの変更総行数（採点対象外）")),
                metricWeight(301, reviewedCount, "communication", 0.40, 0, 5, true, "週あたりのレビュー実施件数"),
                metricWeight(302, commentCount, "communication", 0.35, 0, 8, true, "週あたりのコメント件数"),
                metricWeight(303, reviewCommentCount, "communication", 0.25, 0, 4, true, "レビューあたりの平均コメント件数"),
                metricWeight(401, mergedLeadTimeHours, "efficiency", 0.30, 8, 48, false, "マージまでの平均リードタイム"),
                metricWeight(402, reviewWaitTime, "efficiency", 0.25, 2, 36, false, "初回レビューまでの平均待ち時間"),
                metricWeight(403, uninterruptedFocusTimeHours, "efficiency", 0.25, 1, 4, true, "1営業日あたりの平均連続集中時間"),
                metricWeight(404, contextSwitchFrequency, "efficiency", 0.20, 2, 8, false, "1営業日あたりの平均コンテキストスイッチ回数"),
                metricWeight(501, satisfactionJobMeaning, "satisfaction", 0.28, 50, 90, true, "仕事の意義実感"),
                metricWeight(502, satisfactionDeveloperEfficacy, "satisfaction", 0.26, 50, 90, true, "開発効力感"),
                metricWeight(503, satisfactionSustainability, "satisfaction", 0.24, 45, 85, true, "持続可能な働き方"),
                metricWeight(504, satisfactionImprovementPotential, "satisfaction", 0.22, 50, 90, true, "改善期待と前向きさ"),
                // Thresholds only: used as the Satisfaction score when there are no survey responses.
                metricWeight(505, contributorRetentionRate, "satisfaction", 0.0, 20, 70, true,
                        "コントリビューター定着率（%）。満足度調査の回答がない場合の満足度に使用（重みは使わない）"));
    }

    public List<MetricWeightManagement> getMetricWeightEntities() {
        return getMetricWeights().stream()
                .map(this::toMetricWeightEntity)
                .toList();
    }

    public IssueStatsDTO getIssueStats(String projectId, String userName) {
        return switch (normalizeUser(userName)) {
            case "ALICE" -> new IssueStatsDTO(18, 15, 5, 1, 4, 19.5);
            case "BOB" -> new IssueStatsDTO(15, 13, 4, 2, 3, 26.0);
            case "CHEN" -> new IssueStatsDTO(21, 18, 7, 1, 6, 14.0);
            case "MAYA" -> new IssueStatsDTO(12, 11, 3, 1, 3, 17.0);
            case "KEN" -> new IssueStatsDTO(16, 13, 5, 2, 4, 23.5);
            case "SORA" -> new IssueStatsDTO(19, 17, 8, 1, 7, 13.5);
            default -> new IssueStatsDTO(54, 46, 16, 4, 13, 19.8);
        };
    }

    public CommitMetricsResponseDTO getCommitMetrics(String projectId, String author) {
        int total = switch (normalizeUser(author)) {
            case "ALICE" -> 42;
            case "BOB" -> 36;
            case "CHEN" -> 28;
            case "MAYA" -> 31;
            case "KEN" -> 39;
            case "SORA" -> 24;
            default -> 106;
        };
        int multiplier = Math.max(1, total / 20);
        CommitStatsDTO commitStats = new CommitStatsDTO(total * 34, total * 12, total * 46);
        return new CommitMetricsResponseDTO(
                total,
                List.of(
                        new WeeklyBreakdownDTO("2026-05-25", "2026-05-31", Math.max(4, total / 5)),
                        new WeeklyBreakdownDTO("2026-06-01", "2026-06-07", Math.max(6, total / 4)),
                        new WeeklyBreakdownDTO("2026-06-08", "2026-06-14", Math.max(8, total / 3)),
                        new WeeklyBreakdownDTO("2026-06-15", "2026-06-21", Math.max(7, total / 4 + multiplier)),
                        new WeeklyBreakdownDTO("2026-06-22", "2026-06-28", Math.max(5, total / 5 + multiplier))),
                commitStats);
    }

    public MergeRequestStatsDTO getMergeRequestStats(String projectId, String userName) {
        return switch (normalizeUser(userName)) {
            case "ALICE" -> new MergeRequestStatsDTO(12, 10, 9, 31, 18, 22.4, 20.8, 5.5, 7, 16);
            case "BOB" -> new MergeRequestStatsDTO(10, 8, 8, 22, 14, 28.0, 25.0, 7.2, 5, 12);
            case "CHEN" -> new MergeRequestStatsDTO(8, 7, 12, 28, 20, 18.0, 17.5, 4.1, 11, 23);
            case "MAYA" -> new MergeRequestStatsDTO(9, 8, 8, 21, 13, 19.4, 18.0, 4.8, 6, 14);
            case "KEN" -> new MergeRequestStatsDTO(11, 9, 10, 26, 16, 24.5, 22.0, 6.9, 7, 18);
            case "SORA" -> new MergeRequestStatsDTO(7, 6, 11, 30, 19, 16.0, 15.2, 3.8, 12, 25);
            default -> new MergeRequestStatsDTO(30, 25, 29, 81, 52, 22.8, 21.1, 5.6, 23, 51);
        };
    }

    public MergeRequestActivityPeriodDTO getMergeRequestActivityPeriod(String projectId) {
        return new MergeRequestActivityPeriodDTO(
                LocalDate.of(2026, 5, 25),
                LocalDate.of(2026, 6, 28),
                LocalDate.of(2026, 5, 25),
                LocalDate.of(2026, 6, 28));
    }

    public List<AiMrEvaluation> getMockMrEvaluations(String projectId, String userName) {
        String normalizedUser = userName == null || userName.isBlank() ? null : normalizeUser(userName);
        return mockMergeRequests(projectId).stream()
                .filter(mr -> normalizedUser == null || normalizedUser.equalsIgnoreCase(mr.author()))
                .map(mr -> mockMrEvaluation(projectId, mr))
                .toList();
    }

    private AiMrEvaluation mockMrEvaluation(String projectId, MockMergeRequest mr) {
        int scenario = mr.iid() % 100;
        MockMrAnalysis analysis = switch (scenario) {
            case 1 -> new MockMrAnalysis("New Feature", 1.1, "mid", 1.0, "high", 1.2, false, "high", 1.2,
                    "監査ログ検索APIを追加した新機能。構造は明確で、運用監査への貢献が大きい。");
            case 2 -> new MockMrAnalysis("Bug Fix", 1.0, "mid", 1.0, "high", 1.2, false, "mid", 1.0,
                    "欠損値を0点として扱う既存不具合を修正し、未観測データを区別している。");
            case 3 -> new MockMrAnalysis("Refactor", 1.5, "mid", 1.0, "high", 1.2, false, "mid", 1.0,
                    "集計責務を分割するリファクタで、変更容易性とテスト性を改善している。");
            case 4 -> new MockMrAnalysis("Optimization", 1.3, "high", 1.2, "high", 1.2, false, "high", 1.2,
                    "N+1クエリを一括取得へ変更し、メンバー数増加時の性能劣化を抑制している。");
            case 5 -> new MockMrAnalysis("Auto-generated", 0.1, "low", 0.7, "high", 1.2, false, "low", 0.7,
                    "OpenAPI定義から生成された機械的変更で、直接的な実装貢献は限定的。");
            case 6 -> new MockMrAnalysis("New Feature", 1.1, "high", 1.2, "low", 0.7, true, "high", 1.2,
                    "admin=trueの場合にテナントと検索語の条件を迂回でき、越権参照につながる可能性がある。");
            default -> throw new IllegalArgumentException("Unknown demo MR: " + mr.iid());
        };

        AiMrEvaluation evaluation = new AiMrEvaluation();
        evaluation.setProjectId(projectId);
        evaluation.setMrIid(mr.iid());
        evaluation.setAuthorUsername(mr.author());
        evaluation.setSourceBranch(mr.sourceBranch());
        evaluation.setTargetBranch(mr.targetBranch());
        evaluation.setMergedAt(mr.mergedAt());
        evaluation.setChangeType(analysis.changeType());
        evaluation.setTypeCoefficient(analysis.typeCoefficient());
        evaluation.setComplexity(analysis.complexity());
        evaluation.setComplexityCoefficient(analysis.complexityCoefficient());
        evaluation.setMaintainability(analysis.maintainability());
        evaluation.setMaintainabilityCoefficient(analysis.maintainabilityCoefficient());
        evaluation.setHasBug(analysis.hasBug());
        evaluation.setBugCoefficient(analysis.hasBug() ? 0.0 : 1.0);
        evaluation.setContribution(analysis.contribution());
        evaluation.setContributionCoefficient(analysis.contributionCoefficient());
        evaluation.setReasoning(analysis.reasoning());
        evaluation.setRawResponse(mockMrAnalysisJson(analysis).toString());
        evaluation.setAnalyzedAt(LocalDateTime.now());
        return evaluation;
    }

    private JSONObject mockMrAnalysisJson(MockMrAnalysis analysis) {
        return new JSONObject()
                .put("type", mockMetric(analysis.changeType(), analysis.reasoning()))
                .put("complexity", mockMetric(analysis.complexity(), analysis.reasoning()))
                .put("maintainability", mockMetric(analysis.maintainability(), analysis.reasoning()))
                .put("bug", mockMetric(analysis.hasBug(), analysis.reasoning()))
                .put("contribution", mockMetric(analysis.contribution(), analysis.reasoning()));
    }

    private JSONObject mockMetric(Object value, String reasoning) {
        return new JSONObject().put("value", value).put("reasoning", reasoning);
    }

    private List<MockMergeRequest> mockMergeRequests(String projectId) {
        boolean aiReviewProject = AI_REVIEW_DEMO_PROJECT_ID.equals(projectId);
        int baseIid = aiReviewProject ? 200 : 100;
        List<String> authors = aiReviewProject
                ? List.of("MAYA", "KEN", "SORA", "MAYA", "KEN", "SORA")
                : List.of("ALICE", "BOB", "CHEN", "ALICE", "BOB", "CHEN");

        return List.of(
                new MockMergeRequest(baseIid + 1, authors.get(0), "feature/audit-log-search", "main",
                        LocalDateTime.of(2026, 6, 3, 10, 15)),
                new MockMergeRequest(baseIid + 2, authors.get(1), "fix/missing-score", "main",
                        LocalDateTime.of(2026, 6, 7, 14, 30)),
                new MockMergeRequest(baseIid + 3, authors.get(2), "refactor/space-aggregation", "main",
                        LocalDateTime.of(2026, 6, 11, 9, 20)),
                new MockMergeRequest(baseIid + 4, authors.get(3), "perf/member-statistics", "main",
                        LocalDateTime.of(2026, 6, 16, 16, 45)),
                new MockMergeRequest(baseIid + 5, authors.get(4), "chore/regenerate-api-client", "main",
                        LocalDateTime.of(2026, 6, 21, 11, 5)),
                new MockMergeRequest(baseIid + 6, authors.get(5), "feature/admin-user-search", "main",
                        LocalDateTime.of(2026, 6, 26, 13, 40)));
    }

    private BranchDTO branch(String name, boolean merged, boolean isProtected, boolean isDefault,
            String firstCommitAt, String lastCommitAt) {
        BranchDTO dto = new BranchDTO();
        dto.setName(name);
        dto.setMerged(merged);
        dto.setIsProtected(isProtected);
        dto.setIsDefault(isDefault);
        dto.setWebUrl("");
        dto.setFirstCommitAt(firstCommitAt);
        dto.setLastCommitAt(lastCommitAt);
        return dto;
    }

    private Map<String, Object> aiCorrected(Map<String, Object> raw) {
        Map<String, Object> corrected = new LinkedHashMap<>(raw);
        double efficiency = getNumber(raw, efficiencyScore);
        corrected = new LinkedHashMap<>(scoringService.applyDimensionScoreOverride(
                corrected,
                "efficiency",
                Math.min(100.0, efficiency + 3.0)));
        corrected.put("demoAiGuardrail", DEMO_AI_GUARDRAIL);
        return corrected;
    }

    private ProjectSatisfactionSurveyResponseDTO survey(Long id, String projectId, String userCode,
            String userName, Double score, String comment) {
        return new ProjectSatisfactionSurveyResponseDTO(
                id,
                projectId,
                userCode,
                userName,
                LocalDate.of(2026, 6, 24),
                LocalDate.of(2026, 5, 25),
                LocalDate.of(2026, 6, 28),
                userName,
                4, 4, 4, 4, 4, 4, 3, 4, 3, 2, 2, 3, 4, 4,
                comment,
                score,
                score + 2,
                score + 4,
                score - 6,
                score,
                LocalDateTime.of(2026, 6, 24, 10, 30));
    }

    private MetricWeightDTO metricWeight(Integer id, String metricKey, String parentKey, double weight,
            double minThreshold, double maxThreshold, boolean positiveMetric, String description) {
        MetricWeightDTO dto = new MetricWeightDTO();
        dto.setId(id);
        dto.setMetricKey(metricKey);
        dto.setParentKey(parentKey);
        dto.setWeight(weight);
        dto.setActive(true);
        dto.setMinThreshold(minThreshold);
        dto.setMaxThreshold(maxThreshold);
        dto.setPositiveMetric(positiveMetric);
        dto.setDescription(description);
        return dto;
    }

    private MetricWeightDTO inactive(MetricWeightDTO dto) {
        dto.setActive(false);
        return dto;
    }

    private MetricWeightManagement toMetricWeightEntity(MetricWeightDTO dto) {
        MetricWeightManagement entity = new MetricWeightManagement();
        entity.setId(dto.getId());
        entity.setMetricKey(dto.getMetricKey());
        entity.setParentKey(dto.getParentKey());
        entity.setWeight(dto.getWeight());
        entity.setActive(dto.isActive());
        entity.setMinThreshold(dto.getMinThreshold());
        entity.setMaxThreshold(dto.getMaxThreshold());
        entity.setPositiveMetric(dto.isPositiveMetric());
        entity.setDescription(dto.getDescription());
        return entity;
    }

    private Map<String, Number> buildRawSpaceMetrics(String projectId, String userName) {
        String user = normalizeUser(userName);
        if ("ALL".equals(user)) {
            return aggregateProjectMetrics(projectId);
        }

        IssueStatsDTO issues = getIssueStats(projectId, user);
        CommitMetricsResponseDTO commits = getCommitMetrics(projectId, user);
        MergeRequestStatsDTO merges = getMergeRequestStats(projectId, user);

        Map<String, Number> metrics = new LinkedHashMap<>();
        metrics.put(mergedCount, merges.getMergedCount());
        metrics.put(mergedLeadTimeHours, merges.getAvgHoursToMergeByMerged());
        metrics.put(bugCausedCount, issues.getBugCausedCount());
        metrics.put(bugFixLeadTimeHours, issues.getAvgBugFixedHours());
        metrics.put(commitCount, commits.getTotalCommitCount());
        metrics.put(issueCreatedCount, issues.getCreatedCount());
        metrics.put(bugFoundCount, issues.getBugFoundCount());
        metrics.put(bugFixedCount, issues.getBugFixedCount());
        metrics.put(linesAdded, commits.getCommitStats().getLinesAdded());
        metrics.put(linesDeleted, commits.getCommitStats().getLinesDeleted());
        metrics.put(linesTotal, commits.getCommitStats().getLinesTotal());
        metrics.put(reviewedCount, merges.getGivenReviewCount());
        metrics.put(commentCount, merges.getGivenCommentCount());
        metrics.put(reviewWaitTime, merges.getAvgHoursToFirstReview());
        metrics.put(uninterruptedFocusTimeHours, mockUninterruptedFocusTime(user));
        metrics.put(contextSwitchFrequency, mockContextSwitchFrequency(user));
        metrics.put(reviewCommentCount, averageReviewCommentCount(user));
        metrics.put(satisfactionResponseCount, 1);
        metrics.put(satisfactionJobMeaning, satisfactionBaseline(user) + 2.0);
        metrics.put(satisfactionDeveloperEfficacy, satisfactionBaseline(user) + 5.0);
        metrics.put(satisfactionSustainability, satisfactionBaseline(user) - 6.0);
        metrics.put(satisfactionImprovementPotential, satisfactionBaseline(user) - 1.0);
        metrics.put(satisfactionSurveyScore, averageOf(
                metrics.get(satisfactionJobMeaning).doubleValue(),
                metrics.get(satisfactionDeveloperEfficacy).doubleValue(),
                metrics.get(satisfactionSustainability).doubleValue(),
                metrics.get(satisfactionImprovementPotential).doubleValue()));
        return metrics;
    }

    private Map<String, Number> aggregateProjectMetrics(String projectId) {
        List<String> userCodes = getProjectMembers(projectId).stream()
                .map(ProjectMemberGroupDTO::getUserCode)
                .toList();
        List<Map<String, Number>> memberMetrics = userCodes.stream()
                .map(userCode -> buildRawSpaceMetrics(projectId, userCode))
                .toList();

        Map<String, Number> aggregated = new LinkedHashMap<>();
        String[] sumKeys = {
                mergedCount, bugCausedCount, commitCount, issueCreatedCount, bugFoundCount, bugFixedCount,
                linesAdded, linesDeleted, linesTotal, reviewedCount, commentCount, satisfactionResponseCount
        };
        String[] avgKeys = {
                mergedLeadTimeHours, bugFixLeadTimeHours, reviewWaitTime, uninterruptedFocusTimeHours,
                contextSwitchFrequency, reviewCommentCount,
                satisfactionSurveyScore, satisfactionJobMeaning, satisfactionDeveloperEfficacy,
                satisfactionSustainability, satisfactionImprovementPotential
        };

        for (String key : sumKeys) {
            aggregated.put(key, memberMetrics.stream()
                    .mapToDouble(item -> item.getOrDefault(key, 0).doubleValue())
                    .sum());
        }
        for (String key : avgKeys) {
            aggregated.put(key, averageOf(memberMetrics.stream()
                    .mapToDouble(item -> item.getOrDefault(key, 0).doubleValue())
                    .toArray()));
        }
        return aggregated;
    }

    private double averageReviewCommentCount(String user) {
        return switch (user) {
            case "ALICE" -> 3.2;
            case "BOB" -> 2.4;
            case "CHEN" -> 3.8;
            case "MAYA" -> 2.9;
            case "KEN" -> 2.6;
            case "SORA" -> 4.1;
            default -> 3.0;
        };
    }

    private double mockUninterruptedFocusTime(String user) {
        return switch (user) {
            case "ALICE" -> 3.6;
            case "BOB" -> 2.4;
            case "CHEN" -> 3.2;
            case "MAYA" -> 3.8;
            case "KEN" -> 2.7;
            case "SORA" -> 3.4;
            default -> 3.2;
        };
    }

    private double mockContextSwitchFrequency(String user) {
        return switch (user) {
            case "ALICE" -> 3.0;
            case "BOB" -> 6.2;
            case "CHEN" -> 4.1;
            case "MAYA" -> 2.7;
            case "KEN" -> 5.4;
            case "SORA" -> 3.6;
            default -> 4.2;
        };
    }

    private double satisfactionBaseline(String user) {
        return switch (user) {
            case "ALICE" -> 80.0;
            case "BOB" -> 71.0;
            case "CHEN" -> 77.0;
            case "MAYA" -> 79.0;
            case "KEN" -> 74.0;
            case "SORA" -> 78.0;
            default -> 76.0;
        };
    }

    private double averageOf(double... values) {
        if (values.length == 0) {
            return 0.0;
        }
        double total = 0.0;
        for (double value : values) {
            total += value;
        }
        return total / values.length;
    }

    private double getNumber(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return 0.0;
    }

    private String normalizeUser(String userName) {
        if (userName == null || userName.isBlank()) {
            return "ALL";
        }
        return userName.trim().toUpperCase();
    }

    private List<GroupDTO> defaultGroups() {
        return List.of(
                new GroupDTO("platform", "プラットフォーム", new ArrayList<>(List.of(
                        new UserDTO("ALICE", "田中 亜里沙"),
                        new UserDTO("MAYA", "鈴木 真彩")))),
                new GroupDTO("application", "アプリケーション", new ArrayList<>(List.of(
                        new UserDTO("BOB", "佐藤 恒一"),
                        new UserDTO("KEN", "渡辺 健")))),
                new GroupDTO("qa", "品質保証", new ArrayList<>(List.of(
                        new UserDTO("CHEN", "陳 亮"),
                        new UserDTO("SORA", "金 空")))));
    }

    private GroupDTO findOrCreateGroup(String groupId) {
        return demoGroups.stream()
                .filter(group -> group.getGroupId().equalsIgnoreCase(groupId))
                .findFirst()
                .orElseGet(() -> {
                    GroupDTO group = new GroupDTO(groupId, groupId, new ArrayList<>());
                    demoGroups.add(group);
                    return group;
                });
    }

    private GroupDTO copyGroup(GroupDTO group) {
        return new GroupDTO(
                group.getGroupId(),
                group.getGroupName(),
                group.getUsers().stream()
                        .map(user -> new UserDTO(user.getUserCode(), user.getUserName()))
                        .toList());
    }

    private record MockMergeRequest(
            int iid,
            String author,
            String sourceBranch,
            String targetBranch,
            LocalDateTime mergedAt) {
    }

    private record MockMrAnalysis(
            String changeType,
            double typeCoefficient,
            String complexity,
            double complexityCoefficient,
            String maintainability,
            double maintainabilityCoefficient,
            boolean hasBug,
            String contribution,
            double contributionCoefficient,
            String reasoning) {
    }
}
