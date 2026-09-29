"use client";
import React, { useState } from 'react';
import { DateRange } from 'react-day-picker';
import { TrendBadge } from '@/components/ui/TrendBadge';
import { DashboardTrendCharts } from './DashboardTrendCharts';
import type { MetricComparison, MetricTrend } from '@/lib/api/metric-comparison';
import { Card } from '@/components/ui/Common';
import { DeveloperDetailPanel } from './DeveloperDetailPanel';
import { Project } from '@/lib/api/projects';
import { MetricWeight } from '@/lib/api/metric-weights';
import { DeveloperStat } from '@/types/developer';
import { ProjectStat } from '@/types/project';
import { useTranslations } from 'next-intl';
import { CircleHelp, Loader2 } from 'lucide-react';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { GROUP_UI_ENABLED } from '@/lib/feature-flags';

interface DashboardViewProps {
  comparison?: MetricComparison | null;
  error?: string | null;
  projects: Project[];
  isLoadingProjects: boolean;
  errorProjects: string | null;
  selectedProjectId: string | null;
  date: DateRange | undefined;
  developerStats: DeveloperStat[];
  isDevLoading: boolean;
  projectStats: ProjectStat | null;
  isProjectLoading: boolean;
  isAiCorrecting: boolean;
  metricConfigs: MetricWeight[];
  onMetricConfigsNeeded?: () => Promise<void>;
}

const LoadingValue = ({ tone = 'slate' }: { tone?: 'slate' | 'emerald' | 'blue' }) => {
  const toneClass = tone === 'emerald'
    ? 'bg-emerald-100'
    : tone === 'blue'
      ? 'bg-blue-100'
      : 'bg-slate-200';

  return (
    <span
      className={`inline-block h-9 w-20 animate-pulse rounded-md bg-gradient-to-r from-transparent via-white/50 to-transparent ${toneClass}`}
      aria-label="Loading"
    />
  );
};

const LoadingTableRow = ({ colSpan, label }: { colSpan: number; label: string }) => (
  <tr>
    <td colSpan={colSpan} className="px-6 py-10 text-center text-slate-400">
      <span className="inline-flex items-center gap-2">
        <Loader2 className="h-4 w-4 animate-spin" />
        {label}
      </span>
    </td>
  </tr>
);

const MetricDescriptionPopover = ({
  label,
  description,
}: {
  label: string;
  description: string;
}) => (
  <Popover>
    <PopoverTrigger asChild>
      <button
        type="button"
        aria-label={`${label}: ${description}`}
        title={description}
        className="inline-flex h-5 w-5 items-center justify-center rounded-full text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2"
      >
        <CircleHelp className="h-3.5 w-3.5" aria-hidden="true" />
      </button>
    </PopoverTrigger>
    <PopoverContent
      align="start"
      className="w-64 border-slate-200 bg-white p-3 text-xs leading-5 text-slate-600"
    >
      {description}
    </PopoverContent>
  </Popover>
);

const DashboardMetricCard = ({
  label,
  description,
  value,
  isLoading, trend, unit, unavailable,
}: {
  unavailable?: boolean;
  trend?: MetricTrend;
  unit?: string;
  label: string;
  description: string;
  value: number;
  isLoading: boolean;
}) => (
  <Card className="flex h-32 flex-col justify-between p-4">
    <div className="flex items-start gap-1.5">
      <p className="text-sm font-medium text-slate-600">{label}</p>
      <MetricDescriptionPopover label={label} description={description} />
    </div>
    <div className="flex flex-wrap items-end justify-between gap-2">
      <span className="text-2xl font-bold text-slate-800">
        {isLoading ? <LoadingValue /> : unavailable ? '—' : unit ? <>{trend?.current === null ? '—' : value.toFixed(1)}<span className="ml-1 text-sm font-normal">{unit}</span></> : Math.round(value)}
      </span>
      {!isLoading && <TrendBadge trend={trend} />}
    </div>
  </Card>
);

export const DashboardView = (props: DashboardViewProps) => {
  const t = useTranslations('Dashboard');
  const tTrend = useTranslations('ComparisonTrend');
  const tInsights = useTranslations('TrendsPanel');
  const tTable = useTranslations('Table');
  const {
    developerStats,
    isDevLoading,
    projectStats,
    isProjectLoading,
    isLoadingProjects,
    isAiCorrecting,
    metricConfigs,
    onMetricConfigsNeeded,
  } = props;
  const [selectedUser, setSelectedUser] = useState<string | null>(null);
  const selectedDeveloper = developerStats.find(stat => stat.member.userCode === selectedUser) ?? null;

  const handleDeveloperSelect = (stat: DeveloperStat) => {
    setSelectedUser(stat.member.userCode);
    if (metricConfigs.length === 0) {
      void onMetricConfigsNeeded?.();
    }
  };


  const sortedDeveloperStats = React.useMemo(() => {
    return [...developerStats].sort((a, b) => (b.totalScore || 0) - (a.totalScore || 0));
  }, [developerStats]);

  const isDeveloperSectionLoading = isLoadingProjects || isDevLoading;
  const isProjectSectionLoading = isLoadingProjects || isProjectLoading;

  const totalCommits = projectStats?.commitCount || 0;
  const totalMrs = projectStats?.mergedCount || 0;

  return (
    <div className="space-y-6">

      <div className="flex justify-between items-center">
        <h2 className="text-2xl font-bold text-slate-800">{t('overview')}</h2>
      </div>

      {props.error && <p role="alert" className="rounded border border-rose-200 bg-rose-50 p-4 text-sm text-rose-700">{tTrend('loadError')}</p>}
      <DeveloperDetailPanel
        isOpen={selectedDeveloper !== null}
        onClose={() => setSelectedUser(null)}
        developer={selectedDeveloper}
        metricConfigs={metricConfigs}
      />

      <div aria-busy={isDeveloperSectionLoading || isProjectSectionLoading}>
        <div className="space-y-6">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
            <DashboardMetricCard
              unavailable={!projectStats}
              label={t('totalCommits')}
              description={t('totalCommitsDescription')}
              trend={props.comparison?.projectTrends.commitCount}
              value={totalCommits}
              isLoading={isProjectSectionLoading}
            />
            <DashboardMetricCard
              unavailable={!projectStats}
              label={tTable('totalMrs')}
              description={t('totalMrsDescription')}
              trend={props.comparison?.projectTrends.mergedCount}
              value={totalMrs}
              isLoading={isProjectSectionLoading}
            />
            <DashboardMetricCard unavailable={!projectStats || projectStats.mergedCount === 0} label={tInsights('avgLeadTime')} description={tInsights('avgLeadTimeDetail')} value={projectStats?.mergedLeadTimeHours ?? 0} trend={projectStats?.trends?.mergedLeadTimeHours} unit="h" isLoading={isProjectSectionLoading} />

            <DashboardMetricCard unavailable={projectStats?.spaceMetrics?.spaceTotalScore === undefined} label={tInsights('projectSpaceScore')} description={tInsights('projectSpaceDetail')} value={projectStats?.totalScore ?? 0} trend={projectStats?.trends?.spaceTotalScore} isLoading={isProjectSectionLoading} />
          </div>
          <DashboardTrendCharts comparison={props.comparison ?? null} isLoading={isProjectSectionLoading} />

          {/* Leaderboard */}
          <Card className="overflow-hidden">
            <div className="px-6 py-4 border-b border-slate-200 bg-slate-50 font-semibold text-slate-700">
              {t('developerLeaderboard')}
            </div>
            <div className="overflow-x-auto"><table className="w-full text-sm text-left">
              <thead className="bg-white text-slate-500 border-b border-slate-200">
                <tr>
                  <th className="px-6 py-3">{tTable('developer')}</th>
                  {GROUP_UI_ENABLED && <th className="px-6 py-3">{tTable('group')}</th>}
                  <th className="px-6 py-3">{tTable('commits')}</th>
                  <th className="px-6 py-3">{tTable('merges')}</th>
                  <th className="px-6 py-3">{tTable('issues')}</th>
                  <th className="px-6 py-3">{tTable('bugs')}</th>
                  <th className="px-6 py-3">{tInsights('avgLeadTime')}</th>
                  <th className="px-6 py-3">{tTable('score')}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {isDeveloperSectionLoading ? (
                  <LoadingTableRow colSpan={GROUP_UI_ENABLED ? 8 : 7} label={tTable('loading')} />
                ) : sortedDeveloperStats.map((stat) => (
                    <tr key={stat.member.userCode} onClick={() => handleDeveloperSelect(stat)} className="hover:bg-blue-50/50 transition cursor-pointer group">
                      <td className="px-6 py-4 font-medium text-slate-900 group-hover:text-blue-700 transition-colors">{stat.member.userName} ({stat.member.userCode})</td>
                      {GROUP_UI_ENABLED && <td className="px-6 py-4 text-slate-500">{stat.member.groupName || ''}</td>}
                      <td className="px-6 py-4 font-mono"><div className="flex flex-wrap items-center justify-between gap-1">{Math.round(stat.commitCount || 0)}<TrendBadge trend={stat.trends?.commitCount} /></div></td>
                      <td className="px-6 py-4 font-mono"><div className="flex flex-wrap items-center justify-between gap-1">{Math.round(stat.mergedCount || 0)}<TrendBadge trend={stat.trends?.mergedCount} /></div></td>
                      <td className="px-6 py-4 font-mono"><div className="flex flex-wrap items-center justify-between gap-1">{Math.round(stat.issueCreatedCount || 0)}<TrendBadge trend={stat.trends?.issueCreatedCount} /></div></td>
                      <td className="px-6 py-4 font-mono"><div className="flex flex-wrap items-center justify-between gap-1">{Math.round(stat.bugCausedCount || 0)}<TrendBadge trend={stat.trends?.bugCausedCount} /></div></td>
                      <td className="px-6 py-4"><div className="flex flex-wrap items-center gap-1">{stat.mergedCount ? (stat.mergedLeadTimeHours ?? 0).toFixed(1) + 'h' : '—'}<TrendBadge trend={stat.trends?.mergedLeadTimeHours} /></div></td>
                      <td className="px-6 py-4">
                        <span className="inline-flex items-center gap-2 font-bold text-blue-600">
                          {(stat.totalScore || 0).toFixed(1)}
                          <TrendBadge trend={stat.trends?.spaceTotalScore} />
                          {isAiCorrecting && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
                        </span>
                      </td>
                    </tr>
                ))}
              </tbody>
            </table></div>
          </Card>

        </div>
      </div>

    </div>
  );
};
