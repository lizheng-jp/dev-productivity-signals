package dev.productivity.signals.service;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import dev.productivity.signals.entity.AiMrEvaluation;
import dev.productivity.signals.entity.MetricWeightManagement;
import dev.productivity.signals.repository.MetricWeightManagementRepository;
import dev.productivity.signals.util.JsonObjectConverter;
import lombok.extern.slf4j.Slf4j;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.Objects;

import static dev.productivity.signals.util.SpaceMetricConstants.*;
import dev.productivity.signals.util.PerformanceTimingLog;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;

/**
 * Google Gemini API との通信を担当するサービス。
 * コード差分（Diff）の分析や、SPACE指標に基づく生産性評価を行います。
 */
@Service
@Slf4j
public class GeminiService {

    private final MetricWeightManagementRepository metricWeightRepository;
    private final MonitoringMetricsService monitoringMetricsService;

    private static final Map<String, MetricPromptDefinition> METRIC_PROMPT_DEFINITIONS = Map.ofEntries(
            Map.entry(mergedCount,
                    new MetricPromptDefinition("マージ済みMR数",
                            "期間内にマージされたマージリクエスト数。個人評価では本人が作成してマージされたMR数、プロジェクト評価では期間内にマージされたMR数を示します。", "パフォーマンス")),
            Map.entry(mergedLeadTimeHours,
                    new MetricPromptDefinition("MRリードタイム（時間）",
                            "期間内にマージされたMRについて、作成からマージまでにかかった平均時間。短いほど変更を素早く届けられていることを示します。", "効率性")),
            Map.entry(bugCausedCount,
                    new MetricPromptDefinition("発生バグ数", "期間内に発生した、実装や変更に起因すると見なされるバグ数。少ないほど品質面のリスクが低いことを示します。",
                            "パフォーマンス")),
            Map.entry(bugFixLeadTimeHours,
                    new MetricPromptDefinition("バグ修正リードタイム（時間）", "バグが発見されてから修正されるまでの平均時間。短いほど問題対応が速いことを示します。",
                            "パフォーマンス")),
            Map.entry(commitCount, new MetricPromptDefinition("コミット数", "期間内のコミット数。開発活動量の目安です。", "アクティビティ")),
            Map.entry(issueCreatedCount,
                    new MetricPromptDefinition("Issue作成数", "期間内に作成されたIssue数。課題の可視化やタスク化の活動量を示します。", "アクティビティ")),
            Map.entry(bugFoundCount,
                    new MetricPromptDefinition("発見バグ数", "期間内に発見されたバグ数。品質確認や不具合検知の活動量を示します。", "アクティビティ")),
            Map.entry(bugFixedCount, new MetricPromptDefinition("修正バグ数", "期間内に修正されたバグ数。品質改善への対応量を示します。", "アクティビティ")),
            Map.entry(linesAdded, new MetricPromptDefinition("追加行数", "期間内のコミットで追加されたコード行数。変更量の参考値です。", "アクティビティ")),
            Map.entry(linesDeleted,
                    new MetricPromptDefinition("削除行数", "期間内のコミットで削除されたコード行数。整理や置き換えを含む変更量の参考値です。", "アクティビティ")),
            Map.entry(linesTotal, new MetricPromptDefinition("総変更行数", "追加行数と削除行数を合わせた変更量の参考値です。", "アクティビティ")),
            Map.entry(reviewedCount,
                    new MetricPromptDefinition("レビュー参加数",
                            "期間内に他者のMRへ有効なレビューコメントを行ったMR数。プロジェクト評価では非作者レビューコメントを行ったレビュアー参加数を示します。", "コミュニケーション")),
            Map.entry(commentCount,
                    new MetricPromptDefinition("他者MRコメント数", "期間内に他者のMRへ投稿した有効なレビューコメント数。作者自身のMR上の返信や説明コメントは含めません。",
                            "コミュニケーション")),
            Map.entry(reviewWaitTime,
                    new MetricPromptDefinition("レビュー待ち時間（時間）", "マージリクエスト作成から最初のレビューコメントまでの平均時間。短いほどレビュー開始が早いことを示します。",
                            "効率性")),
            Map.entry(uninterruptedFocusTimeHours,
                    new MetricPromptDefinition("連続集中時間（時間／営業日）",
                            "1営業日あたりに確保できた平均的な連続集中時間。長いほど中断されずに深い作業へ取り組めていることを示します。", "効率性")),
            Map.entry(contextSwitchFrequency,
                    new MetricPromptDefinition("コンテキストスイッチ頻度（回／営業日）",
                            "1営業日あたりにタスク、プロジェクト、会議などの間を切り替えた平均回数。少ないほど作業の分断が抑えられていることを示します。", "効率性")),
            Map.entry(reviewCommentCount, new MetricPromptDefinition("MR議論密度",
                    "本人またはプロジェクトが作成したMR 1件あたりに受け取った非作者レビューコメント数。多いほど良いとは限らないため、スコアには直接使わず、複雑度・リードタイム・バグ数と合わせて解釈します。",
                    "コミュニケーション")),
            Map.entry(satisfactionSurveyScore,
                    new MetricPromptDefinition("満足度調査スコア", "プロジェクト満足度アンケートから算出したスコア。働きやすさ、支援、持続可能性などの主観的な健全性を示します。",
                            "満足度")),
            Map.entry(satisfactionJobMeaning,
                    new MetricPromptDefinition("仕事満足・意義", "仕事の価値、やりがい、チーム満足、推奨意向から算出した満足度分項目です。", "満足度")),
            Map.entry(satisfactionDeveloperEfficacy,
                    new MetricPromptDefinition("開発者効力感", "情報アクセス、開発環境、相談・解決経路、成果実感から算出した満足度分項目です。", "満足度")),
            Map.entry(satisfactionSustainability,
                    new MetricPromptDefinition("持続可能性", "業務量、疲労感、心理的距離感、過度なプレッシャーから算出した満足度分項目です。疲労・距離感・プレッシャーは逆転スコアです。",
                            "満足度")),
            Map.entry(satisfactionImprovementPotential,
                    new MetricPromptDefinition("改善可能性", "心理的安全性と改善への期待から算出した満足度分項目です。", "満足度")),
            Map.entry(satisfactionResponseCount,
                    new MetricPromptDefinition("満足度調査回答数", "プロジェクト満足度アンケートの回答数。満足度スコアの根拠となる回答量を示します。", "満足度")));

    @Value("${gemini.api.key:}")
    private String apiKey;

    @Value("${gemini.api.url:}")
    private String apiUrl;

    @Value("${gemini.api.model:}")
    private String apiModel;

    public GeminiService(
            MetricWeightManagementRepository metricWeightRepository,
            MonitoringMetricsService monitoringMetricsService) {
        this.metricWeightRepository = metricWeightRepository;
        this.monitoringMetricsService = monitoringMetricsService;
    }

    /**
     * Merge Request のコード差分（Diff）を分析し、JSON形式の評価結果を取得します。
     *
     * @param diff 対象のソースコード差分文字列
     * @return 評価結果のJSON文字列（エラー時は null）
     */
    public String analyzeDiff(String diff) {
        return analyzeDiff(diff, diffAnalysisPromptTemplate());
    }

    public String analyzeDiff(String diff, String promptTemplate) {
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("Gemini API key is not configured.");
            return null;
        }

        String prompt = renderDiffAnalysisPrompt(promptTemplate, diff);

        try {
            String text = generateJsonContent(prompt, diffAnalysisSchema(), "gemini.diffApi");
            PerformanceTimingLog.incrementCount("gemini.diffApi.calls");
            return text;
        } catch (Exception e) {
            log.error("Error calling Gemini API: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * SPACE指標およびMR品質評価に基づいて、エンジニアのパフォーマンス評価と改善施策を生成します。
     *
     * @param metrics       SPACEの各種スコアおよびサブ指標を含むマップ
     * @param aiEvaluations 事前に生成されたMR品質評価のリスト
     * @return 強み・課題・具体策を含むJSON文字列（エラー時は null）
     */
    public String evaluatePerformance(Map<String, Object> metrics, List<?> aiEvaluations) {
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("Gemini API key is not configured.");
            return null;
        }

        String prompt = buildPerformanceEvaluationPrompt(metrics, aiEvaluations);

        try {
            String text = generateJsonContent(prompt, performanceEvaluationSchema(), "gemini.performanceApi");
            PerformanceTimingLog.incrementCount("gemini.performanceApi.calls");
            return text;
        } catch (Exception e) {
            log.error("Error calling Gemini API for performance evaluation: {}", e.getMessage(), e);
            return null;
        }
    }

    private String generateJsonContent(String prompt, JSONObject responseSchema, String timingName) {
        GenerateContentConfig config = GenerateContentConfig.builder()
                .responseMimeType("application/json")
                .candidateCount(1)
                .responseJsonSchema(JsonObjectConverter.toMap(responseSchema))
                .build();

        String model = geminiModel();
        try (Client client = createClient()) {
            GenerateContentResponse response = PerformanceTimingLog.time(
                    timingName,
                    () -> client.models.generateContent(model, prompt, config));
            response.usageMetadata()
                    .ifPresent(usage -> recordGeminiUsage(model, timingName, usage));
            return response.text();
        }
    }

    private Client createClient() {
        return Client.builder().apiKey(apiKey).build();
    }

    private void recordGeminiUsage(String model, String operation, GenerateContentResponseUsageMetadata usage) {
        monitoringMetricsService.recordGeminiUsage(
                model,
                operation,
                usage.promptTokenCount().orElse(0),
                usage.candidatesTokenCount().orElse(0),
                usage.thoughtsTokenCount().orElse(0),
                usage.totalTokenCount().orElse(0));
    }

    public String currentModel() {
        return geminiModel();
    }

    private String geminiModel() {
        if (apiModel != null && !apiModel.isBlank()) {
            return apiModel;
        }

        if (apiUrl != null && !apiUrl.isBlank()) {
            int modelsIndex = apiUrl.indexOf("/models/");
            int generateIndex = apiUrl.indexOf(":generateContent", modelsIndex);
            if (modelsIndex >= 0 && generateIndex > modelsIndex) {
                return apiUrl.substring(modelsIndex + "/models/".length(), generateIndex);
            }
        }

        return "gemini-3.5-flash";
    }

    private String buildDiffAnalysisPrompt(String diff) {
        return renderDiffAnalysisPrompt(diffAnalysisPromptTemplate(), diff);
    }

    public String diffAnalysisPromptTemplate() {
        return """
                # Role
                You are an experienced software engineering reviewer.

                # Task
                Analyze the supplied Merge Request diff and classify it across five metrics:
                1. Change type
                2. Problem-solving complexity
                3. Code maintainability
                4. Potential defect in the submitted diff
                5. Project contribution level

                # Classification Rules
                - Change type is the primary nature of this code change:
                  - New Feature: adds user-facing or system functionality.
                  - Bug Fix: fixes existing incorrect behavior.
                  - Optimization: improves performance or resource usage.
                  - Auto-generated: generated files or mechanical generated output.
                  - Refactor: restructures code without changing intended behavior.
                - Complexity is the difficulty of the underlying problem, considering domain difficulty, implementation difficulty, integration constraints, and required reasoning.
                - Maintainability is the readability and maintainability of the submitted code, including clarity, structure, naming, useful comments, and ease of future modification.
                - Contribution is the impact of the change on the project, users, architecture, quality, operations, or delivery progress.
                - Set bug=true only when the submitted diff itself appears to introduce or contain a likely defect, regression, missing validation, unsafe behavior, or broken logic.
                - Do not set bug=true merely because the Merge Request fixes an existing bug.

                # Output Contract
                - Return JSON that conforms to the provided schema.
                - For each metric, output both the selected value and metric-specific reasoning.
                - All user-facing reasoning text must be written in Japanese.
                - Each reasoning must explain only that metric's decision; do not combine all reasoning into one general explanation.

                # Diff
                %s
                """;
    }

    private String renderDiffAnalysisPrompt(String promptTemplate, String diff) {
        String template = (promptTemplate == null || promptTemplate.isBlank())
                ? diffAnalysisPromptTemplate()
                : promptTemplate;
        if (template.contains("%s")) {
            return template.formatted(diff);
        }
        return template + "\n\n# Diff\n" + diff;
    }

    String buildPerformanceEvaluationPrompt(Map<String, Object> metrics, List<?> aiEvaluations) {
        return """
                # Role
                You are a senior engineering manager and an experienced code reviewer.

                # Task
                Evaluate the target's engineering performance using:
                - SPACE scores and sub-metrics.
                - Current metric configuration, including weights and thresholds.
                - Precomputed MR quality evaluations generated from code diffs.

                Generate practical feedback and improvement actions that can be used by an engineering team.

                # Output Language
                - All user-facing output text must be written in Japanese.
                - Use Japanese metric names in the output.
                - Do not expose internal metric keys in user-facing text.

                # High-Level SPACE Scores
                - Performance: %s
                - Activity: %s
                - Communication: %s
                - Efficiency: %s
                - Satisfaction: %s
                - Overall: %s

                # Data Availability
                %s

                # Interpretation Rules
                - Do not conclude that activity is insufficient only because commit count is low.
                - If there is activity in MRs, bug fixes, issue handling, review comments, or changed lines, treat low commit count as a possible work phase or work style signal.
                - Do not suggest increasing commit count as a goal by itself. If needed, suggest meaningful commit granularity only to improve traceability or reviewability.
                - "未取得" means that no value was supplied. Never replace it with 0, estimate it, or use it as evidence of a strength or weakness.
                - A numeric 0 may be interpreted as an observed zero only when Data Availability says "取得済み（値0）".
                - If 開発活動データ is "なし", do not evaluate the target as low-performing or inactive. State that performance cannot be assessed from the available activity data.
                - If 開発活動データ is "判定不能", explicitly state the uncertainty before drawing any activity-related conclusion.
                - When data is missing or sparse, recommend checking the search period, branch, GitLab identity mapping, labels, or data integration instead of recommending that the developer increase activity.
                - If the target is a whole project, evaluate from team and process perspectives.
                - If the target is an individual, base feedback on observable activity and avoid blaming language.

                # How To Use MR Quality Evaluations
                - MR quality evaluations are JSON with count, summary, and items.
                - Each item represents an AI analysis result for an individual MR.
                - If MR quality evaluations are empty, avoid firm claims about code quality and evaluate only from SPACE metrics.
                - When referencing an individual MR, cite it as MR-id<mrIid>.
                - Do not infer a whole trend from a single MR. Prefer the summary and common patterns across multiple MRs.
                - Do not overvalue Auto-generated MRs as evidence of activity or quality.

                # Required Output Sections
                Return exactly these three sections in Japanese:
                1. Strengths: Explain what is working, grounded in available high scores, healthy sub-metrics, or MR quality signals.
                2. Weaknesses: Explain risks grounded only in available low scores, sub-metrics, or MR quality signals.
                3. Suggestions: Recommend concrete actions that can be executed within the next 1-2 weeks and explain which metric should change.

                # Pairing Rules
                - weaknesses and suggestions must have the same number of items.
                - suggestions[i] must directly address weaknesses[i] in the same order.
                - Every weakness must start with exactly one tag in this format: 【関連SPACE：<Japanese SPACE category>／関連指標：<Japanese metric name>】.
                - The paired suggestion must start with the identical tag used by its weakness. Do not change the category or metric name between the pair.
                - After the tag, each weakness must state the observed evidence and the resulting risk.
                - After the tag, each suggestion must state a concrete action and end with "期待する変化：<related Japanese metric name and expected direction>".
                - Strengths should also start with the same tag format when they are supported by a specific SPACE metric.
                - If a suggestion uses MR quality evidence, mention the relevant MR-id<mrIid> or common pattern across multiple MRs.
                - Avoid vague advice such as "improve productivity" or "communicate more."

                # No-Activity Output Rule
                - When 開発活動データ is "なし" and there is no other reliable evidence, return one neutral weakness/suggestion pair using this identical tag:
                  【関連SPACE：データ品質／関連指標：活動データ取得状況】
                - In the same case, the strengths array must contain one neutral item with that tag stating that no strength can be identified from the unavailable activity data. Do not invent a strength.
                - The weakness must say that strengths and weaknesses cannot be assessed because activity data is unavailable; it must not call the developer's performance low.
                - The suggestion must recommend checking the period, branch, identity mapping, or GitLab linkage, and its expected change must be improved data coverage.

                # Metrics With Sub-Metrics
                %s

                # Active Metric Configuration
                %s

                # MR Quality Evaluations
                %s
                """
                .formatted(
                        promptValue(metrics, performanceScore),
                        promptValue(metrics, activityScore),
                        promptValue(metrics, communicationScore),
                        promptValue(metrics, efficiencyScore),
                        promptValue(metrics, satisfactionScore),
                        promptValue(metrics, spaceTotalScore),
                        new JSONObject(dataAvailabilityForPrompt(metrics)),
                        new JSONObject(metricsForPrompt(metrics)),
                        new JSONObject(metricConfigsForPrompt()),
                        aiEvaluationsForPrompt(aiEvaluations));
    }

    private Object promptValue(Map<String, Object> metrics, String key) {
        if (!metrics.containsKey(key) || metrics.get(key) == null) {
            return "未取得";
        }
        return metrics.get(key);
    }

    private Map<String, Object> dataAvailabilityForPrompt(Map<String, Object> metrics) {
        Map<String, Object> availability = new LinkedHashMap<>();
        Object activityFlag = metrics.get(hasActivity);
        if (activityFlag instanceof Boolean present) {
            availability.put("開発活動データ", present ? "あり" : "なし");
        } else {
            availability.put("開発活動データ", "判定不能（hasActivity未取得）");
        }

        Map<String, Object> dimensionStatus = new LinkedHashMap<>();
        dimensionStatus.put("パフォーマンス", dataState(metrics, performanceScore));
        dimensionStatus.put("アクティビティ", dataState(metrics, activityScore));
        dimensionStatus.put("コミュニケーション", dataState(metrics, communicationScore));
        dimensionStatus.put("効率性", dataState(metrics, efficiencyScore));
        dimensionStatus.put("満足度", dataState(metrics, satisfactionScore));
        dimensionStatus.put("総合", dataState(metrics, spaceTotalScore));
        availability.put("SPACEスコア状態", dimensionStatus);

        Map<String, Object> subMetricStatus = new LinkedHashMap<>();
        METRIC_PROMPT_DEFINITIONS.forEach((key, definition) ->
                subMetricStatus.put(definition.name(), dataState(metrics, key)));
        availability.put("サブ指標状態", subMetricStatus);
        return availability;
    }

    private String dataState(Map<String, Object> metrics, String key) {
        if (!metrics.containsKey(key) || metrics.get(key) == null) {
            return "未取得";
        }
        Object value = metrics.get(key);
        if (value instanceof Number number && number.doubleValue() == 0.0) {
            return "取得済み（値0）";
        }
        return "取得済み";
    }

    /**
     * プロンプト埋め込み用にメトリクスマップを加工します。
     * 重複を避けるため、事前に計算済みの aiEvaluations データを除外します。
     */
    private Map<String, Object> metricsForPrompt(Map<String, Object> metrics) {
        Map<String, Object> promptMetrics = new LinkedHashMap<>();
        promptMetrics.put("SPACEスコア", dimensionScoresForPrompt(metrics));
        promptMetrics.put("サブ指標", subMetricsForPrompt(metrics));

        Object aiCorrected = metrics.get("aiCorrected");
        if (aiCorrected instanceof Map<?, ?> correctedMetrics) {
            promptMetrics.put("AI補正後SPACEスコア", dimensionScoresForPrompt(correctedMetrics));
            promptMetrics.put("AI補正後サブ指標", subMetricsForPrompt(correctedMetrics));
        }

        return promptMetrics;
    }

    private Map<String, Object> metricConfigsForPrompt() {
        Map<String, Object> promptConfigs = new LinkedHashMap<>();
        List<Map<String, Object>> dimensions = metricWeightRepository.findByIsActive(true).stream()
                .filter(config -> config.getParentKey() == null || config.getParentKey().isBlank())
                .sorted(Comparator.comparing(MetricWeightManagement::getMetricKey))
                .map(config -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("指標キー", config.getMetricKey());
                    item.put("重み", config.getWeight());
                    item.put("説明", Objects.toString(config.getDescription(), ""));
                    return item;
                })
                .toList();

        List<Map<String, Object>> subMetrics = metricWeightRepository.findByIsActive(true).stream()
                .filter(config -> config.getParentKey() != null && !config.getParentKey().isBlank())
                .sorted(Comparator.comparing(MetricWeightManagement::getParentKey)
                        .thenComparing(MetricWeightManagement::getMetricKey))
                .map(config -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    MetricPromptDefinition definition = METRIC_PROMPT_DEFINITIONS.get(config.getMetricKey());
                    item.put("指標キー", config.getMetricKey());
                    item.put("指標名", definition != null ? definition.name() : config.getMetricKey());
                    item.put("カテゴリ", config.getParentKey());
                    item.put("重み", config.getWeight());
                    item.put("最小閾値", config.getMinThreshold());
                    item.put("最大閾値", config.getMaxThreshold());
                    item.put("評価方向", config.isPositiveMetric() ? "高いほど良い" : "低いほど良い");
                    item.put("意味", definition != null ? definition.description()
                            : Objects.toString(config.getDescription(), ""));
                    return item;
                })
                .toList();

        promptConfigs.put("SPACEカテゴリ設定", dimensions);
        promptConfigs.put("サブ指標設定", subMetrics);
        return promptConfigs;
    }

    private Map<String, Object> dimensionScoresForPrompt(Map<?, ?> metrics) {
        Map<String, Object> scores = new LinkedHashMap<>();
        putIfPresent(scores, "パフォーマンス", metrics, performanceScore);
        putIfPresent(scores, "アクティビティ", metrics, activityScore);
        putIfPresent(scores, "コミュニケーション", metrics, communicationScore);
        putIfPresent(scores, "効率性", metrics, efficiencyScore);
        putIfPresent(scores, "満足度", metrics, satisfactionScore);
        putIfPresent(scores, "総合", metrics, spaceTotalScore);
        return scores;
    }

    private List<Map<String, Object>> subMetricsForPrompt(Map<?, ?> metrics) {
        return METRIC_PROMPT_DEFINITIONS.entrySet().stream()
                .filter(entry -> metrics.containsKey(entry.getKey()) || metrics.containsKey(entry.getKey() + "Score"))
                .map(entry -> {
                    String metricKey = entry.getKey();
                    MetricPromptDefinition definition = entry.getValue();
                    Map<String, Object> metric = new LinkedHashMap<>();
                    metric.put("指標名", definition.name());
                    metric.put("カテゴリ", definition.category());
                    metric.put("意味", definition.description());
                    putIfPresent(metric, "値", metrics, metricKey);
                    putIfPresent(metric, "スコア", metrics, metricKey + "Score");
                    return metric;
                })
                .toList();
    }

    private JSONObject aiEvaluationsForPrompt(List<?> aiEvaluations) {
        JSONArray items = new JSONArray();
        Map<String, Integer> changeTypeCounts = new LinkedHashMap<>();
        Map<String, Integer> complexityCounts = new LinkedHashMap<>();
        Map<String, Integer> maintainabilityCounts = new LinkedHashMap<>();
        Map<String, Integer> contributionCounts = new LinkedHashMap<>();
        int bugCount = 0;

        if (aiEvaluations != null) {
            for (Object value : aiEvaluations) {
                if (value instanceof AiMrEvaluation eval) {
                    JSONObject item = new JSONObject();
                    item.put("mrIid", eval.getMrIid());
                    item.put("authorUsername", eval.getAuthorUsername());
                    item.put("targetBranch", eval.getTargetBranch());
                    item.put("mergedAt", eval.getMergedAt() != null ? eval.getMergedAt().toString() : null);
                    item.put("changeType", eval.getChangeType());
                    item.put("complexity", eval.getComplexity());
                    item.put("maintainability", eval.getMaintainability());
                    item.put("hasBug", eval.getHasBug());
                    item.put("contribution", eval.getContribution());
                    item.put("reasoning", parseReasoningForPrompt(eval.getReasoning()));
                    items.put(item);

                    increment(changeTypeCounts, eval.getChangeType());
                    increment(complexityCounts, eval.getComplexity());
                    increment(maintainabilityCounts, eval.getMaintainability());
                    increment(contributionCounts, eval.getContribution());
                    if (Boolean.TRUE.equals(eval.getHasBug())) {
                        bugCount++;
                    }
                } else if (value != null) {
                    items.put(new JSONObject().put("raw", Objects.toString(value, "")));
                }
            }
        }

        JSONObject summary = new JSONObject();
        summary.put("changeTypes", new JSONObject(changeTypeCounts));
        summary.put("complexity", new JSONObject(complexityCounts));
        summary.put("maintainability", new JSONObject(maintainabilityCounts));
        summary.put("contribution", new JSONObject(contributionCounts));
        summary.put("bugCount", bugCount);

        return new JSONObject()
                .put("count", items.length())
                .put("summary", summary)
                .put("items", items);
    }

    private Object parseReasoningForPrompt(String reasoning) {
        if (reasoning == null || reasoning.isBlank()) {
            return JSONObject.NULL;
        }
        String trimmed = reasoning.trim();
        try {
            if (trimmed.startsWith("{")) {
                return new JSONObject(trimmed);
            }
            if (trimmed.startsWith("[")) {
                return new JSONArray(trimmed);
            }
        } catch (Exception ignored) {
            // Fall through to plain text for older or malformed records.
        }
        return reasoning;
    }

    private void increment(Map<String, Integer> counts, String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        counts.merge(key, 1, Integer::sum);
    }

    private void putIfPresent(Map<String, Object> target, String promptKey, Map<?, ?> source, String sourceKey) {
        if (source.containsKey(sourceKey)) {
            target.put(promptKey, source.get(sourceKey));
        }
    }

    private JSONObject diffAnalysisSchema() {
        JSONObject properties = new JSONObject();
        properties.put("type", evaluationMetricSchema(
                stringEnum(
                        "Primary nature of the current code change. New Feature = newly added user/system functionality. Bug Fix = fixes existing incorrect behavior. Optimization = improves performance or resource usage. Auto-generated = generated files or mechanical generated output. Refactor = restructures code without changing intended behavior.",
                        "New Feature", "Bug Fix", "Optimization", "Auto-generated", "Refactor"),
                "Japanese reasoning for the selected change type."));
        properties.put("complexity", evaluationMetricSchema(
                stringEnum(
                        "Problem-solving difficulty for the developer. Consider domain difficulty, implementation difficulty, integration constraints, and required reasoning. low = routine/simple problem, mid = moderate reasoning or integration work, high = difficult problem requiring deep domain knowledge, complex design, or careful trade-offs.",
                        "low", "mid", "high"),
                "Japanese reasoning for the selected complexity level."));
        properties.put("maintainability", evaluationMetricSchema(
                stringEnum(
                        "Readability and maintainability of the submitted code. Consider clarity, structure, naming, appropriate comments, testability, and ease of future modification. low = hard to read or maintain, mid = acceptable but with some maintainability concerns, high = clear, well-structured, and easy to maintain.",
                        "low", "mid", "high"),
                "Japanese reasoning for the selected maintainability level."));
        properties.put("bug", evaluationMetricSchema(
                new JSONObject()
                        .put("type", "boolean")
                        .put("description",
                                "Whether the submitted diff itself appears to introduce or contain a likely defect, regression, missing validation, unsafe behavior, or broken logic. Return false when the diff is simply fixing an existing bug and does not appear to introduce a new defect."),
                "Japanese reasoning for whether the submitted diff appears to introduce or contain a defect."));
        properties.put("contribution", evaluationMetricSchema(
                stringEnum(
                        "Impact of this change on the overall project, users, architecture, quality, operations, or delivery progress. low = small/local effect, mid = meaningful feature/fix/improvement for part of the system, high = broad or important project-level impact.",
                        "low", "mid", "high"),
                "Japanese reasoning for the selected contribution level."));

        return objectSchema(properties,
                "type", "complexity", "maintainability", "bug", "contribution");
    }

    private JSONObject performanceEvaluationSchema() {
        JSONObject stringArray = new JSONObject()
                .put("type", "array")
                .put("items", new JSONObject().put("type", "string"))
                .put("minItems", 1);

        JSONObject properties = new JSONObject();
        properties.put("strengths", new JSONObject(stringArray.toString())
                .put("description", "Strengths supported by high scores, healthy sub-metrics, or MR quality signals."));
        properties.put("weaknesses", new JSONObject(stringArray.toString())
                .put("description",
                        "Weaknesses or risks supported by available evidence. Every item must start with 【関連SPACE：...／関連指標：...】."));
        properties.put("suggestions", new JSONObject(stringArray.toString())
                .put("description",
                        "Concrete next actions. Keep the same item count, order, and identical metric tag as weaknesses, and end with the expected metric change."));

        return objectSchema(properties, "strengths", "weaknesses", "suggestions");
    }

    private JSONObject objectSchema(JSONObject properties, String... requiredFields) {
        return new JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", new JSONArray(List.of(requiredFields)))
                .put("additionalProperties", false);
    }

    private JSONObject evaluationMetricSchema(JSONObject valueSchema, String reasoningDescription) {
        JSONObject properties = new JSONObject();
        properties.put("value", valueSchema);
        properties.put("reasoning", new JSONObject()
                .put("type", "string")
                .put("description", reasoningDescription));
        return objectSchema(properties, "value", "reasoning");
    }

    private JSONObject stringEnum(String description, String... values) {
        return new JSONObject()
                .put("type", "string")
                .put("description", description)
                .put("enum", new JSONArray(List.of(values)));
    }

    private record MetricPromptDefinition(String name, String description, String category) {
    }
}
