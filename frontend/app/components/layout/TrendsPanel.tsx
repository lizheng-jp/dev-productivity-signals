"use client";

import { useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { Activity, ChevronLeft, ChevronRight, Gauge, Loader2, Medal, Users } from 'lucide-react';
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { useTranslations } from 'next-intl';
import { useMainLayout } from '@/contexts/MainLayoutContext';
import { getCommitCount } from '@/lib/api/commits';
import { formatLocalDate } from '@/lib/date-format';
import { DeveloperStat } from '@/types/developer';
import { GROUP_UI_ENABLED } from '@/lib/feature-flags';

type ActivityTrendPoint = {
  label: string;
  commits: number;
};

type InsightCardProps = {
  title: string;
  value: string;
  detail: string;
  icon: ReactNode;
  tone: 'blue' | 'green' | 'amber';
  isLoading?: boolean;
  isAiCorrecting?: boolean;
};

type TrendsPanelProps = {
  isOpen: boolean;
  onToggle: () => void;
};

const cardToneStyles = {
  blue: 'bg-blue-50 border-blue-100 text-blue-700',
  green: 'bg-emerald-50 border-emerald-100 text-emerald-700',
  amber: 'bg-amber-50 border-amber-100 text-amber-700',
};

const iconToneStyles = {
  blue: 'text-blue-600',
  green: 'text-emerald-600',
  amber: 'text-amber-600',
};

function formatScore(score?: number) {
  return Number.isFinite(score) ? Number(score).toFixed(1) : '-';
}

function formatHours(hours?: number) {
  if (!Number.isFinite(hours)) return '-';
  return `${Number(hours).toFixed(1)}h`;
}

function getDeveloperName(stat?: DeveloperStat) {
  if (!stat) return '-';
  return stat.member.userName || stat.member.userCode || '-';
}

function getTopPerformer(developerStats: DeveloperStat[]) {
  return developerStats.reduce<DeveloperStat | undefined>((best, current) => {
    if (!best) return current;
    return (current.totalScore || 0) > (best.totalScore || 0) ? current : best;
  }, undefined);
}

function getBestTeam(developerStats: DeveloperStat[], unassignedLabel: string) {
  const teams = new Map<string, { totalScore: number; members: number }>();

  developerStats.forEach((stat) => {
    const teamName = stat.member.groupName || unassignedLabel;
    const current = teams.get(teamName) || { totalScore: 0, members: 0 };
    teams.set(teamName, {
      totalScore: current.totalScore + (stat.totalScore || 0),
      members: current.members + 1,
    });
  });

  return Array.from(teams.entries())
    .map(([name, team]) => ({
      name,
      members: team.members,
      averageScore: team.members > 0 ? team.totalScore / team.members : 0,
    }))
    .sort((a, b) => b.averageScore - a.averageScore)[0];
}

function formatTrendLabel(weekStart: string, index: number) {
  if (!weekStart) return `W${index + 1}`;
  const date = new Date(weekStart);
  if (Number.isNaN(date.getTime())) return weekStart;
  return `${date.getMonth() + 1}/${date.getDate()}`;
}

function InsightCard({ title, value, detail, icon, tone, isLoading, isAiCorrecting }: InsightCardProps) {
  const t = useTranslations('TrendsPanel');

  return (
    <div className={`rounded-lg border p-4 ${cardToneStyles[tone]}`}>
      <div className="flex items-center justify-between mb-2">
        <span className="text-xs font-semibold uppercase">{title}</span>
        <span className={iconToneStyles[tone]}>{icon}</span>
      </div>
      <div className="mb-1 flex items-center gap-2">
        <div className="min-w-0 truncate text-2xl font-bold text-slate-800">
          {isLoading ? '...' : value}
        </div>
        {!isLoading && isAiCorrecting && (
          <span className="inline-flex shrink-0 items-center gap-1 rounded-full bg-surface/70 px-2 py-0.5 text-[10px] font-bold text-blue-600">
            <Loader2 className="h-3 w-3 animate-spin" />
            AI補正中
          </span>
        )}
      </div>
      <div className="text-xs text-slate-600 truncate">{isLoading ? t('loading') : detail}</div>
    </div>
  );
}

export const TrendsPanel = ({ isOpen, onToggle }: TrendsPanelProps) => {
  const t = useTranslations('TrendsPanel');
  const { projects, selectedProjectId, selectedBranch, date, dashboardStats } = useMainLayout();
  const since = formatLocalDate(date?.from);
  const until = formatLocalDate(date?.to);

  const {
    developerStats,
    projectStats,
    isDeveloperLoading,
    isProjectLoading,
    isDeveloperAiCorrecting,
    isProjectAiCorrecting,
  } = dashboardStats;
  const [activityTrend, setActivityTrend] = useState<ActivityTrendPoint[]>([]);
  const [isTrendLoading, setIsTrendLoading] = useState(false);

  useEffect(() => {
    let ignore = false;

    if (!selectedProjectId || !since || !until) {
      Promise.resolve().then(() => {
        if (ignore) return;
        setActivityTrend([]);
        setIsTrendLoading(false);
      });
      return;
    }

    const fetchTrend = async () => {
      setIsTrendLoading(true);
      try {
        const data = await getCommitCount(selectedProjectId, since, until, undefined, selectedBranch || undefined);
        if (ignore) return;

        setActivityTrend(
          (data.weeklyBreakdown || []).map((point, index) => ({
            label: formatTrendLabel(point.weekStart, index),
            commits: point.count || 0,
          }))
        );
      } catch (error) {
        if (!ignore) {
          console.error('Failed to fetch activity trend', error);
          setActivityTrend([]);
        }
      } finally {
        if (!ignore) setIsTrendLoading(false);
      }
    };

    fetchTrend();

    return () => {
      ignore = true;
    };
  }, [selectedProjectId, selectedBranch, since, until]);

  const topPerformer = useMemo(() => getTopPerformer(developerStats), [developerStats]);
  const bestTeam = useMemo(() => getBestTeam(developerStats, t('unassignedTeam')), [developerStats, t]);
  const selectedProject = projects.find((project) => project.id.toString() === selectedProjectId);
  const isLoading = isDeveloperLoading || isProjectLoading;
  const avgLeadTimeHours = projectStats?.mergedLeadTimeHours || projectStats?.averageHoursToMerge || 0;

  return (
    <>
      {!isOpen && (
        <button
          type="button"
          onClick={onToggle}
          aria-label={t('openPanel')}
          title={t('openPanel')}
          className="fixed right-3 top-4 z-40 flex h-10 w-10 items-center justify-center rounded-lg border border-slate-200 bg-white/90 backdrop-blur-2xl text-slate-600 shadow-sm transition hover:bg-slate-50 hover:text-slate-900"
        >
          <ChevronLeft size={18} />
        </button>
      )}

      <aside
        className={`fixed right-0 top-0 z-30 h-screen w-80 overflow-y-auto border-l border-slate-200 bg-white/90 backdrop-blur-2xl transition-transform duration-300 ease-in-out ${
          isOpen ? 'translate-x-0' : 'pointer-events-none translate-x-full'
        }`}
        aria-hidden={!isOpen}
      >
        <div className="p-6">
          <div className="mb-6 flex items-start justify-between gap-3">
            <div className="min-w-0">
              <h2 className="text-lg font-semibold text-slate-800">{t('title')}</h2>
              <p className="text-xs text-slate-500 truncate">
                {selectedProject?.name || t('noProjectSelected')}
              </p>
            </div>
            <button
              type="button"
              onClick={onToggle}
              aria-label={t('collapsePanel')}
              title={t('collapsePanel')}
              className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border border-slate-200 text-slate-500 transition hover:bg-slate-50 hover:text-slate-900"
            >
              <ChevronRight size={16} />
            </button>
          </div>

          <div className="space-y-4">
            <InsightCard
              title={t('projectSpaceScore')}
              value={formatScore(projectStats?.totalScore)}
              detail={t('projectSpaceDetail')}
              icon={<Gauge size={16} />}
              tone="blue"
              isLoading={isProjectLoading}
              isAiCorrecting={isProjectAiCorrecting}
            />

            <InsightCard
              title={t('topPerformer')}
              value={getDeveloperName(topPerformer)}
              detail={t('spaceScoreDetail', { score: formatScore(topPerformer?.totalScore) })}
              icon={<Medal size={16} />}
              tone="blue"
              isLoading={isLoading}
              isAiCorrecting={isDeveloperAiCorrecting}
            />

            {GROUP_UI_ENABLED && <InsightCard
              title={t('bestTeam')}
              value={bestTeam?.name || '-'}
              detail={t('avgScoreDetail', {
                score: formatScore(bestTeam?.averageScore),
                count: bestTeam?.members || 0,
              })}
              icon={<Users size={16} />}
              tone="green"
              isLoading={isLoading}
              isAiCorrecting={isDeveloperAiCorrecting}
            />}

            <InsightCard
              title={t('avgLeadTime')}
              value={formatHours(avgLeadTimeHours)}
              detail={t('avgLeadTimeDetail')}
              icon={<Activity size={16} />}
              tone="amber"
              isLoading={isProjectLoading}
            />
          </div>

          <div className="mt-8">
            <h3 className="text-sm font-semibold text-slate-700 mb-4">{t('activityTrend')}</h3>
            <div className="h-[150px]">
              {isTrendLoading ? (
                <div className="h-full flex items-center justify-center text-sm text-slate-400">{t('loading')}</div>
              ) : activityTrend.length > 0 ? (
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={activityTrend}>
                    <CartesianGrid strokeDasharray="3 3" stroke="var(--color-slate-100, #f1f5f9)" />
                    <XAxis dataKey="label" tick={{ fontSize: 10 }} />
                    <YAxis tick={{ fontSize: 10 }} allowDecimals={false} />
                    <Tooltip />
                    <Line type="monotone" dataKey="commits" stroke="var(--color-blue-600, #2563eb)" strokeWidth={2} dot={{ r: 4 }} />
                  </LineChart>
                </ResponsiveContainer>
              ) : (
                <div className="h-full flex items-center justify-center text-sm text-slate-400">
                  {t('noCommitActivity')}
                </div>
              )}
            </div>
          </div>
        </div>
      </aside>
    </>
  );
};
