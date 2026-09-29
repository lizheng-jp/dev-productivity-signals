
package dev.productivity.signals.service;

import dev.productivity.signals.entity.MetricWeightManagement;
import dev.productivity.signals.repository.MetricWeightManagementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static dev.productivity.signals.util.SpaceMetricConstants.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class SpaceMetricScoringService {

    private final MetricWeightManagementRepository metricWeightRepository;

    // 週次で正規化が必要な指標
    private static final Set<String> WEEKLY_NORMALIZED_METRICS = Set.of(
            mergedCount, bugCausedCount, commitCount, bugFoundCount, bugFixedCount, commentCount, issueCreatedCount,
            reviewedCount, linesAdded, linesDeleted, linesTotal);

    private static final Set<String> NON_SCORING_METRICS = Set.of();

    private static final Set<String> ACTIVITY_DETECTION_METRICS = Set.of(
            mergedCount,
            bugCausedCount,
            bugFixLeadTimeHours,
            commitCount,
            bugFoundCount,
            bugFixedCount,
            issueCreatedCount,
            reviewedCount,
            commentCount,
            reviewWaitTime,
            reviewCommentCount,
            linesAdded,
            linesDeleted,
            linesTotal);

    private static final Map<String, String> METRIC_DEPENDENCIES = Map.of(bugCausedCount, commitCount, bugFixLeadTimeHours, bugFixedCount, mergedLeadTimeHours, mergedCount, reviewWaitTime, mergedCount);

    private static final Map<String, String> METRIC_PARENT_OVERRIDES = Map.of(mergedLeadTimeHours, "efficiency");

    /**
     * 各指標の値からスコアを計算します。
     *
     * @param metrics 指標名と値のマップ（期間内の合計値）
     * @param weeks   スコア計算に使用する期間の週数（正規化用）
     * @return スコア計算結果を含むマップ
     */
    public Map<String, Object> calculateScores(Map<String, Number> metrics, double weeks) {
        if (metrics == null || metrics.isEmpty()) {
            return null;
        }

        List<MetricWeightManagement> activeConfigs = metricWeightRepository.findByIsActive(true);
        return calculateScores(metrics, weeks, activeConfigs);
    }

    public Map<String, Object> calculateScores(
            Map<String, Number> metrics,
            double weeks,
            List<MetricWeightManagement> activeConfigs) {
        if (metrics == null || metrics.isEmpty()) {
            return null;
        }

        if (activeConfigs.isEmpty()) {
            log.warn("No active metric configurations found in the database. Unable to calculate scores.");
            return new HashMap<>(metrics);
        }
        Map<String, MetricWeightManagement> metricConfigMap = activeConfigs.stream().collect(Collectors.toMap(MetricWeightManagement::getMetricKey, Function.identity()));

        Map<String, Object> results = new HashMap<>(metrics);
        Map<String, Double> scoredMetrics = new HashMap<>();

        // 1. 個別の指標スコアを計算
        metrics.forEach((key, value) -> {
            if (metricConfigMap.containsKey(key) && value != null && !NON_SCORING_METRICS.contains(key)) {
                // 依存関係のチェック
                String dependencyKey = METRIC_DEPENDENCIES.get(key);
                if (dependencyKey != null) {
                    double depValue = metrics.getOrDefault(dependencyKey, 0).doubleValue();
                    if (depValue <= 0) {
                        return;
                    }
                }

                double valForScoring = value.doubleValue();
                if (WEEKLY_NORMALIZED_METRICS.contains(key) && weeks > 0) {
                    valForScoring = valForScoring / weeks;
                }

                double score = calculateSingleScore(metricConfigMap.get(key), valForScoring);
                results.put(key + "Score", score);
                scoredMetrics.put(key, score);
            }
        });

        // アクティビティフラグ
        results.put(hasActivity, hasDevelopmentActivity(metrics));

        // 2. 次元スコアと総合スコアを計算
        Map<String, List<MetricWeightManagement>> dimensions = activeConfigs.stream()
                .filter(this::hasEffectiveParentKey)
                .collect(Collectors.groupingBy(this::effectiveParentKey));

        Map<String, MetricWeightManagement> dimensionConfigs = activeConfigs.stream()
                .filter(c -> c.getParentKey() == null || c.getParentKey().isEmpty())
                .collect(Collectors.toMap(MetricWeightManagement::getMetricKey, Function.identity()));

        double totalWeightedScore = 0.0;
        double totalDimensionWeight = 0.0;

        for (Map.Entry<String, List<MetricWeightManagement>> entry : dimensions.entrySet()) {
            String dimName = entry.getKey();
            List<MetricWeightManagement> dimMetrics = entry.getValue();
            MetricWeightManagement dimConfig = dimensionConfigs.get(dimName);

            double dimWeightedScoreSum = 0.0;
            double dimTotalWeight = 0.0;

            for (MetricWeightManagement metric : dimMetrics) {
                if (scoredMetrics.containsKey(metric.getMetricKey())) {
                    dimWeightedScoreSum += scoredMetrics.get(metric.getMetricKey()) * metric.getWeight();
                    dimTotalWeight += metric.getWeight();
                }
            }

            if (dimTotalWeight > 0) {
                double dimScore = dimWeightedScoreSum / dimTotalWeight;
                results.put(dimName + "Score", dimScore);
                if (dimConfig != null) {
                    totalWeightedScore += dimScore * dimConfig.getWeight();
                    totalDimensionWeight += dimConfig.getWeight();
                }
            }
        }

        double totalScore = (totalDimensionWeight > 0) ? totalWeightedScore / totalDimensionWeight : 0.0;
        results.put(spaceTotalScore, totalScore);

        // サニタイズ
        results.replaceAll((k, v) -> {
            if (v instanceof Double) {
                double d = (Double) v;
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    return 0.0;
                }
                return Math.round(d * 100.0) / 100.0;
            }
            return v;
        });

        return results;
    }

    public Map<String, Object> applyDimensionScoreOverride(
            Map<String, Object> results,
            String dimensionKey,
            Double score) {
        if (results == null || score == null) {
            return results;
        }

        double sanitizedScore = Math.max(0.0, Math.min(100.0, score));
        results.put(dimensionKey + "Score", round(sanitizedScore));

        List<MetricWeightManagement> activeConfigs = metricWeightRepository.findByIsActive(true);
        double totalWeightedScore = 0.0;
        double totalDimensionWeight = 0.0;

        for (MetricWeightManagement config : activeConfigs) {
            if (config.getParentKey() != null && !config.getParentKey().isEmpty()) {
                continue;
            }

            Object dimensionScore = results.get(config.getMetricKey() + "Score");
            if (dimensionScore instanceof Number number) {
                totalWeightedScore += number.doubleValue() * config.getWeight();
                totalDimensionWeight += config.getWeight();
            }
        }

        if (totalDimensionWeight > 0) {
            results.put(spaceTotalScore, round(totalWeightedScore / totalDimensionWeight));
        }
        return results;
    }

    /**
     * 単一の指標に対してスコア（0.0〜100.0）を計算します。
     *
     * @param config 指標の設定
     * @param value  指標の実際の値
     * @return 0.0から100.0の範囲のスコア
     */
    private double calculateSingleScore(MetricWeightManagement config, double value) {
        // 特別ルール：レビュー待ち時間が0の場合、レビュー活動がなかったと見なしスコアを0にする
        if (reviewWaitTime.equals(config.getMetricKey()) && value == 0.0) {
            return 0.0;
        }
        if (reviewCommentCount.equals(config.getMetricKey()) && value == 0.0) {
            return 0.0;
        }

        double benchLo = config.getMinThreshold();
        double benchHi = config.getMaxThreshold();

        double score;
        if (!config.isPositiveMetric()) { // 低い方が良い (LOWER_IS_BETTER)
            if (value <= benchLo)
                score = 100.0;
            else if (value >= benchHi)
                score = 0.0;
            else
                score = (benchHi - value) / (benchHi - benchLo) * 100.0;
        } else { // 高い方が良い (HIGHER_IS_BETTER)
            if (value <= benchLo)
                score = 0.0;
            else if (value >= benchHi)
                score = 100.0;
            else
                score = (value - benchLo) / (benchHi - benchLo) * 100.0;
        }

        return Math.max(0.0, Math.min(100.0, score));
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private boolean hasEffectiveParentKey(MetricWeightManagement config) {
        String parentKey = effectiveParentKey(config);
        return parentKey != null && !parentKey.isEmpty();
    }

    private String effectiveParentKey(MetricWeightManagement config) {
        return METRIC_PARENT_OVERRIDES.getOrDefault(config.getMetricKey(), config.getParentKey());
    }

    private boolean hasDevelopmentActivity(Map<String, Number> metrics) {
        return ACTIVITY_DETECTION_METRICS.stream()
                .map(metrics::get)
                .filter(Objects::nonNull)
                .anyMatch(value -> value.doubleValue() > 0.0);
    }
}
