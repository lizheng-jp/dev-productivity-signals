import { useEffect, useState } from 'react';
import type { DateRange } from 'react-day-picker';
import { useMainLayout } from '@/contexts/MainLayoutContext';
import { formatLocalDate } from '@/lib/date-format';
import { getMetricComparison, type MetricComparison } from '@/lib/api/metric-comparison';
export function useMetricComparison(projectId: string | null, date: DateRange | undefined, refName?: string) {
  const { aiEnabled, comparisonDays } = useMainLayout();
  const since = formatLocalDate(date?.from), until = formatLocalDate(date?.to);
  const key = projectId && since && until ? [projectId, since, until, refName || '', comparisonDays].join('|') : '';
  const [raw, setRaw] = useState<{ key: string; data: MetricComparison | null; error: string | null } | null>(null);
  const [corrected, setCorrected] = useState<{ key: string; data: MetricComparison | null } | null>(null);
  useEffect(() => {
    let ignore = false;
    if (!projectId || !since || !until) return;
    getMetricComparison(projectId, { since, until, refName, previousDays: comparisonDays, aiEnabled: false })
      .then(data => { if (!ignore) setRaw({ key, data, error: null }); })
      .catch(error => { if (!ignore) setRaw({ key, data: null, error: error instanceof Error ? error.message : String(error) }); });
    return () => { ignore = true; };
  }, [key, projectId, since, until, refName, comparisonDays]);
  const rawData = key && raw?.key === key ? raw.data : null;
  const aiKey = key + '|' + (rawData?.comparisonId || '');
  useEffect(() => {
    let ignore = false;
    if (!aiEnabled || !rawData || !projectId || !since || !until) return;
    getMetricComparison(projectId, { since, until, refName, previousDays: comparisonDays, aiEnabled: true, comparisonId: rawData.comparisonId })
      .then(data => { if (!ignore) setCorrected({ key: aiKey, data }); })
      .catch(() => { if (!ignore) setCorrected({ key: aiKey, data: null }); });
    return () => { ignore = true; };
  }, [aiEnabled, rawData, aiKey, projectId, since, until, refName, comparisonDays]);
  return {
    comparison: aiEnabled && corrected?.key === aiKey && corrected.data ? corrected.data : rawData,
    isLoading: Boolean(key && raw?.key !== key),
    isAiCorrecting: Boolean(aiEnabled && rawData && corrected?.key !== aiKey),
    error: key && raw?.key === key ? raw.error : null,
  };
}
