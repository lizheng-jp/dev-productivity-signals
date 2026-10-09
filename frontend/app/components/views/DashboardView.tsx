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
import { CircleHelp, Clock3, GitCommitHorizontal, GitMerge, Loader2, ShieldCheck, type LucideIcon } from 'lucide-react';
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
      className="w-64 p-3 text-xs leading-5 text-slate-600"
    >
      {description}
    </PopoverContent>
  </Popover>
);

const ACCENTS = {
  blue: { chip: 'bg-blue-500/10 text-blue-500', stroke: 'var(--color-aurora-blue)' },
  violet: { chip: 'bg-violet-500/10 text-violet-500', stroke: 'var(--color-aurora-violet)' },
  cyan: { chip: 'bg-cyan-500/10 text-cyan-600', stroke: 'var(--color-aurora-cyan)' },
} as const;
type Accent = keyof typeof ACCENTS;

const Sparkline = ({ values, accent }: { values: number[]; accent: Accent }) => {
  if (values.length < 2) return null;
  const width = 112;
  const height = 40;
  const max = Math.max(...values) || 1;
  const points = values.map((value, index) => [
    (index / (values.length - 1)) * width,
    height - 4 - (value / max) * (height - 8),
  ]);
  const line = points.map(([x, y], index) => `${index ? 'L' : 'M'}${x.toFixed(1)},${y.toFixed(1)}`).join(' ');
  const gradientId = `spark-${accent}`;
  return (
    <svg viewBox={`0 0 ${width} ${height}`} className="h-10 w-28 shrink-0" aria-hidden="true">
      <defs>
        <linearGradient id={gradientId} x1="0" x2="0" y1="0" y2="1">
          <stop offset="0" stopColor={ACCENTS[accent].stroke} stopOpacity="0.3" />
          <stop offset="1" stopColor={ACCENTS[accent].stroke} stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={`${line} L${width},${height} L0,${height}Z`} fill={`url(#${gradientId})`} />
      <path d={line} fill="none" stroke={ACCENTS[accent].stroke} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
};

const ScoreRing = ({ value }: { value: number }) => {
  const radius = 40;
  const length = 2 * Math.PI * radius;
  const ratio = Math.min(Math.max(value / 100, 0), 1);
  return (
    <div className="relative h-[76px] w-[76px] shrink-0">
      <svg viewBox="0 0 100 100" className="h-full w-full -rotate-90" aria-hidden="true">
        <defs>
          <linearGradient id="score-ring" x1="0" x2="1">
            <stop offset="0" stopColor="var(--color-aurora-blue)" />
            <stop offset="0.55" stopColor="var(--color-aurora-violet)" />
            <stop offset="1" stopColor="var(--color-aurora-cyan)" />
          </linearGradient>
        </defs>
        <circle cx="50" cy="50" r={radius} fill="none" stroke="rgb(120 135 170 / 0.15)" strokeWidth="9" />
        <circle
          cx="50" cy="50" r={radius} fill="none" stroke="url(#score-ring)" strokeWidth="9" strokeLinecap="round"
          strokeDasharray={length} strokeDashoffset={length * (1 - ratio)}
          className="animate-ring-draw drop-shadow-[0_0_6px_rgb(139_92_246/0.45)]"
          style={{ '--ring-length': length } as React.CSSProperties}
        />
      </svg>
      <span className="kicker absolute inset-0 flex items-center justify-center">/100</span>
    </div>
  );
};

const DashboardMetricCard = ({
  label,
  description,
  value,
  isLoading, trend, unit, unavailable,
  icon: Icon, accent = 'blue', spark, ring,
}: {
  unavailable?: boolean;
  trend?: MetricTrend;
  unit?: string;
  label: string;
  description: string;
  value: number;
  isLoading: boolean;
  icon: LucideIcon;
  accent?: Accent;
  spark?: number[];
  ring?: boolean;
}) => {
  const showValue = !isLoading && !unavailable;
  return (
    <Card className="group flex h-36 flex-col justify-between p-5 transition-all duration-300 hover:-translate-y-0.5 hover:shadow-md">
      <div className="flex items-center gap-2">
        <span className={`flex h-7 w-7 items-center justify-center rounded-lg ${ACCENTS[accent].chip}`}>
          <Icon className="h-3.5 w-3.5" aria-hidden="true" />
        </span>
        <p className="text-[13px] font-semibold text-slate-600">{label}</p>
        <MetricDescriptionPopover label={label} description={description} />
      </div>
      <div className="flex items-end justify-between gap-3">
        <div className="flex min-w-0 flex-col gap-1.5">
          <span className="font-mono text-[32px] font-bold leading-none tracking-tight text-slate-900 tabular">
            {isLoading ? <LoadingValue /> : unavailable ? '—' : unit ? <>{trend?.current === null ? '—' : value.toFixed(1)}<span className="ml-1 text-sm font-medium text-slate-400">{unit}</span></> : Math.round(value)}
          </span>
          {!isLoading && <div className="h-5"><TrendBadge trend={trend} /></div>}
        </div>
        {showValue && ring && <ScoreRing value={value} />}
        {showValue && !ring && spark && <Sparkline values={spark} accent={accent} />}
      </div>
    </Card>
  );
};

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
  const deliveryPoints = (props.comparison?.deliveryTrend ?? []).filter(point => point.period === 'current');
  const commitSpark = deliveryPoints.map(point => point.commitCount);
  const mergedSpark = deliveryPoints.map(point => point.mergedCount);
  const leadTimeSpark = deliveryPoints.map(point => point.averageLeadTimeHours).filter((value): value is number => value !== null);

  return (
    <div className="space-y-6">

      <div className="animate-rise">
        <p className="kicker" aria-hidden="true">Workspace / Dashboard</p>
        <h2 className="mt-1 text-3xl font-extrabold tracking-tight text-slate-900">{t('overview')}</h2>
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
              icon={GitCommitHorizontal}
              spark={commitSpark}
            />
            <DashboardMetricCard
              unavailable={!projectStats}
              label={tTable('totalMrs')}
              description={t('totalMrsDescription')}
              trend={props.comparison?.projectTrends.mergedCount}
              value={totalMrs}
              isLoading={isProjectSectionLoading}
              icon={GitMerge}
              accent="violet"
              spark={mergedSpark}
            />
            <DashboardMetricCard unavailable={!projectStats || projectStats.mergedCount === 0} label={tInsights('avgLeadTime')} description={tInsights('avgLeadTimeDetail')} value={projectStats?.mergedLeadTimeHours ?? 0} trend={projectStats?.trends?.mergedLeadTimeHours} unit="h" isLoading={isProjectSectionLoading} icon={Clock3} accent="cyan" spark={leadTimeSpark} />

            <DashboardMetricCard unavailable={projectStats?.spaceMetrics?.spaceTotalScore === undefined} label={tInsights('projectSpaceScore')} description={tInsights('projectSpaceDetail')} value={projectStats?.totalScore ?? 0} trend={projectStats?.trends?.spaceTotalScore} isLoading={isProjectSectionLoading} icon={ShieldCheck} ring />
          </div>
          <DashboardTrendCharts comparison={props.comparison ?? null} isLoading={isProjectSectionLoading} />

          {/* Leaderboard */}
          <Card className="overflow-hidden">
            <div className="flex items-center gap-3 px-6 py-4">
              <h3 className="text-base font-bold tracking-tight text-slate-900">{t('developerLeaderboard')}</h3>
              <span className="h-px flex-1 bg-gradient-to-r from-slate-200 to-transparent" />
              <span className="kicker" aria-hidden="true">Top performers</span>
            </div>
            <div className="overflow-x-auto"><table className="w-full text-sm text-left">
              <thead className="kicker border-y border-slate-200/70 bg-slate-50/60 [&_th]:font-semibold">
                <tr>
                  <th className="px-4 py-3 first:pl-6">{tTable('developer')}</th>
                  {GROUP_UI_ENABLED && <th className="px-4 py-3 first:pl-6">{tTable('group')}</th>}
                  <th className="px-4 py-3 first:pl-6">{tTable('commits')}</th>
                  <th className="px-4 py-3 first:pl-6">{tTable('merges')}</th>
                  <th className="px-4 py-3 first:pl-6">{tTable('issues')}</th>
                  <th className="px-4 py-3 first:pl-6">{tTable('bugs')}</th>
                  <th className="px-4 py-3 first:pl-6">{tInsights('avgLeadTime')}</th>
                  <th className="px-4 py-3 first:pl-6">{tTable('score')}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-200/60">
                {isDeveloperSectionLoading ? (
                  <LoadingTableRow colSpan={GROUP_UI_ENABLED ? 8 : 7} label={tTable('loading')} />
                ) : sortedDeveloperStats.map((stat, index) => (
                    <tr key={stat.member.userCode} onClick={() => handleDeveloperSelect(stat)} className="hover:bg-white/80 transition cursor-pointer group">
                      <td className="px-4 py-3.5 pl-6">
                        <div className="flex items-center gap-3">
                          <span className={`w-6 font-mono text-xs font-bold tabular ${index === 0 ? 'text-aurora' : 'text-slate-400'}`}>{String(index + 1).padStart(2, '0')}</span>
                          <span className="bg-aurora flex h-9 w-9 shrink-0 items-center justify-center rounded-full p-[2px]">
                            <span className="flex h-full w-full items-center justify-center rounded-full bg-white text-xs font-bold text-violet-600">{(stat.member.userName || stat.member.userCode || '?').trim().charAt(0)}</span>
                          </span>
                          <span className="min-w-0">
                            <span className="block whitespace-nowrap font-semibold text-slate-900 transition-colors group-hover:text-blue-600">{stat.member.userName || stat.member.userCode}</span>
                            <span className="block font-mono text-[10.5px] tracking-wider text-slate-400">{stat.member.userCode}</span>
                          </span>
                        </div>
                      </td>
                      {GROUP_UI_ENABLED && <td className="px-4 py-4 text-slate-500">{stat.member.groupName || ''}</td>}
                      <td className="px-4 py-4 font-mono tabular text-slate-800"><div className="flex flex-wrap items-center justify-between gap-1">{Math.round(stat.commitCount || 0)}<TrendBadge trend={stat.trends?.commitCount} /></div></td>
                      <td className="px-4 py-4 font-mono tabular text-slate-800"><div className="flex flex-wrap items-center justify-between gap-1">{Math.round(stat.mergedCount || 0)}<TrendBadge trend={stat.trends?.mergedCount} /></div></td>
                      <td className="px-4 py-4 font-mono tabular text-slate-800"><div className="flex flex-wrap items-center justify-between gap-1">{Math.round(stat.issueCreatedCount || 0)}<TrendBadge trend={stat.trends?.issueCreatedCount} /></div></td>
                      <td className="px-4 py-4 font-mono tabular text-slate-800"><div className="flex flex-wrap items-center justify-between gap-1">{Math.round(stat.bugCausedCount || 0)}<TrendBadge trend={stat.trends?.bugCausedCount} /></div></td>
                      <td className="px-6 py-4"><div className="flex flex-wrap items-center gap-1">{stat.mergedCount ? (stat.mergedLeadTimeHours ?? 0).toFixed(1) + 'h' : '—'}<TrendBadge trend={stat.trends?.mergedLeadTimeHours} /></div></td>
                      <td className="px-6 py-4">
                        <span className="inline-flex items-center gap-2 font-mono font-bold text-slate-900 tabular">
                          {(stat.totalScore || 0).toFixed(1)}
                          <span className="h-1.5 w-16 overflow-hidden rounded-full bg-slate-500/15">
                            <span className="bg-aurora block h-full rounded-full shadow-[0_0_8px_rgb(79_123_255/0.6)]" style={{ width: `${Math.min(Math.max(stat.totalScore || 0, 0), 100)}%` }} />
                          </span>
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
