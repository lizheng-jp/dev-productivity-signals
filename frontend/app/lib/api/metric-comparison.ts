import { get } from './client';
import type { SpaceMetricsResponse } from './space-metrics';
import type { ProjectMember } from './members';
export interface MetricTrend {
  current: number | null; previous: number | null; change: number | null; percentChange: number | null;
  status: 'comparable' | 'new' | 'unavailable'; lowerIsBetter: boolean; dailyNormalized: boolean;
}
export interface DashboardTimePoint {
  since: string; until: string; period: 'previous' | 'current'; commitCount: number; mergedCount: number;
  averageLeadTimeHours: number | null; medianLeadTimeHours: number | null; sampleCount: number;
}
export interface MetricPeriod { project: SpaceMetricsResponse; members: SpaceMetricsResponse[]; commitTrend: { weekStart: string; count: number }[] }
export interface MetricComparison {
  periods: { current: { since: string; until: string }; previous: { since: string; until: string } };
  current: MetricPeriod; previous: MetricPeriod; members: ProjectMember[];
  projectTrends: Record<string, MetricTrend>; developerTrends: Record<string, Record<string, MetricTrend>>;
  comparisonId: string;
  deliveryTrend: DashboardTimePoint[];
}
export const getMetricComparison = (projectId: string, params: { since: string; until: string; refName?: string; previousDays?: number; aiEnabled?: boolean; comparisonId?: string }) =>
  get<MetricComparison>(`/api/gitlab/projects/${projectId}/space-metrics/comparison`, params);
