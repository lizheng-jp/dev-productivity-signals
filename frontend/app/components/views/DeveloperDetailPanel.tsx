// components/views/DeveloperDetailPanel.tsx
"use client";
import { TrendBadge } from '@/components/ui/TrendBadge';
import type { MetricTrend } from '@/lib/api/metric-comparison';
import { X, ExternalLink, CircleHelp } from 'lucide-react';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { SpaceRadarChart } from '@/components/ui/Charts';
import { Badge, cn } from '@/components/ui/Common';
import type { MetricWeight } from '@/lib/api/metric-weights';
import { DeveloperStat } from '@/types/developer';
import { spaceGroupsConfig } from '@/lib/space-config';
import { useTranslations } from 'next-intl';
import { formatAiEvaluationText } from '@/lib/ai-evaluation-display';
import { GROUP_UI_ENABLED } from '@/lib/feature-flags';

// Types for transformed developer detail data
export type DeveloperMetric = {
  trend?: MetricTrend;
  label: string;
  value: string | number;
  unit?: string;
  isMock?: boolean; // Added to indicate if the data is mocked
  chartData?: { day: string; commits: number }[]; // For commit activity
};

export type DeveloperDimension = {
  trend?: MetricTrend;
  name: string; // e.g., "Activity"
  score: number; // e.g., 92
  description: string;
  metrics: DeveloperMetric[];
};

export type DeveloperDetail = {
  id: string;
  name: string;
  role: string;
  team: string;
  avatar: string;
  totalScore: number;
  dimensions: DeveloperDimension[];
};

// Function to transform DeveloperStat to DeveloperDetail
const transformDeveloperStatToDetail = (
  developerStat: DeveloperStat,
  metricConfigs: MetricWeight[],
  tSpace: (key: string) => string,
  tTable: (key: string) => string,
  tDetail: (key: string) => string
): DeveloperDetail => {
  const transformedDimensions: DeveloperDimension[] = spaceGroupsConfig.map(groupConfig => {
    const rawScore = developerStat[groupConfig.scoreKey as keyof DeveloperStat];
    const dimensionScore = typeof rawScore === 'number' ? rawScore : 0;

    const activeMetrics = new Set(
      metricConfigs
        .filter(m => m.active && m.parentKey === groupConfig.nameKey)
        .map(m => m.metricKey)
    );

    const metrics: DeveloperMetric[] = groupConfig.metrics
      .filter(metricConfig => activeMetrics.has(metricConfig.key))
      .map(metricConfig => {
        const rawValue = developerStat.spaceMetrics ? developerStat.spaceMetrics[metricConfig.key] : developerStat[metricConfig.key as keyof DeveloperStat];
        const comparisonTrend = developerStat.trends?.[metricConfig.key];
        const isMock = rawValue === undefined || rawValue === null || comparisonTrend?.current === null;

        let formattedValue: string | number;
        if (isMock) {
          formattedValue = '-';
        } else if (typeof rawValue === 'number') {
          let decimals = metricConfig.unit === 'd' || metricConfig.unit === 'h' ? 1 : 0;
          // 密度指標とマージリードタイムは小数点以下2桁で表示
          if (metricConfig.key === 'reviewCommentCount'
            || metricConfig.key === 'mergedLeadTimeHours'
            || metricConfig.key === 'contextSwitchFrequency') {
            decimals = 2;
          }
          formattedValue = rawValue.toFixed(decimals);
        } else {
          formattedValue = typeof rawValue === 'string' ? rawValue : '-';
        }

        return {
          trend: developerStat.trends?.[metricConfig.key],
          label: tTable(metricConfig.labelKey),
          value: formattedValue,
          unit: metricConfig.unit,
          isMock: isMock,
        };
      });

    return {
      trend: developerStat.trends?.[groupConfig.scoreKey],
      name: tSpace(groupConfig.nameKey),
      description: tSpace(groupConfig.descriptionKey),
      score: Math.round(dimensionScore),
      metrics: metrics,
    };
  });

  return {
    id: developerStat.member.userCode,
    name: developerStat.member.userName || developerStat.member.userCode,
    role: tDetail('unknownRole'),
    team: developerStat.member.groupName || tTable('noGroup'),
    avatar: developerStat.member.userCode.charAt(0).toUpperCase() || '—',
    totalScore: developerStat.totalScore || 0,
    dimensions: transformedDimensions,
  };
};

type Props = {
  isOpen: boolean;
  onClose: () => void;
  developer: DeveloperStat | null;
  metricConfigs: MetricWeight[];
};

export const DeveloperDetailPanel = ({ isOpen, onClose, developer, metricConfigs }: Props) => {
  const tSpace = useTranslations('Space');
  const tTable = useTranslations('Table');
  const t = useTranslations('DeveloperDetail');

  if (!isOpen || !developer) return null;

  // Pass translation functions to the transformer
  const data: DeveloperDetail = transformDeveloperStatToDetail(developer, metricConfigs, tSpace, tTable, t);

  // Helper to determine if a value is mocked (undefined or null)
  const isMocked = (value: DeveloperMetric['value']) => value === undefined || value === null || value === 'N/A';

  // SPACE Score is not directly available in DeveloperStat, so it's mocked or calculated from dimensions
  // For now, using the totalScore from the transformed data
  const spaceScore = data.totalScore;
  const aiCorrectionReason = developer.spaceMetrics?.aiCorrection?.reason;
  const displayAiCorrectionReason = aiCorrectionReason
    ? formatAiEvaluationText(aiCorrectionReason)
    : null;

  return (
    <>
      {/* */}
      <div
        className="fixed inset-0 bg-black/20 backdrop-blur-[1px] z-40 transition-opacity"
        onClick={onClose}
      />

      {/* Slide-over Panel */}
      <div className="fixed inset-y-0 right-0 w-[600px] bg-white shadow-2xl z-50 transform transition-transform duration-300 ease-in-out flex flex-col">

        {/* 1. Header Area */}
        <div className="flex items-center justify-between px-6 py-5 border-b border-slate-200 bg-slate-50">
          <div className="flex items-center gap-4">
            <div className="w-14 h-14 rounded-full bg-blue-600 text-white flex items-center justify-center text-xl font-bold border-4 border-white shadow-sm">
              {data.avatar}
            </div>
            <div>
              <h2 className="text-xl font-bold text-slate-800">{data.name}({data.id})</h2>
              <div className="flex items-center gap-2 text-sm text-slate-500">
                <span>{data.role}</span>
                {GROUP_UI_ENABLED && (
                  <>
                    <span>•</span>
                    <span>{data.team}</span>
                  </>
                )}
              </div>
            </div>
          </div>
          <div className="flex items-center gap-3">
            <div className="text-right">
              <div className="text-xs text-slate-500 font-semibold uppercase mb-1">{t('spaceScore')}</div>
              <div className="flex items-center justify-end gap-1">
                <TrendBadge trend={developer?.trends?.spaceTotalScore} />
                <Badge
                  color={spaceScore > 80 ? 'blue' : spaceScore > 60 ? 'green' : 'yellow'}
                >
                  {isMocked(spaceScore) ? '—' : spaceScore.toFixed(1)}
                </Badge>
                {displayAiCorrectionReason && (
                  <button
                    type="button"
                    aria-label={displayAiCorrectionReason}
                    title={displayAiCorrectionReason}
                    className="inline-flex h-5 w-5 items-center justify-center rounded-full text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2"
                  >
                    <CircleHelp className="h-3.5 w-3.5" aria-hidden="true" />
                  </button>
                )}
              </div>
            </div>
            <button aria-label={t('close')} onClick={onClose} className="p-2 hover:bg-slate-200 rounded-full text-slate-500 transition">
              <X className="w-5 h-5" />
            </button>
          </div>
        </div>

        {/* 2. Scrollable Body */}
        <div className="flex-1 overflow-y-auto p-6 bg-white">

          {/* AI Evaluations Section
          {developer.aiEvaluations && developer.aiEvaluations.length > 0 && (
            <div className="mb-8">
              <div className="flex items-center gap-2 mb-4">
                <Brain className="w-5 h-5 text-purple-600" />
                <h3 className="text-sm font-bold text-slate-400 uppercase tracking-wider">AI Evaluation Results</h3>
              </div>
              <div className="space-y-4">
                {developer.aiEvaluations.map((evalItem: any, idx: number) => (
                  <div key={idx} className="bg-purple-50 border border-purple-100 rounded-xl p-4 shadow-sm">
                    <div className="flex justify-between items-start mb-2">
                      <div className="flex items-center gap-2">
                        <Badge color="blue">MR #{evalItem.mrIid}</Badge>
                        <span className="text-xs font-bold text-slate-700">{evalItem.changeType}</span>
                      </div>
                      <div className="flex gap-2">
                        <Badge color={evalItem.complexity === 'high' ? 'red' : evalItem.complexity === 'mid' ? 'yellow' : 'green'}>
                          Complexity: {evalItem.complexity}
                        </Badge>
                        <Badge color={evalItem.contribution === 'high' ? 'blue' : evalItem.contribution === 'mid' ? 'green' : 'gray'}>
                          Contribution: {evalItem.contribution}
                        </Badge>
                      </div>
                    </div>
                    <p className="text-sm text-slate-700 leading-relaxed italic">
                      "{evalItem.reasoning}"
                    </p>
                  </div>
                ))}
              </div>
            </div>
          )} */}

          {/* Section: Radar Overview */}
          <div className="mb-8">
            <h3 className="text-sm font-bold text-slate-400 uppercase tracking-wider mb-4">{t('metricProfile')}</h3>
            <div className="h-64 w-full bg-slate-50 rounded-xl border border-slate-100 p-4">
              <SpaceRadarChart chartData={data.dimensions.map(d => ({ subject: d.name, value: d.score }))} />
            </div>
          </div>

          {/* Section: Detailed 5-Dimension Breakdown */}
          <div className="space-y-6">
            <h3 className="text-sm font-bold text-slate-400 uppercase tracking-wider">{t('dimensionBreakdown')}</h3>

            {/* Grid for Dimensions */}
            <div className="grid grid-cols-1 gap-4">
              {data.dimensions.map((dim) => (
                <DimensionCard key={dim.name} dimension={dim} />
              ))}
            </div>
          </div>
        </div>

        {/* 3. Footer Actions */}
        <div className="p-4 border-t border-slate-200 bg-slate-50 flex justify-end gap-3">
          <button className="px-4 py-2 bg-white border border-slate-300 rounded-lg text-sm font-medium text-slate-700 hover:bg-slate-100 shadow-sm">
            {t('exportPdf')}
          </button>
          <button className="px-4 py-2 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 shadow-sm flex items-center gap-2">
            {t('compareDeveloper')} <ExternalLink className="w-4 h-4" />
          </button>
        </div>
      </div>
    </>
  );
};

// --- Sub Component: Dimension Card ---
const DimensionCard = ({ dimension }: { dimension: DeveloperDimension }) => {
  const t = useTranslations('DeveloperDetail');
  const tTable = useTranslations('Table');
  return (
    <div className="border border-slate-200 rounded-lg overflow-hidden shadow-sm">
      {/* Card Header */}
      <div className="bg-slate-50 px-4 py-3 border-b border-slate-100 flex justify-between items-center">
        <div className="flex items-center gap-2">
          <span className="font-bold text-slate-700">{dimension.name}</span>
          <Popover>
            <PopoverTrigger asChild>
              <button type="button" aria-label={`${dimension.name}: ${dimension.description}`} className="rounded p-1 text-slate-500 hover:text-blue-600 focus-visible:outline-2 focus-visible:outline-blue-600">
                <CircleHelp className="h-4 w-4" aria-hidden="true" />
              </button>
            </PopoverTrigger>
            <PopoverContent className="z-[100] max-w-[calc(100vw-2rem)] bg-white text-sm leading-6 text-slate-600" sideOffset={8}>
              <p>{dimension.description}</p>
            </PopoverContent>
          </Popover>
        </div>
        <div className="flex items-center gap-2"><TrendBadge trend={dimension.trend} /><Badge color={dimension.score > 80 ? 'green' : dimension.score > 60 ? 'yellow' : 'red'}>
          {tTable('score')}: {dimension.score}
        </Badge></div>
      </div>

      {/* Metrics Grid */}
      <div className="p-4 space-y-2">
        {dimension.metrics.map((metric, idx) => (
          <div key={idx} className="bg-white border border-slate-200 rounded-lg p-3 shadow-sm">
            <div className="mb-1.5 flex items-center justify-between gap-2"><span className="text-[10px] font-bold text-slate-500 uppercase">{metric.label}</span><TrendBadge trend={metric.trend} /></div>
            <div className="grid grid-cols-1">
              <div className="flex flex-col">
                <span className={cn("text-base font-bold", !metric.isMock ? "text-blue-600" : "text-slate-300")}>
                  {metric.value}
                  {metric.unit && !metric.isMock && (
                    <span className="text-[10px] ml-0.5 font-normal text-slate-400">{metric.unit === 'min' ? t('units.minutes') : metric.unit === 'h' ? t('units.hours') : metric.unit === 'd' ? t('units.days') : metric.unit === '/h' ? t('units.perHour') : metric.unit}</span>
                  )}
                </span>
              </div>
            </div>
          </div>
        ))}
        {dimension.metrics.length === 0 && (
          <div className="text-[10px] text-slate-400 italic text-center py-2">{t('noActiveMetrics')}</div>
        )}
      </div>
    </div>
  );
};
