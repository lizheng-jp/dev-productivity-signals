"use client";

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { ClipboardList, MessageSquareText, Save, TrendingUp } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Card, cn } from '@/components/ui/Common';
import { useMainLayout } from '@/contexts/MainLayoutContext';
import { getProjectMembers, ProjectMember } from '@/lib/api/members';
import {
  createProjectSatisfactionSurvey,
  getProjectSatisfactionSummary,
  getProjectSatisfactionSurveys,
  ProjectSatisfactionSummary,
  ProjectSatisfactionSurvey,
  SatisfactionQuestionKey,
} from '@/lib/api/satisfaction-surveys';
import { getMergeRequestActivityPeriod } from '@/lib/api/merges';
import { formatLocalDate } from '@/lib/date-format';

const questionKeys: SatisfactionQuestionKey[] = [
  'q1WorkValue',
  'q2WorkMeaning',
  'q3TeamSatisfaction',
  'q4RecommendTeam',
  'q5InformationAccess',
  'q6EnvironmentSupport',
  'q7SupportFlow',
  'q8OutcomeConfidence',
  'q9SustainableWorkload',
  'q10Fatigue',
  'q11Detachment',
  'q12Pressure',
  'q13PsychologicalSafety',
  'q14ImprovementExpectation',
];

const reverseQuestionKeys = new Set<SatisfactionQuestionKey>([
  'q10Fatigue',
  'q11Detachment',
  'q12Pressure',
]);

const likertOptions = [
  { value: 1, labelKey: 'stronglyDisagree' },
  { value: 2, labelKey: 'disagree' },
  { value: 3, labelKey: 'neutral' },
  { value: 4, labelKey: 'agree' },
  { value: 5, labelKey: 'stronglyAgree' },
] as const;

const subScoreGroups = [
  {
    key: 's1JobSatisfaction',
    label: 'S1 仕事満足・意義',
  },
  {
    key: 's2DeveloperEfficacy',
    label: 'S2 開発者効力感',
  },
  {
    key: 's3Sustainability',
    label: 'S3 持続可能性',
  },
  {
    key: 's4ImprovementPotential',
    label: 'S4 改善可能性',
  },
] as const;

const todayInput = () => formatLocalDate(new Date()) || '';

const formatPeriod = (start?: string, end?: string) => {
  if (start && end) return `${start} - ${end}`;
  return start || end || '-';
};

const formatScore = (score?: number | null) => {
  if (score == null) return '-';
  return score.toFixed(1);
};

const getMemberName = (member?: ProjectMember) => member?.userName?.trim() || member?.userCode || '';

const ScoreButton = ({
  label,
  selected,
  onClick,
}: {
  label: string;
  selected: boolean;
  onClick: () => void;
}) => (
  <button
    type="button"
    onClick={onClick}
    className={cn(
      "min-h-11 rounded border px-2 py-2 text-center text-[11px] font-semibold leading-4 transition",
      selected
        ? "border-blue-600 bg-blue-600 text-white shadow-sm"
        : "border-slate-200 bg-white text-slate-600 hover:border-blue-300 hover:bg-blue-50 hover:text-blue-700"
    )}
  >
    {label}
  </button>
);

export const SatisfactionSurveyView = () => {
  const t = useTranslations('SatisfactionSurvey');
  const { projects, selectedProjectId } = useMainLayout();
  const selectedProject = projects.find(project => project.id.toString() === selectedProjectId);

  const [scores, setScores] = useState<Partial<Record<SatisfactionQuestionKey, number>>>({});
  const [members, setMembers] = useState<ProjectMember[]>([]);
  const [selectedUserCode, setSelectedUserCode] = useState('');
  const [comment, setComment] = useState('');
  const [periodStart, setPeriodStart] = useState(() => todayInput());
  const [periodEnd, setPeriodEnd] = useState(() => todayInput());
  const [summary, setSummary] = useState<ProjectSatisfactionSummary | null>(null);
  const [surveys, setSurveys] = useState<ProjectSatisfactionSurvey[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [isPeriodLoading, setIsPeriodLoading] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const periodParams = useMemo(() => ({
    since: periodStart,
    until: periodEnd,
  }), [periodStart, periodEnd]);

  const selectedMember = useMemo(
    () => members.find(member => member.userCode === selectedUserCode),
    [members, selectedUserCode]
  );

  const requestParams = useMemo(() => ({
    ...periodParams,
    userName: selectedUserCode || undefined,
  }), [periodParams, selectedUserCode]);

  const averagePreview = useMemo(() => {
    const hasAllScores = questionKeys.every(key => scores[key] != null);
    if (!hasAllScores) return null;

    const toScore = (key: SatisfactionQuestionKey) => {
      const value = scores[key] as number;
      return reverseQuestionKeys.has(key)
        ? ((5 - value) / 4) * 100
        : ((value - 1) / 4) * 100;
    };
    const average = (keys: SatisfactionQuestionKey[]) => keys.reduce((sum, key) => sum + toScore(key), 0) / keys.length;
    const s1 = average(['q1WorkValue', 'q2WorkMeaning', 'q3TeamSatisfaction', 'q4RecommendTeam']);
    const s2 = average(['q5InformationAccess', 'q6EnvironmentSupport', 'q7SupportFlow', 'q8OutcomeConfidence']);
    const s3 = average(['q9SustainableWorkload', 'q10Fatigue', 'q11Detachment', 'q12Pressure']);
    const s4 = average(['q13PsychologicalSafety', 'q14ImprovementExpectation']);
    return s1 * 0.25 + s2 * 0.30 + s3 * 0.30 + s4 * 0.15;
  }, [scores]);

  const isSurveyComplete = useMemo(
    () => questionKeys.every(key => scores[key] != null),
    [scores]
  );

  useEffect(() => {
    if (!selectedProjectId) {
      Promise.resolve().then(() => {
        setMembers([]);
        setSelectedUserCode('');
      });
      return;
    }

    let ignore = false;
    getProjectMembers(selectedProjectId)
      .then(data => {
        if (ignore) return;
        const selectableMembers = [...data]
          .filter(member => member.userCode)
          .sort((a, b) => getMemberName(a).localeCompare(getMemberName(b)));
        setMembers(selectableMembers);
        setSelectedUserCode(current => {
          if (current && selectableMembers.some(member => member.userCode === current)) {
            return current;
          }
          return '';
        });
      })
      .catch(error => {
        if (ignore) return;
        console.error(error);
        setMembers([]);
        setSelectedUserCode('');
        setError(t('memberLoadError'));
      });

    return () => {
      ignore = true;
    };
  }, [selectedProjectId, t]);

  useEffect(() => {
    if (!selectedProjectId) {
      Promise.resolve().then(() => {
        const today = todayInput();
        setPeriodStart(today);
        setPeriodEnd(today);
      });
      return;
    }

    let ignore = false;
    Promise.resolve()
      .then(() => {
        if (!ignore) setIsPeriodLoading(true);
        return getMergeRequestActivityPeriod(selectedProjectId, undefined, selectedUserCode || undefined);
      })
      .then(period => {
        if (ignore) return;
        const fallbackEnd = todayInput();
        const nextEnd = period.periodEnd || fallbackEnd;
        setPeriodStart(period.periodStart || period.firstMergeCreatedDate || nextEnd);
        setPeriodEnd(nextEnd);
      })
      .catch(error => {
        if (ignore) return;
        console.error(error);
        const today = todayInput();
        setPeriodStart(today);
        setPeriodEnd(today);
      })
      .finally(() => {
        if (!ignore) setIsPeriodLoading(false);
      });

    return () => {
      ignore = true;
    };
  }, [selectedProjectId, selectedUserCode]);

  const loadSurveyData = useCallback(async () => {
    if (!selectedProjectId || !selectedUserCode || !periodStart || !periodEnd) {
      setSummary(null);
      setSurveys([]);
      return;
    }
    setIsLoading(true);
    setError(null);
    try {
      const [summaryData, surveyData] = await Promise.all([
        getProjectSatisfactionSummary(selectedProjectId, requestParams),
        getProjectSatisfactionSurveys(selectedProjectId, requestParams),
      ]);
      setSummary(summaryData);
      setSurveys(surveyData);
    } catch (err) {
      console.error(err);
      setError(t('loadError'));
    } finally {
      setIsLoading(false);
    }
  }, [requestParams, selectedProjectId, selectedUserCode, periodStart, periodEnd, t]);

  useEffect(() => {
    void Promise.resolve().then(loadSurveyData);
  }, [loadSurveyData]);

  const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!selectedProjectId || !selectedUserCode || !periodStart || !periodEnd || !isSurveyComplete) return;

    const completedScores = questionKeys.reduce((result, key) => {
      result[key] = scores[key] as number;
      return result;
    }, {} as Record<SatisfactionQuestionKey, number>);

    setIsSaving(true);
    setMessage(null);
    setError(null);
    try {
      await createProjectSatisfactionSurvey(selectedProjectId, {
        ...completedScores,
        userCode: selectedUserCode,
        userName: getMemberName(selectedMember),
        surveyDate: periodParams.since,
        periodStart: periodParams.since || undefined,
        periodEnd: periodParams.until || undefined,
        comment: comment || undefined,
      });
      setMessage(t('saveSuccess'));
      setComment('');
      await loadSurveyData();
    } catch (err) {
      console.error(err);
      setError(t('saveError'));
    } finally {
      setIsSaving(false);
    }
  };

  const handlePeriodStartChange = (value: string) => {
    setPeriodStart(value);
    if (value && periodEnd && value > periodEnd) {
      setPeriodEnd(value);
    }
  };

  const handlePeriodEndChange = (value: string) => {
    setPeriodEnd(value);
    if (value && periodStart && value < periodStart) {
      setPeriodStart(value);
    }
  };

  return (
    <div className="space-y-6 pb-10">
      <div className="flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">{t('title')}</h1>
          <p className="mt-1 text-sm text-slate-500">
            {selectedProject?.name || t('noProject')}
            {selectedMember && <span className="ml-2 text-slate-400">/ {getMemberName(selectedMember)}</span>}
          </p>
        </div>
      </div>

      <div className="grid grid-cols-1 gap-6 xl:grid-cols-[minmax(0,1.4fr)_minmax(340px,0.8fr)]">
        <Card className="p-6">
          <div className="mb-5 flex items-center justify-between border-b border-slate-100 pb-4">
            <div className="flex items-center gap-3">
              <div className="flex h-9 w-9 items-center justify-center rounded bg-blue-50 text-blue-700">
                <ClipboardList className="h-5 w-5" />
              </div>
              <div>
                <h2 className="text-base font-bold text-slate-900">{t('formTitle')}</h2>
                <p className="text-xs text-slate-500">{t('formDescription')}</p>
              </div>
            </div>
            <div className="text-right">
              <p className="text-xs text-slate-500">{t('previewScore')}</p>
              <p className="text-xl font-black text-blue-700">{averagePreview == null ? '-' : averagePreview.toFixed(1)}</p>
            </div>
          </div>

          <form onSubmit={handleSubmit} className="space-y-5">
            <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
              <label className="block">
                <span className="mb-1 block text-xs font-medium text-slate-500">{t('developer')}</span>
                <select
                  value={selectedUserCode}
                  onChange={event => setSelectedUserCode(event.target.value)}
                  className="w-full rounded border border-slate-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-100"
                >
                  <option value="">{members.length === 0 ? t('noDevelopers') : t('selectDeveloper')}</option>
                  {members.map(member => (
                    <option key={member.userCode} value={member.userCode}>
                      {getMemberName(member)} ({member.userCode})
                    </option>
                  ))}
                </select>
              </label>
              <label className="block">
                <span className="mb-1 block text-xs font-medium text-slate-500">{t('surveyDate')}</span>
                <div className="grid grid-cols-[minmax(0,1fr)_auto_minmax(0,1fr)] items-center gap-2">
                  <input
                    type="date"
                    value={periodStart}
                    max={periodEnd || undefined}
                    onChange={event => handlePeriodStartChange(event.target.value)}
                    className="w-full rounded border border-slate-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-100"
                  />
                  <span className="text-xs font-semibold text-slate-400">-</span>
                  <input
                    type="date"
                    value={periodEnd}
                    min={periodStart || undefined}
                    onChange={event => handlePeriodEndChange(event.target.value)}
                    className="w-full rounded border border-slate-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-100"
                  />
                </div>
                {isPeriodLoading && <span className="mt-1 block text-[11px] text-slate-400">{t('loading')}</span>}
              </label>
            </div>

            <div className="divide-y divide-slate-100">
              {questionKeys.map(key => (
                <div key={key} className="space-y-3 py-4">
                  <div>
                    <p className="text-sm font-semibold text-slate-800">{t(`questions.${key}.title`)}</p>
                    <p className="mt-1 text-xs text-slate-500">{t(`questions.${key}.description`)}</p>
                  </div>
                  <div className="grid grid-cols-1 gap-2 sm:grid-cols-5">
                    {likertOptions.map(option => (
                      <ScoreButton
                        key={option.value}
                        label={t(`scale.${option.labelKey}`)}
                        selected={scores[key] === option.value}
                        onClick={() => setScores(current => ({ ...current, [key]: option.value }))}
                      />
                    ))}
                  </div>
                </div>
              ))}
            </div>

            <label className="block">
              <span className="mb-1 flex items-center gap-2 text-xs font-medium text-slate-500">
                <MessageSquareText className="h-4 w-4" />
                {t('comment')}
              </span>
              <textarea
                value={comment}
                onChange={event => setComment(event.target.value)}
                rows={4}
                placeholder={t('commentPlaceholder')}
                className="w-full resize-none rounded border border-slate-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-100"
              />
            </label>

            <div className="flex items-center justify-between border-t border-slate-100 pt-4">
              <div className="text-sm">
                {message && <span className="text-emerald-600">{message}</span>}
                {error && <span className="text-red-600">{error}</span>}
              </div>
              <button
                type="submit"
                disabled={!selectedProjectId || !selectedUserCode || !periodStart || !periodEnd || !isSurveyComplete || isSaving}
                className="inline-flex items-center gap-2 rounded bg-blue-600 px-4 py-2 text-sm font-bold text-white transition hover:bg-blue-700 disabled:cursor-not-allowed disabled:bg-slate-300"
              >
                <Save className="h-4 w-4" />
                {isSaving ? t('saving') : t('save')}
              </button>
            </div>
          </form>
        </Card>

        <div className="space-y-6">
          <Card className="p-6">
            <div className="mb-5 flex items-center justify-between">
              <div>
                <h2 className="text-base font-bold text-slate-900">{t('summaryTitle')}</h2>
                <p className="text-xs text-slate-500">{t('summaryDescription')}</p>
              </div>
              <TrendingUp className="h-5 w-5 text-emerald-600" />
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div className="rounded border border-slate-200 p-4">
                <p className="text-xs text-slate-500">{t('spaceScore')}</p>
                <p className="mt-1 text-3xl font-black text-slate-900">{formatScore(summary?.satisfactionScore)}</p>
              </div>
              <div className="rounded border border-slate-200 p-4">
                <p className="text-xs text-slate-500">{t('responses')}</p>
                <p className="mt-1 text-3xl font-black text-slate-900">{summary?.responseCount ?? 0}</p>
              </div>
            </div>
            <div className="mt-5 space-y-3">
              {subScoreGroups.map(group => (
                <div key={group.key}>
                  <div className="mb-1 flex justify-between text-xs">
                    <span className="font-medium text-slate-600">{group.label}</span>
                    <span className="text-slate-500">{formatScore(summary?.[group.key])}</span>
                  </div>
                  <div className="h-2 rounded-full bg-slate-100">
                    <div
                      className="h-2 rounded-full bg-emerald-600"
                      style={{ width: `${Math.min(100, Math.max(0, summary?.[group.key] ?? 0))}%` }}
                    />
                  </div>
                </div>
              ))}
            </div>
            <div className="mt-5 space-y-3">
              {questionKeys.map(key => (
                <div key={key}>
                  <div className="mb-1 flex justify-between text-xs">
                    <span className="font-medium text-slate-600">{t(`questions.${key}.title`)}</span>
                    <span className="text-slate-500">{formatScore(summary?.[key])}</span>
                  </div>
                  <div className="h-2 rounded-full bg-slate-100">
                    <div
                      className="h-2 rounded-full bg-blue-600"
                      style={{ width: `${Math.min(100, Math.max(0, summary?.[key] ?? 0))}%` }}
                    />
                  </div>
                </div>
              ))}
            </div>
          </Card>

          <Card className="p-6">
            <div className="mb-4 flex items-center justify-between">
              <h2 className="text-base font-bold text-slate-900">{t('historyTitle')}</h2>
              {isLoading && <span className="text-xs text-slate-400">{t('loading')}</span>}
            </div>
            <div className="space-y-3">
              {surveys.length === 0 && (
                <div className="rounded border border-dashed border-slate-200 px-4 py-6 text-center text-sm text-slate-500">
                  {t('emptyHistory')}
                </div>
              )}
              {surveys.slice(0, 8).map(survey => (
                <div key={survey.id} className="rounded border border-slate-200 px-4 py-3">
                  <div className="flex items-center justify-between gap-3">
                    <div>
                      <p className="text-sm font-semibold text-slate-800">
                        {formatPeriod(survey.periodStart || survey.surveyDate, survey.periodEnd || survey.surveyDate)}
                      </p>
                      <p className="text-xs text-slate-500">{survey.userName || survey.userCode}</p>
                    </div>
                    <span className="rounded bg-slate-100 px-2.5 py-1 text-sm font-bold text-slate-700">
                      {formatScore(survey.responseScore)}
                    </span>
                  </div>
                  {survey.comment && <p className="mt-2 line-clamp-2 text-xs text-slate-500">{survey.comment}</p>}
                </div>
              ))}
            </div>
          </Card>
        </div>
      </div>
    </div>
  );
};
