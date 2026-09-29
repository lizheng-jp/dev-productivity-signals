'use client';

import { useTranslations } from 'next-intl';
import { CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { Card } from '@/components/ui/Common';
import type { DashboardTimePoint, MetricComparison } from '@/lib/api/metric-comparison';

type CountKey = 'commitCount' | 'mergedCount';
type ChartPoint = DashboardTimePoint & { commitPerDay: number | null; mergedPerDay: number | null };
const chartTick = { fontSize: 10, fill: '#64748b' };

function dailyRate(point: DashboardTimePoint, key: CountKey) {
  const value = point[key];
  const days = (Date.parse(point.until) - Date.parse(point.since)) / 86_400_000 + 1;
  return Number.isFinite(value) && days > 0 ? value / days : null;
}

function ObservationTooltip({ active, payload, leadTime = false, grouped = false, countKey = 'mergedCount' }: {
  active?: boolean;
  payload?: readonly { payload?: DashboardTimePoint }[];
  leadTime?: boolean;
  grouped?: boolean;
  countKey?: CountKey;
}) {
  const t = useTranslations('DashboardTrends');
  const point = payload?.[0]?.payload;
  if (!active || !point) return null;
  return <div className="rounded-lg border border-slate-200 bg-white p-3 text-xs shadow-lg">
    <p className="mb-2 font-semibold text-slate-700">{point.since === point.until ? point.since : point.since + ' ～ ' + point.until}</p>
    {leadTime ? <div className="space-y-1 text-slate-600">
      {point.averageLeadTimeHours !== null && <p>{t('average')}: {point.averageLeadTimeHours.toFixed(1)} h</p>}
      {point.medianLeadTimeHours !== null && <p>{t('median')}: {point.medianLeadTimeHours.toFixed(1)} h</p>}
      <p>{t('samples', { count: point.sampleCount })}</p>
    </div> : <div className="space-y-1 text-slate-600"><p>{t(countKey)}: {point[countKey]}</p>{grouped && <p>{t(countKey === 'commitCount' ? 'commitDailyRate' : 'dailyRate')}: {dailyRate(point, countKey)?.toFixed(2)}</p>}</div>}
  </div>;
}

function EmptyChart({ isLoading, message }: { isLoading: boolean; message: string }) {
  const t = useTranslations('DashboardTrends');
  return <div className="flex h-full items-center justify-center text-sm text-slate-400">
    {isLoading ? <div className="h-full w-full animate-pulse rounded bg-slate-50" aria-label={t('loading')} /> : message}
  </div>;
}

function CountTrendCard({ points, countKey, grouped, isLoading }: {
  points: ChartPoint[]; countKey: CountKey; grouped: boolean; isLoading: boolean;
}) {
  const t = useTranslations('DashboardTrends');
  const commits = countKey === 'commitCount';
  const hasData = points.some(point => Number.isFinite(point[countKey]));
  return <Card className="min-w-0 p-5">
    <h4 className="text-sm font-semibold text-slate-800">{t(commits ? 'commitTitle' : 'throughputTitle')}</h4>
    <p className="mt-1 min-h-10 text-xs leading-5 text-slate-500">{t(commits ? 'commitDescription' : 'throughputDescription')}</p>
    <div className="mt-3 h-72 min-w-0">
      {!isLoading && hasData ? <ResponsiveContainer width="100%" height="100%">
        <LineChart data={points} margin={{ top: 16, right: 8, bottom: 0, left: 0 }} accessibilityLayer>
          <CartesianGrid vertical={false} stroke="#e2e8f0" strokeDasharray="3 3" />
          <XAxis dataKey="since" tickFormatter={value => String(value).slice(5)} tick={chartTick} minTickGap={20} />
          <YAxis allowDecimals={grouped} width={grouped ? 48 : 36} tick={chartTick} unit={grouped ? '/d' : undefined} domain={[0, 'auto']} />
          <Tooltip content={<ObservationTooltip grouped={grouped} countKey={countKey} />} />
          <Line type="linear" dataKey={grouped ? commits ? 'commitPerDay' : 'mergedPerDay' : countKey} name={t(grouped ? commits ? 'commitDailyRate' : 'dailyRate' : countKey)} stroke={commits ? '#8b5cf6' : '#3b82f6'} strokeWidth={2} dot={{ r: 3 }} connectNulls={false} isAnimationActive={false} />
        </LineChart>
      </ResponsiveContainer> : <EmptyChart isLoading={isLoading} message={t('noData')} />}
    </div>
  </Card>;
}

export function DashboardTrendCharts({ comparison, isLoading }: { comparison: MetricComparison | null; isLoading: boolean }) {
  const t = useTranslations('DashboardTrends');
  const points = (comparison?.deliveryTrend ?? []).filter(point => point.period === 'current').map(point => ({ ...point, commitPerDay: dailyRate(point, 'commitCount'), mergedPerDay: dailyRate(point, 'mergedCount') }));
  const grouped = points.some(point => point.since !== point.until);
  const hasLeadTime = points.some(point => point.sampleCount > 0);

  return <section aria-label={t('title')} className="space-y-3">
    <h3 className="text-sm font-semibold text-slate-700">{t('title')}</h3>
    <div className="grid gap-5 lg:grid-cols-3">
      <CountTrendCard points={points} countKey="commitCount" grouped={grouped} isLoading={isLoading} />
      <CountTrendCard points={points} countKey="mergedCount" grouped={grouped} isLoading={isLoading} />
      <Card className="min-w-0 p-5">
        <h4 className="text-sm font-semibold text-slate-800">{t('leadTimeTitle')}</h4>
        <p className="mt-1 min-h-10 text-xs leading-5 text-slate-500">{t('leadTimeDescription')}</p>
        <div className="mt-3 h-72 min-w-0">
          {!isLoading && hasLeadTime ? <ResponsiveContainer width="100%" height="100%">
            <LineChart data={points} margin={{ top: 16, right: 8, bottom: 0, left: 0 }} accessibilityLayer>
              <CartesianGrid vertical={false} stroke="#e2e8f0" strokeDasharray="3 3" />
              <XAxis dataKey="since" tickFormatter={value => String(value).slice(5)} tick={chartTick} minTickGap={20} />
              <YAxis domain={[0, 'auto']} width={42} tick={chartTick} unit="h" />
              <Tooltip content={<ObservationTooltip leadTime />} /><Legend wrapperStyle={{ fontSize: 11, paddingTop: 8 }} />
              <Line type="linear" dataKey="averageLeadTimeHours" name={t('average')} stroke="#3b82f6" strokeWidth={2} dot={{ r: 4 }} connectNulls isAnimationActive={false} />
              <Line type="linear" dataKey="medianLeadTimeHours" name={t('median')} stroke="#10b981" strokeWidth={2} strokeDasharray="5 4" dot={{ r: 2 }} connectNulls isAnimationActive={false} />
            </LineChart>
          </ResponsiveContainer> : <EmptyChart isLoading={isLoading} message={t('noLeadTime')} />}
        </div>
      </Card>
    </div>
  </section>;
}
