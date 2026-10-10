"use client";

import React, { useEffect, useMemo, useState } from 'react';
import { DateRange } from 'react-day-picker';
import { useTranslations } from 'next-intl';
import { ChevronRight, ExternalLink, Loader2, TriangleAlert } from 'lucide-react';
import { Card, cn } from '@/components/ui/Common';
import { SpaceRadarChart } from '@/components/ui/Charts';
import { getSpaceMetrics } from '@/lib/api/space-metrics';
import { buildProjectStat } from '@/lib/hooks/useProjectStats';
import { formatLocalDate } from '@/lib/date-format';
import type { Project } from '@/lib/api/projects';
import type { ProjectStat } from '@/types/project';

type SortKey = 'totalScore' | 'commitCount' | 'mergedCount' | 'name';

type LoadState =
  | { status: 'queued' }
  | { status: 'loading' }
  | { status: 'done'; stat: ProjectStat }
  | { status: 'error' };

// Each project is computed live from the GitHub API, so only a couple run at once.
const CONCURRENCY = 2;

interface ProjectPortfolioViewProps {
  projects: Project[];
  date: DateRange | undefined;
  onOpenProject: (project: Project) => void;
}

export const ProjectPortfolioView = ({ projects, date, onOpenProject }: ProjectPortfolioViewProps) => {
  const t = useTranslations('ProjectPortfolio');
  const tSpace = useTranslations('Space');
  const [sortKey, setSortKey] = useState<SortKey>('totalScore');
  const [states, setStates] = useState<Record<string, LoadState>>({});

  const since = formatLocalDate(date?.from);
  const until = formatLocalDate(date?.to);
  const projectKey = projects.map(project => project.id).join(',');

  useEffect(() => {
    if (!since || !until || projects.length === 0) return;
    let cancelled = false;
    const queue = [...projects];
    setStates(Object.fromEntries(projects.map(project => [project.id, { status: 'queued' }])));

    const worker = async () => {
      while (!cancelled && queue.length > 0) {
        const project = queue.shift()!;
        setStates(current => ({ ...current, [project.id]: { status: 'loading' } }));
        try {
          const metrics = await getSpaceMetrics(project.id, {
            since,
            until,
            refName: project.defaultBranch || undefined,
            aiEnabled: false,
          });
          if (cancelled) return;
          const stat = { ...buildProjectStat(project.id, project.fullName || project.name, metrics), spaceMetrics: metrics };
          setStates(current => ({ ...current, [project.id]: { status: 'done', stat } }));
        } catch (error) {
          console.error('Failed to load project metrics:', project.id, error);
          if (!cancelled) setStates(current => ({ ...current, [project.id]: { status: 'error' } }));
        }
      }
    };

    void Promise.all(Array.from({ length: CONCURRENCY }, worker));
    return () => {
      cancelled = true;
    };
    // projectKey captures the project list; the array identity changes on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectKey, since, until]);

  const sortedProjects = useMemo(() => {
    const statOf = (project: Project) => {
      const state = states[project.id];
      return state?.status === 'done' ? state.stat : null;
    };
    return [...projects].sort((a, b) => {
      if (sortKey === 'name') return (a.fullName || a.name).localeCompare(b.fullName || b.name);
      const left = statOf(a);
      const right = statOf(b);
      if (!left || !right) return left ? -1 : right ? 1 : 0;
      return (right[sortKey] || 0) - (left[sortKey] || 0);
    });
  }, [projects, states, sortKey]);

  const loadedCount = Object.values(states).filter(state => state.status === 'done' || state.status === 'error').length;

  return (
    <div className="space-y-6 pb-10">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="kicker" aria-hidden="true">Workspace / Projects</p>
          <h2 className="mt-1 text-3xl font-extrabold tracking-tight text-slate-900">{t('title')}</h2>
          <p className="mt-1 text-sm text-slate-500">
            {t('subtitle')}
            {projects.length > 0 && loadedCount < projects.length && (
              <span className="ml-2 font-mono text-xs text-slate-400 tabular">{t('progress', { done: loadedCount, total: projects.length })}</span>
            )}
          </p>
        </div>
        <select
          aria-label={t('sortBy')}
          value={sortKey}
          onChange={event => setSortKey(event.target.value as SortKey)}
          className="h-10 rounded-full border border-white/80 bg-white/80 px-4 text-sm font-medium text-slate-700 shadow-sm ring-1 ring-slate-900/5 focus:outline-none focus:ring-2 focus:ring-blue-500/40"
        >
          <option value="totalScore">{t('sortBy')} {t('score')}</option>
          <option value="commitCount">{t('sortBy')} {t('commits')}</option>
          <option value="mergedCount">{t('sortBy')} {t('merges')}</option>
          <option value="name">{t('sortBy')} {t('name')}</option>
        </select>
      </div>

      {projects.length === 0 ? (
        <Card className="p-10 text-center text-sm text-slate-500">{t('empty')}</Card>
      ) : (
        <div className="grid grid-cols-1 gap-6 md:grid-cols-2 xl:grid-cols-3">
          {sortedProjects.map(project => (
            <ProjectCard
              key={project.id}
              project={project}
              state={states[project.id] ?? { status: 'queued' }}
              onOpen={() => onOpenProject(project)}
              labels={{
                performance: tSpace('performance'),
                activity: tSpace('activity'),
                communication: tSpace('communication'),
                efficiency: tSpace('efficiency'),
                satisfaction: tSpace('satisfaction'),
              }}
            />
          ))}
        </div>
      )}
    </div>
  );
};

// Satisfaction comes from the survey when there are responses, otherwise from
// contributor retention; say which so the radar's S axis is not misread.
const satisfactionNote = (stat: ProjectStat, t: ReturnType<typeof useTranslations>) => {
  const metrics = stat.spaceMetrics;
  if (metrics?.satisfactionSource === 'survey') {
    return t('satisfactionSurvey', { count: metrics.satisfactionResponseCount ?? 0 });
  }
  if (metrics?.satisfactionSource === 'retention') {
    return t('satisfactionRetention', {
      rate: metrics.contributorRetentionRate ?? 0,
      retained: metrics.retainedContributorCount ?? 0,
      previous: metrics.previousActiveContributorCount ?? 0,
    });
  }
  return t('satisfactionNone');
};

const ProjectCard = ({ project, state, onOpen, labels }: {
  project: Project;
  state: LoadState;
  onOpen: () => void;
  labels: Record<'performance' | 'activity' | 'communication' | 'efficiency' | 'satisfaction', string>;
}) => {
  const t = useTranslations('ProjectPortfolio');
  const [owner, repo] = (project.fullName || project.name).split('/');
  const stat = state.status === 'done' ? state.stat : null;

  return (
    <Card
      className="group flex cursor-pointer flex-col transition-all duration-300 hover:-translate-y-1 hover:shadow-lg"
      onClick={onOpen}
    >
      <div className="flex items-start gap-3 p-5 pb-0">
        <span className="bg-aurora flex h-11 w-11 shrink-0 items-center justify-center rounded-xl p-[2px] shadow-[0_6px_16px_-6px_rgb(139_92_246/0.6)]">
          <span className="flex h-full w-full items-center justify-center rounded-[10px] bg-white text-sm font-bold uppercase text-violet-600">
            {(owner || '?').charAt(0)}
          </span>
        </span>
        <div className="min-w-0 flex-1">
          <p className="font-mono text-[11px] tracking-wide text-slate-400">{repo ? owner : ''}</p>
          <h3 className="truncate text-base font-bold text-slate-900">{repo || owner}</h3>
        </div>
        {stat && (
          <div className="text-right">
            <p className="kicker">{t('score')}</p>
            <p className="font-mono text-2xl font-bold leading-none tracking-tight text-slate-900 tabular">{Math.round(stat.totalScore)}</p>
          </div>
        )}
      </div>
      {project.description && (
        <p className="line-clamp-2 min-h-10 px-5 pt-2 text-xs leading-5 text-slate-500">{project.description}</p>
      )}

      <div className="relative mx-2 h-52">
        {stat ? (
          <SpaceRadarChart chartData={[
            { subject: labels.performance, value: Math.round(stat.performanceScore || 0) },
            { subject: labels.activity, value: Math.round(stat.activityScore || 0) },
            { subject: labels.communication, value: Math.round(stat.communicationScore || 0) },
            { subject: labels.efficiency, value: Math.round(stat.efficiencyScore || 0) },
            { subject: labels.satisfaction, value: Math.round(stat.satisfactionScore || 0) },
          ]} />
        ) : (
          <div className="flex h-full flex-col items-center justify-center gap-2 text-xs text-slate-400">
            {state.status === 'error' ? (
              <>
                <TriangleAlert className="h-5 w-5 text-amber-500" aria-hidden="true" />
                <span className="max-w-56 text-center">{t('loadError')}</span>
              </>
            ) : (
              <>
                <Loader2 className={cn('h-5 w-5', state.status === 'loading' && 'animate-spin')} aria-hidden="true" />
                <span>{t(state.status === 'loading' ? 'loading' : 'queued')}</span>
              </>
            )}
          </div>
        )}
      </div>

      {stat && <p className="px-5 pb-2 text-center text-[11px] text-slate-500">{satisfactionNote(stat, t)}</p>}

      <div className="mt-auto grid grid-cols-3 border-t border-slate-200/60 text-center">
        {([
          ['commits', stat ? Math.round(stat.commitCount) : null],
          ['merges', stat ? Math.round(stat.mergedCount) : null],
          ['leadTime', stat && stat.mergedCount > 0 ? `${stat.mergedLeadTimeHours.toFixed(1)}h` : null],
        ] as const).map(([key, value]) => (
          <div key={key} className="px-2 py-3">
            <p className="kicker text-[9.5px]">{t(key)}</p>
            <p className="mt-1 font-mono text-sm font-bold text-slate-800 tabular">{value ?? '—'}</p>
          </div>
        ))}
      </div>
      <div className="flex items-center justify-between border-t border-slate-200/60 px-5 py-2.5">
        {project.webUrl ? (
          <a
            href={project.webUrl}
            target="_blank"
            rel="noreferrer"
            onClick={event => event.stopPropagation()}
            className="inline-flex items-center gap-1 text-xs text-slate-500 hover:text-slate-800"
          >
            <ExternalLink className="h-3 w-3" aria-hidden="true" /> GitHub
          </a>
        ) : <span />}
        <span className="inline-flex items-center gap-1 text-xs font-semibold text-blue-600 transition-transform group-hover:translate-x-0.5">
          {t('viewDetails')} <ChevronRight className="h-3 w-3" aria-hidden="true" />
        </span>
      </div>
    </Card>
  );
};
