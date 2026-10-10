import { useTranslations } from 'next-intl';
import { rateLimitRetryMinutes } from '@/lib/api/client';

/**
 * Turns a metrics load error into a message for people. GitHub rate limits get their own
 * message with the retry time, because "check your settings" would send them the wrong way.
 */
export function useLoadErrorMessage() {
  const t = useTranslations('ComparisonTrend');
  return (error: unknown) => {
    const minutes = rateLimitRetryMinutes(error);
    if (minutes === null) return t('loadError');
    return minutes > 0 ? t('rateLimitError', { minutes }) : t('rateLimitErrorSoon');
  };
}
