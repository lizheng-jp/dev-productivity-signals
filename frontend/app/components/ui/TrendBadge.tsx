'use client';
import { TrendingUp, TrendingDown, Minus } from 'lucide-react';
import { useTranslations } from 'next-intl';
import type { MetricTrend } from '@/lib/api/metric-comparison';
export function TrendBadge({ trend }: { trend?: MetricTrend }) {
  const t = useTranslations('ComparisonTrend');
  if (!trend || trend.status !== 'comparable' || trend.percentChange === null
    || trend.current === null || trend.previous === null
    || !Number.isFinite(trend.percentChange) || !Number.isFinite(trend.current) || !Number.isFinite(trend.previous)
    || (trend.current === 0 && trend.previous === 0)) return null;
  const percent = trend.percentChange;
  const flat = Math.abs(percent) < 0.05;
  const up = percent > 0;
  const good = trend.lowerIsBetter ? !up : up;
  const Icon = flat ? Minus : up ? TrendingUp : TrendingDown;
  return <span title={t('detail', { current: trend.current ?? '—', previous: trend.previous ?? '—' }) + (trend.dailyNormalized ? ' · ' + t('dailyNormalized') : '')}
    className={`inline-flex shrink-0 items-center gap-1 whitespace-nowrap rounded px-1.5 py-0.5 text-[10px] font-semibold ${flat ? 'bg-slate-100 text-slate-500' : good ? 'bg-emerald-50 text-emerald-700' : 'bg-rose-50 text-rose-700'}`}>
    <Icon className="h-3 w-3" aria-hidden="true" />
    {flat ? '0.0%' : (up ? '+' : '') + percent.toFixed(1) + '%'}
    {trend.dailyNormalized && <span>{t('daily')}</span>}
  </span>;
}
