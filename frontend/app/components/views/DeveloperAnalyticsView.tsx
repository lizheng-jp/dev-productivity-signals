// components/views/DeveloperAnalyticsView.tsx
"use client";
import { TrendBadge } from '@/components/ui/TrendBadge';
import { useTranslations } from 'next-intl';
import React, { useState, useMemo } from 'react';
import { ChevronRight } from 'lucide-react';
import { Card } from '@/components/ui/Common';
import { SpaceRadarChart } from '@/components/ui/Charts';
import { DeveloperStat } from '@/types/developer';
import { GROUP_UI_ENABLED } from '@/lib/feature-flags';

type Props = {
  onViewDetails: (dev: DeveloperStat) => void;
  developerStats: DeveloperStat[];
  isLoading: boolean;
};

type SortKey = 'commitCount' | 'mergedCount' | 'issueCreatedCount' | 'bugFoundCount';

export const DeveloperAnalyticsView = ({ onViewDetails, developerStats, isLoading }: Props) => {
  const t = useTranslations('DeveloperAnalytics');
  const tTable = useTranslations('Table');
  const tSpace = useTranslations('Space');
  const [selectedTeam, setSelectedTeam] = useState('');
  const [sortKey, setSortKey] = useState<SortKey>('commitCount');

  const uniqueGroupNames = useMemo(() => {
    const groups = new Set(developerStats.map(stat => stat.member.groupName).filter((name): name is string => Boolean(name)));
    return Array.from(groups);
  }, [developerStats]);

  const filteredAndSortedStats = useMemo(() => {
    let stats = [...developerStats];

    if (selectedTeam !== '') {
      stats = stats.filter(stat => stat.member.groupName === selectedTeam);
    }

    stats.sort((a, b) => {
      const valA = a[sortKey] || 0;
      const valB = b[sortKey] || 0;
      return valB - valA; // Descending
    });

    return stats;
  }, [developerStats, selectedTeam, sortKey]);

  return (
    <div className="space-y-6 pb-10">

      <div className="flex justify-between items-center">
        <h2 className="text-3xl font-extrabold tracking-tight text-slate-900">{t('title')}</h2>
        <div className="flex items-center gap-2">
          {GROUP_UI_ENABLED && (
            <select
              aria-label={t('groupFilter')}
              value={selectedTeam}
              onChange={(e) => setSelectedTeam(e.target.value)}
              className="h-10 rounded-full border border-white/80 bg-white/80 px-4 text-sm font-medium text-slate-700 shadow-sm ring-1 ring-slate-900/5 focus:outline-none focus:ring-2 focus:ring-blue-500/40"
            >
              <option value="">{t('allTeams')}</option>
              {uniqueGroupNames.map(groupName => (
                <option key={groupName} value={groupName}>{groupName}</option>
              ))}
            </select>
          )}

          <select
            aria-label={t('sortBy')}
            value={sortKey}
            onChange={(e) => setSortKey(e.target.value as SortKey)}
            className="h-10 rounded-full border border-white/80 bg-white/80 px-4 text-sm font-medium text-slate-700 shadow-sm ring-1 ring-slate-900/5 focus:outline-none focus:ring-2 focus:ring-blue-500/40"
          >
            <option value="commitCount">{t('sortBy')} {tTable('commits')}</option>
            <option value="mergedCount">{t('sortBy')} {tTable('merges')}</option>
            <option value="issueCreatedCount">{t('sortBy')} {tTable('issues')}</option>
            <option value="bugFoundCount">{t('sortBy')} {tTable('bugFoundCount')}</option>
          </select>
        </div>
      </div>

      {isLoading ? (
        <p>{t('loading')}</p>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-6">
          {filteredAndSortedStats.map((stat) => (
            <Card
              key={stat.member.userCode}
              className="flex flex-col cursor-pointer group transition-all duration-300 hover:-translate-y-1 hover:shadow-lg"
              onClick={() => onViewDetails(stat)}
            >

              {/* Card Body */}
              <div className="p-5 flex-1 flex flex-col items-center text-center">
                <span className="bg-aurora mb-3 flex h-12 w-12 items-center justify-center rounded-full p-[2px] shadow-[0_6px_16px_-6px_rgb(139_92_246/0.6)]">
                  <span className="flex h-full w-full items-center justify-center rounded-full bg-white text-sm font-bold text-violet-600">{(stat.member.userName || stat.member.userCode || '?').trim().charAt(0)}</span>
                </span>
                <h3 className="font-bold text-base text-slate-900">{stat.member.userName || stat.member.userCode}</h3>
                <p className="font-mono text-[10.5px] tracking-wider text-slate-400">{stat.member.userCode}</p>
                {GROUP_UI_ENABLED && (
                  <p className="text-xs text-slate-500 mb-4">{stat.member.groupName || tTable('noGroup')}</p>
                )}

                <div className="w-full h-48 my-2">
                  <SpaceRadarChart chartData={[
                    { subject: tSpace('performance'), value: Math.round(stat.performanceScore || 0) },
                    { subject: tSpace('activity'), value: Math.round(stat.activityScore || 0) },
                    { subject: tSpace('communication'), value: Math.round(stat.communicationScore || 0) },
                    { subject: tSpace('efficiency'), value: Math.round(stat.efficiencyScore || 0) },
                    { subject: tSpace('satisfaction'), value: Math.round(stat.satisfactionScore || 0) },
                  ]} />
                </div>
              </div>

              {/* Card Footer - Action */}
              <div className="px-5 py-3 border-t border-slate-200/60 flex flex-wrap justify-between items-center gap-2">
                <span className="whitespace-nowrap font-mono text-xs text-slate-600 tabular">
                  {sortKey === 'commitCount' && t('totalCommits', { count: stat.commitCount || 0 })}
                  {sortKey === 'mergedCount' && t('totalMerges', { count: stat.mergedCount || 0 })}
                  {sortKey === 'issueCreatedCount' && t('issuesCreated', { count: stat.issueCreatedCount || 0 })}
                  {sortKey === 'bugFoundCount' && t('bugsFound', { count: stat.bugFoundCount || 0 })}
                </span>
                <TrendBadge trend={stat.trends?.[sortKey]} />
                <button className="whitespace-nowrap text-xs font-semibold text-blue-600 flex items-center gap-1 transition-transform group-hover:translate-x-0.5">
                  {t('viewDetails')} <ChevronRight className="w-3 h-3" />
                </button>
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
};
