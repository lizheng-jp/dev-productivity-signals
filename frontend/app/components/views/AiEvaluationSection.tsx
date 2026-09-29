"use client";

import { useEffect, useRef, useState } from 'react';
import { useTranslations } from 'next-intl';
import { getEvaluationFeedback, saveEvaluationFeedback, type EvaluationSection } from '@/lib/api/ai';
import { Card, cn } from '@/components/ui/Common';
import { AlertCircle, Brain, CheckCircle2, Lightbulb, Loader2, Target, ThumbsUp, ThumbsDown } from 'lucide-react';
import { AiAnalysisProgress, AiEvaluation } from '@/lib/api/ai';
import { formatAiEvaluationText } from '@/lib/ai-evaluation-display';

interface AiEvaluationSectionProps {
  evaluation: AiEvaluation | null;
  isLoading: boolean;
  entityName: string;
  selectionTitle?: string;
  selectionItems?: Array<{ label: string; value: string }>;
  error?: string | null;
  progress?: AiAnalysisProgress | null;
  actionLabel?: string;
  onAction?: () => void;
  isActionDisabled?: boolean;
}

const SectionList = ({
  title,
  items,
  tone,
  icon: Icon,
  section, votes, pending, onVote,
}: {
  title: string;
  items: string[];
  tone: 'emerald' | 'rose' | 'blue';
  icon: typeof CheckCircle2;
  section: EvaluationSection;
  votes: Record<string, boolean>;
  pending: Set<string>;
  onVote?: (section: EvaluationSection, index: number, helpful: boolean) => void;
}) => {
  const t = useTranslations('AiFeedback');
  const toneClasses = {
    emerald: 'text-emerald-600 bg-emerald-50 border-emerald-100',
    rose: 'text-rose-600 bg-rose-50 border-rose-100',
    blue: 'text-blue-600 bg-blue-50 border-blue-100',
  };

  return (
    <div className="border border-slate-200 rounded-lg bg-white p-4">
      <div className="flex items-center gap-2 mb-3">
        <div className={cn("w-8 h-8 rounded-md border flex items-center justify-center", toneClasses[tone])}>
          <Icon className="w-4 h-4" />
        </div>
        <h4 className="text-sm font-bold text-slate-700">{title}</h4>
      </div>
      <ul className="space-y-3">
        {items.map((item, index) => (
          <li key={index} className="flex gap-2 text-sm leading-relaxed text-slate-600">
            <span className={cn(
              "mt-2 w-1.5 h-1.5 rounded-full shrink-0",
              tone === 'emerald' ? 'bg-emerald-500' : tone === 'rose' ? 'bg-rose-500' : 'bg-blue-500'
            )} />
            <span className="min-w-0 flex-1">{formatAiEvaluationText(item)}</span>
            {onVote && <div className="ml-auto flex shrink-0 items-start gap-1">
              {[true, false].map(helpful => {
                const selected = votes[section + ':' + index] === helpful;
                const Icon = helpful ? ThumbsUp : ThumbsDown;
                return <button key={String(helpful)} type="button" aria-label={t(helpful ? 'helpful' : 'unhelpful')} title={t(helpful ? 'helpful' : 'unhelpful')}
                  aria-pressed={selected} disabled={pending.has(section + ':' + index)} onClick={() => onVote(section, index, helpful)}
                  className={`rounded p-1.5 transition disabled:opacity-40 ${selected ? helpful ? 'bg-emerald-100 text-emerald-700' : 'bg-rose-100 text-rose-700' : 'text-slate-400 hover:bg-slate-100 hover:text-slate-700'}`}><Icon className="h-4 w-4" /></button>;
              })}
            </div>}
          </li>
        ))}
      </ul>
    </div>
  );
};

export const AiEvaluationSection = ({
  evaluation,
  isLoading,
  entityName,
  selectionTitle,
  selectionItems,
  error,
  progress,
  actionLabel,
  onAction,
  isActionDisabled,
}: AiEvaluationSectionProps) => {
  const tFeedback = useTranslations('AiFeedback');
  const evaluationId = evaluation?.evaluationId;
  const latestEvaluationId = useRef(evaluationId);
  useEffect(() => { latestEvaluationId.current = evaluationId; }, [evaluationId]);
  const [feedback, setFeedback] = useState<{ id: string; votes: Record<string, boolean> }>({ id: '', votes: {} });
  const [pending, setPending] = useState<Set<string>>(new Set());
  const [feedbackError, setFeedbackError] = useState('');
  useEffect(() => {
    let ignore = false;
    if (!evaluationId) return;
    getEvaluationFeedback(evaluationId).then(items => {
      if (!ignore) setFeedback(old => ({ id: evaluationId, votes: { ...Object.fromEntries(items.map(item => [item.section + ':' + item.itemIndex, item.helpful])), ...(old.id === evaluationId ? old.votes : {}) } }));
    }).catch(() => { if (!ignore) setFeedbackError(tFeedback('loadError')); });
    return () => { ignore = true; };
  }, [evaluationId, tFeedback]);
  const onVote = evaluationId ? async (section: EvaluationSection, index: number, helpful: boolean) => {
    const key = section + ':' + index;
    if (pending.has(key)) return;
    setPending(old => new Set(old).add(key)); setFeedbackError('');
    try {
      await saveEvaluationFeedback(evaluationId, section, index, helpful);
      if (latestEvaluationId.current === evaluationId) setFeedback(old => ({ id: evaluationId, votes: { ...(old.id === evaluationId ? old.votes : {}), [key]: helpful } }));
    } catch { setFeedbackError(tFeedback('saveError')); }
    finally { setPending(old => { const next = new Set(old); next.delete(key); return next; }); }
  } : undefined;
  const votes = feedback.id === evaluationId ? feedback.votes : {};
  const progressPercent = progress && progress.total > 0
    ? Math.min(100, Math.round((Math.max(progress.processed, progress.current) / progress.total) * 100))
    : 0;

  return (
    <Card className="overflow-hidden bg-white border border-slate-200 shadow-sm">
      <div className="px-5 py-4 border-b border-slate-100 bg-slate-50/70">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-lg bg-blue-50 border border-blue-100 flex items-center justify-center text-blue-600">
            <Brain className="w-5 h-5" />
          </div>
          <div className="min-w-0 flex-1">
            <h3 className="text-sm font-bold text-slate-800">AI総合評価</h3>
            {selectionItems && selectionItems.length > 0 ? (
              <dl className="mt-2 space-y-0.5 text-[11px] leading-4" aria-label={selectionTitle}>
                {selectionItems.map(item => (
                  <div key={item.label} className="grid min-w-0 grid-cols-[4.5rem_minmax(0,1fr)] gap-2">
                    <dt className="text-slate-400">{item.label}</dt>
                    <dd className="break-words font-medium text-slate-600">{item.value}</dd>
                  </div>
                ))}
              </dl>
            ) : (
              <p className="text-xs text-slate-500">{entityName}</p>
            )}
          </div>
        </div>
      </div>

      <div className="p-5">
        {feedbackError && <p role="alert" className="mb-3 text-xs text-rose-600">{feedbackError}</p>}
        {isLoading && (
          <div className="py-12 text-slate-500">
            <div className="flex items-center justify-center gap-3">
              <Loader2 className="w-5 h-5 animate-spin text-blue-600" />
              <span className="text-sm font-medium">AI評価を生成中...</span>
            </div>

            {progress && progress.total > 0 && (
              <div className="mx-auto mt-6 max-w-sm">
                <div className="mb-2 flex items-center justify-between text-xs font-semibold text-slate-500">
                  <span>コードAI分析</span>
                  <span>{progress.current} / {progress.total}</span>
                </div>
                <div className="h-2 overflow-hidden rounded-full bg-slate-200">
                  <div
                    className="h-full rounded-full bg-blue-600 transition-all duration-300"
                    style={{ width: `${progressPercent}%` }}
                  />
                </div>
                <div className="mt-2 flex items-center justify-between text-[11px] text-slate-400">
                  <span>解析済み {progress.processed} / {progress.total}</span>
                  {progress.currentMrIid != null && <span>MR !{progress.currentMrIid}</span>}
                </div>
              </div>
            )}
          </div>
        )}

        {!isLoading && error && (
          <div className="flex items-start gap-3 rounded-lg border border-rose-100 bg-rose-50 p-4 text-rose-700">
            <AlertCircle className="w-5 h-5 shrink-0" />
            <div>
              <div className="text-sm font-bold">AI評価の取得に失敗しました</div>
              <div className="text-xs mt-1">{error}</div>
            </div>
          </div>
        )}

        {!isLoading && !error && !evaluation && (
          <div className="rounded-lg border border-dashed border-slate-200 bg-slate-50 px-4 py-10 text-center text-sm text-slate-500">
            <div>AI総合評価は必要な場合のみ生成します</div>
            {onAction && actionLabel && (
              <button
                type="button"
                onClick={onAction}
                disabled={isActionDisabled}
                className="mt-4 inline-flex items-center justify-center rounded-lg bg-blue-600 px-4 py-2 text-sm font-bold text-white transition hover:bg-blue-700 disabled:cursor-not-allowed disabled:bg-slate-300"
              >
                {actionLabel}
              </button>
            )}
          </div>
        )}

        {!isLoading && !error && evaluation && (
          <div className="grid grid-cols-1 gap-4">
            <SectionList section="strengths" votes={votes} pending={pending} onVote={onVote} title="強み" items={evaluation.strengths} tone="emerald" icon={CheckCircle2} />
            <SectionList section="weaknesses" votes={votes} pending={pending} onVote={onVote} title="弱み" items={evaluation.weaknesses} tone="rose" icon={Target} />
            <SectionList section="suggestions" votes={votes} pending={pending} onVote={onVote} title="改善提案" items={evaluation.suggestions} tone="blue" icon={Lightbulb} />
          </div>
        )}
      </div>
    </Card>
  );
};
