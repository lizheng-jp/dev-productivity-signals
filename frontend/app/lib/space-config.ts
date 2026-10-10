// This file centralizes the configuration for SPACE dimensions and their metrics.
// It ensures consistency across different components like ComparisonView and MetricWeightsView.

import { SPACE_METRICS } from "./api/space-metrics.constants";

export const spaceGroupsConfig = [
    {
        nameKey: 'performance',
        descriptionKey: 'performanceDescription',
        scoreKey: 'performanceScore',
        color: 'blue',
        metrics: [
            { labelKey: SPACE_METRICS.mergedCount, key: SPACE_METRICS.mergedCount, unit: undefined },
            { labelKey: SPACE_METRICS.bugCausedCount, key: SPACE_METRICS.bugCausedCount, unit: undefined },
            { labelKey: SPACE_METRICS.bugFixLeadTimeHours, key: SPACE_METRICS.bugFixLeadTimeHours, unit: 'h' },
        ]
    },
    {
        nameKey: 'activity',
        descriptionKey: 'activityDescription',
        scoreKey: 'activityScore',
        color: 'amber',
        metrics: [
            { labelKey: SPACE_METRICS.commitCount, key: SPACE_METRICS.commitCount, unit: undefined },
            { labelKey: SPACE_METRICS.issueCreatedCount, key:  SPACE_METRICS.issueCreatedCount, unit: undefined },
            { labelKey: SPACE_METRICS.bugFoundCount, key: SPACE_METRICS.bugFoundCount, unit: undefined },
            { labelKey: SPACE_METRICS.bugFixedCount, key: SPACE_METRICS.bugFixedCount, unit: undefined },
            { labelKey: SPACE_METRICS.linesAdded, key: SPACE_METRICS.linesAdded, unit: undefined },
            { labelKey: SPACE_METRICS.linesDeleted, key: SPACE_METRICS.linesDeleted, unit: undefined },
            { labelKey: SPACE_METRICS.linesTotal, key: SPACE_METRICS.linesTotal, unit: undefined },
        ]
    },
    {
        nameKey: 'communication',
        descriptionKey: 'communicationDescription',
        scoreKey: 'communicationScore',
        color: 'purple',
        metrics: [
            { labelKey: SPACE_METRICS.reviewedCount, key: SPACE_METRICS.reviewedCount, unit: undefined },
            { labelKey: SPACE_METRICS.commentCount, key: SPACE_METRICS.commentCount, unit: undefined },
            { labelKey: SPACE_METRICS.reviewCommentCount, key: SPACE_METRICS.reviewCommentCount, unit: undefined },
        ]
    },
    {
        nameKey: 'efficiency',
        descriptionKey: 'efficiencyDescription',
        scoreKey: 'efficiencyScore',
        color: 'emerald',
        metrics: [
            { labelKey: SPACE_METRICS.mergedLeadTimeHours, key: SPACE_METRICS.mergedLeadTimeHours, unit: 'h' },
            { labelKey: SPACE_METRICS.reviewWaitTime, key: SPACE_METRICS.reviewWaitTime, unit: 'h' },
            { labelKey: SPACE_METRICS.uninterruptedFocusTimeHours, key: SPACE_METRICS.uninterruptedFocusTimeHours, unit: 'h/日' },
            { labelKey: SPACE_METRICS.contextSwitchFrequency, key: SPACE_METRICS.contextSwitchFrequency, unit: '回/日' },
        ]
    },
    {
        nameKey: 'satisfaction',
        descriptionKey: 'satisfactionDescription',
        scoreKey: 'satisfactionScore',
        color: 'rose',
        metrics: [
            { labelKey: SPACE_METRICS.satisfactionJobMeaning, key: SPACE_METRICS.satisfactionJobMeaning, unit: undefined },
            { labelKey: SPACE_METRICS.satisfactionDeveloperEfficacy, key: SPACE_METRICS.satisfactionDeveloperEfficacy, unit: undefined },
            { labelKey: SPACE_METRICS.satisfactionSustainability, key: SPACE_METRICS.satisfactionSustainability, unit: undefined },
            { labelKey: SPACE_METRICS.satisfactionImprovementPotential, key: SPACE_METRICS.satisfactionImprovementPotential, unit: undefined },
            { labelKey: SPACE_METRICS.contributorRetentionRate, key: SPACE_METRICS.contributorRetentionRate, unit: '%' },
        ]
    }
];
