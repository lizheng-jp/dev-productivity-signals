'use client';
import { useTranslations } from 'next-intl';
import { SPACE_DIMENSION_COUNT, scoredDimensionCount } from '@/lib/space-coverage';
import { cn } from './Common';

/** Shown next to a SPACE total only when some dimensions had no data. */
export function DimensionCoverage({ metrics, className }: { metrics?: Record<string, unknown> | null; className?: string }) {
  const t = useTranslations('Space');
  const count = scoredDimensionCount(metrics);
  if (!metrics || count === 0 || count >= SPACE_DIMENSION_COUNT) return null;
  return (
    <span className={cn('inline-flex whitespace-nowrap rounded-full bg-amber-500/10 px-2 py-0.5 text-[10.5px] font-semibold text-amber-700', className)}>
      {t('dimensionCoverage', { count, total: SPACE_DIMENSION_COUNT })}
    </span>
  );
}
