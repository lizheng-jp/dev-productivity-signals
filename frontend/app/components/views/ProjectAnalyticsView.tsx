// components/views/ProjectAnalyticsView.tsx
"use client";
import { TrendBadge } from '@/components/ui/TrendBadge';
import type { MetricTrend } from '@/lib/api/metric-comparison';
import React, { useState, useMemo } from 'react';
import { DateRange } from 'react-day-picker';
import { Card, cn, Badge } from '@/components/ui/Common';
import { Radar, RadarChart, PolarGrid, PolarAngleAxis, PolarRadiusAxis, ResponsiveContainer } from 'recharts';
import { Info, CircleHelp, ChevronDown, Search, FolderKanban, Check, Loader2 } from 'lucide-react';
import { ProjectStat } from '@/types/project';
import { Project } from '@/lib/api/projects';
import { useProjectStats } from '@/lib/hooks/useProjectStats';
import { DatePickerWithRange } from '@/components/ui/date-range-picker';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { useTranslations } from 'next-intl';
import { spaceGroupsConfig } from '@/lib/space-config';
import { formatAiEvaluationText } from '@/lib/ai-evaluation-display';

// Types for transformed project detail data
export type ProjectMetric = {
  comparisonTrend?: MetricTrend;
  label: string;
  value: string | number;
  unit?: string;
  trend?: 'up' | 'down' | 'neutral';
  status: 'good' | 'warning' | 'bad';
  isMock?: boolean;
  chartData?: unknown;
};

export type ProjectDimension = {
  comparisonTrend?: MetricTrend;
  name: string;
  score: number;
  description: string;
  metrics: ProjectMetric[];
};

export type ProjectDetail = {
  id: string;
  name: string;
  totalScore: number;
  dimensions: ProjectDimension[];
};

const AiCorrectionReasonPopover = ({ reason }: { reason: string }) => {
  const displayReason = formatAiEvaluationText(reason);

  return (
    <Popover>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label={displayReason}
          title={displayReason}
          className="inline-flex h-5 w-5 items-center justify-center rounded-full text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2"
        >
          <CircleHelp className="h-3.5 w-3.5" aria-hidden="true" />
        </button>
      </PopoverTrigger>
      <PopoverContent align="center" className="w-72 border-slate-200 bg-white p-3 text-xs leading-5 text-slate-600">
        {displayReason}
      </PopoverContent>
    </Popover>
  );
};

const transformProjectStatToDetail = (projectStat: ProjectStat,tSpace: (key: string) => string,tTable: (key: string) => string): ProjectDetail => ({
  id: projectStat.projectId, name: projectStat.projectName,totalScore: projectStat.totalScore,
  dimensions: spaceGroupsConfig.map(group => ({
    name: tSpace(group.nameKey),description: tSpace(group.descriptionKey),score: Number(projectStat[group.scoreKey as keyof ProjectStat] ?? 0),comparisonTrend:projectStat.trends?.[group.scoreKey],
    metrics: group.metrics.filter(metric => projectStat.spaceMetrics?.[metric.key] !== undefined).map(metric => {
      const value = projectStat.spaceMetrics?.[metric.key];
      const trend = projectStat.trends?.[metric.key];
      const decimals = ['contextSwitchFrequency', 'reviewCommentCount'].includes(metric.key) ? 2 : metric.unit ? 1 : 0;
      return { label:tTable(metric.labelKey),value:typeof value === 'number' && (!trend || trend.current !== null) ? value.toFixed(decimals) : '—',unit:metric.unit,status:'good' as const,isMock:false,comparisonTrend:trend };
    }),
  })),
});

type Props = {
  projects: Project[];
  projectStats: ProjectStat | null;
  isLoading: boolean;
  isAiCorrecting?: boolean;
  currentDate: DateRange | undefined;
};

export const ProjectAnalyticsView = ({ projects, projectStats, isLoading, isAiCorrecting = false, currentDate }: Props) => {
  const t = useTranslations('ProjectAnalytics');
  const tHeader = useTranslations('Header');
  const tSpace = useTranslations('Space');
  const tTable = useTranslations('Table');

  // Comparison state
  const [compareProjectId, setCompareProjectId] = useState<string | null>(null);
  const [compareDate, setCompareDate] = useState<DateRange | undefined>(currentDate);
  const [isProjectPopoverOpen, setIsProjectPopoverOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');

  const { projectStats: compareProjectStats, isLoading: isCompareLoading } = useProjectStats(
    compareProjectId,
    projects,
    compareDate
  );

  const data: ProjectDetail | null = projectStats ? transformProjectStatToDetail(projectStats, tSpace, tTable) : null;
  const compareData: ProjectDetail | null = compareProjectStats ? transformProjectStatToDetail(compareProjectStats, tSpace, tTable) : null;

  const sortedProjects = useMemo(() => {
    return [...projects].sort((a, b) => a.name.localeCompare(b.name));
  }, [projects]);

  const filteredProjects = useMemo(() => {
    if (!searchQuery) return sortedProjects;
    const query = searchQuery.toLowerCase();
    return sortedProjects.filter(p =>
      p.name.toLowerCase().includes(query) ||
      p.description?.toLowerCase().includes(query)
    );
  }, [sortedProjects, searchQuery]);

  const selectedCompareProject = useMemo(() => {
    return projects.find(p => p.id.toString() === compareProjectId);
  }, [projects, compareProjectId]);

  const radarData = useMemo(() => {
    if (!data) return [];
    return data.dimensions.map((dim, idx) => ({
      subject: dim.name,
      A: dim.score,
      B: compareData?.dimensions[idx]?.score || 0
    }));
  }, [data, compareData]);

  if (isLoading) return <p className="py-12 text-center text-slate-500">Loading project analytics...</p>;
  if (!projectStats || !data) return <p className="py-12 text-center text-slate-500">No project data available.</p>;

  return (
    <div className="space-y-8">
      {/* Header Info */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-xl font-bold text-slate-800">{data.name}</h2>
        <div className="flex max-w-2xl flex-wrap items-center justify-end gap-2 bg-white px-4 py-2 rounded-lg border border-slate-200 shadow-sm">
          <div className="text-xs text-slate-500 font-semibold uppercase">Overall SPACE Score</div>
          <div className="flex items-center gap-1 text-2xl font-bold text-blue-600">
            <span>{data.totalScore.toFixed(1)}</span><TrendBadge trend={projectStats.trends?.spaceTotalScore} />
            {projectStats.aiCorrectionReason && (
              <AiCorrectionReasonPopover reason={projectStats.aiCorrectionReason} />
            )}
          </div>
          {isAiCorrecting && (
            <div className="inline-flex items-center gap-1 rounded-full bg-blue-50 px-2 py-1 text-[11px] font-bold text-blue-600">
              <Loader2 className="h-3 w-3 animate-spin" />
              AI補正中
            </div>
          )}
        </div>
      </div>

      {/* 5 Dimensions in one row */}
      <div className="grid grid-cols-1 lg:grid-cols-5 gap-4">
        {data.dimensions.map((dim) => (
          <ProjectDimensionCardCompact key={dim.name} dimension={dim} />
        ))}
      </div>

      {/* Comparison Section */}
      <Card className="p-8">
        <h3 className="text-lg font-bold text-slate-800 mb-6 flex items-center gap-2">
          {t('comparisonTitle')}
        </h3>

        {/* Comparison Selectors */}
        <div className="flex flex-wrap items-center gap-6 mb-10 pb-6 border-b border-slate-100">
          <div className="space-y-1.5">
            <label className="text-xs font-semibold text-slate-500 uppercase ml-1">{t('selectProject')}</label>
            <Popover open={isProjectPopoverOpen} onOpenChange={setIsProjectPopoverOpen}>
              <PopoverTrigger asChild>
                <button className="flex items-center justify-between gap-3 text-sm font-semibold text-slate-800 bg-slate-50 hover:bg-slate-100 border border-slate-200 px-4 py-2.5 rounded-lg transition-all w-72 shadow-sm">
                  <div className="flex items-center gap-2 truncate">
                    <FolderKanban className="w-4 h-4 text-blue-500" />
                    <span className="truncate">{selectedCompareProject?.name || tHeader('noProjects')}</span>
                  </div>
                  <ChevronDown className={cn("w-4 h-4 text-slate-400 transition-transform", isProjectPopoverOpen && "rotate-180")} />
                </button>
              </PopoverTrigger>
              <PopoverContent className="p-0 w-80 bg-white" align="start">
                <div className="p-2 border-b border-slate-100 bg-slate-50">
                  <div className="relative">
                    <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-slate-400" />
                    <input
                      type="text"
                      placeholder="Search projects..."
                      className="w-full bg-white border border-slate-200 rounded-md py-2 pl-9 pr-3 text-sm outline-none focus:ring-2 focus:ring-blue-500/20 focus:border-blue-500"
                      value={searchQuery}
                      onChange={(e) => setSearchQuery(e.target.value)}
                    />
                  </div>
                </div>
                <div className="max-h-60 overflow-y-auto p-1">
                  {filteredProjects.map((project) => (
                    <button
                      key={project.id}
                      onClick={() => {
                        setCompareProjectId(project.id.toString());
                        setIsProjectPopoverOpen(false);
                      }}
                      className={cn(
                        "w-full text-left px-3 py-2 rounded-md transition-colors flex items-center justify-between group",
                        compareProjectId === project.id.toString() ? "bg-blue-50" : "hover:bg-slate-50"
                      )}
                    >
                      <span className={cn("text-sm", compareProjectId === project.id.toString() ? "text-blue-700 font-medium" : "text-slate-700")}>
                        {project.name}
                      </span>
                      {compareProjectId === project.id.toString() && <Check className="w-4 h-4 text-blue-600" />}
                    </button>
                  ))}
                </div>
              </PopoverContent>
            </Popover>
          </div>

          <div className="space-y-1.5">
            <label className="text-xs font-semibold text-slate-500 uppercase ml-1">{t('selectDate')}</label>
            <DatePickerWithRange date={compareDate} setDate={setCompareDate} />
          </div>
        </div>

        {compareData ? (
          <div className="grid grid-cols-1 xl:grid-cols-2 gap-12">
            {/* Radar Comparison */}
            <div className="space-y-4">
              <h4 className="text-sm font-bold text-slate-400 uppercase tracking-wider text-center">{t('radarTitle')}</h4>
              <div className="h-[350px] w-full">
                <ResponsiveContainer width="100%" height="100%">
                  <RadarChart cx="50%" cy="50%" outerRadius="80%" data={radarData}>
                    <PolarGrid stroke="#e2e8f0" />
                    <PolarAngleAxis dataKey="subject" stroke="#64748b" fontSize={11} />
                    <PolarRadiusAxis angle={30} domain={[0, 100]} tick={false} axisLine={false} />
                    <Radar
                      name={data.name}
                      dataKey="A"
                      stroke="#3b82f6"
                      fill="#3b82f6"
                      fillOpacity={0.5}
                    />
                    <Radar
                      name={compareData.name}
                      dataKey="B"
                      stroke="#10b981"
                      fill="#10b981"
                      fillOpacity={0.5}
                    />
                  </RadarChart>
                </ResponsiveContainer>
              </div>
              <div className="flex justify-center gap-6">
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-blue-500" />
                  <span className="text-xs font-medium text-slate-600">{data.name}</span>
                </div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-emerald-500" />
                  <span className="text-xs font-medium text-slate-600">{compareData.name}</span>
                </div>
              </div>
            </div>

            {/* Metrics Comparison */}
            <div className="space-y-6">
              <h4 className="text-sm font-bold text-slate-400 uppercase tracking-wider">{t('metricsTitle')}</h4>
              <div className="space-y-4">
                <MetricCompareRow
                  label="Commits"
                  valA={projectStats.commitCount}
                  valB={compareProjectStats?.commitCount || 0}
                />
                <MetricCompareRow
                  label="Merges"
                  valA={projectStats.mergedCount}
                  valB={compareProjectStats?.mergedCount || 0}
                />
                <MetricCompareRow
                  label="Issues"
                  valA={projectStats.issuesCreatedCount}
                  valB={compareProjectStats?.issuesCreatedCount || 0}
                />
                <MetricCompareRow
                  label="Bugs"
                  valA={projectStats.bugFoundCount}
                  valB={compareProjectStats?.bugFoundCount || 0}
                />
              </div>
            </div>
          </div>
        ) : (
          <div className="py-20 text-center text-slate-400 bg-slate-50 rounded-xl border-2 border-dashed border-slate-200">
            <FolderKanban className="w-12 h-12 mx-auto mb-4 opacity-20" />
            <p>{isCompareLoading ? 'Loading comparison data...' : 'Select a project to compare'}</p>
          </div>
        )}
      </Card>
    </div>
  );
};

// --- Sub Components ---

const ProjectDimensionCardCompact = ({ dimension }: { dimension: ProjectDimension }) => {
  return (
    <div className="bg-white border border-slate-200 rounded-xl overflow-hidden shadow-sm flex flex-col h-full">
      <div className="bg-slate-50/80 px-4 py-3 border-b border-slate-100 flex justify-between items-center">
        <div className="flex min-w-0 items-center gap-1.5">
          <span className="truncate text-xs font-bold uppercase tracking-tight text-slate-600">{dimension.name}</span>
          <div className="group relative shrink-0">
            <Info className="h-3.5 w-3.5 cursor-help text-slate-400" aria-label={dimension.description} />
            <div className="absolute left-0 top-5 z-20 hidden w-64 rounded-md bg-slate-800 p-3 text-[11px] font-medium normal-case leading-5 text-white shadow-lg group-hover:block">
              {dimension.description}
            </div>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-1"><TrendBadge trend={dimension.comparisonTrend} /><Badge color={dimension.score > 80 ? 'green' : dimension.score > 60 ? 'yellow' : 'red'}>
          {dimension.score}
        </Badge></div>
      </div>
      <div className="p-4 flex-1 flex flex-col justify-center">
        {dimension.metrics.map((metric, idx) => (
          <div key={idx} className={cn("flex flex-col", idx > 0 && "mt-3 pt-3 border-t border-slate-50")}>
            <div className="mb-1 flex items-start justify-between gap-1"><span className="text-[10px] text-slate-400 uppercase font-semibold">{metric.label}</span><TrendBadge trend={metric.comparisonTrend} /></div>
            <div className="flex items-baseline gap-1">
              <span className="text-lg font-bold text-slate-800 leading-none">{metric.value}</span>
              {metric.unit && <span className="text-[10px] text-slate-400 font-medium">{metric.unit}</span>}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};

const MetricCompareRow = ({ label, valA, valB }: { label: string, valA: number, valB: number }) => {
  const percentA = (valA / (valA + valB || 1)) * 100;

  return (
    <div className="space-y-2">
      <div className="flex justify-between items-end text-sm">
        <span className="font-semibold text-slate-700">{label}</span>
        <div className="flex items-center gap-3">
          <span className="font-bold text-blue-600">{valA}</span>
          <span className="text-slate-300 text-xs font-bold">VS</span>
          <span className="font-bold text-emerald-600">{valB}</span>
        </div>
      </div>
      <div className="h-2 w-full bg-slate-100 rounded-full flex overflow-hidden">
        <div className="h-full bg-blue-500 transition-all duration-500" style={{ width: `${percentA}%` }} />
        <div className="h-full bg-emerald-500 transition-all duration-500 flex-1" />
      </div>
    </div>
  );
};
