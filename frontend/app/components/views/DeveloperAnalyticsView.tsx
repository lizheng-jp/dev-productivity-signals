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
        <h2 className="text-lg font-bold text-slate-800">{t('title')}</h2>
        <div className="flex items-center gap-2">
          {GROUP_UI_ENABLED && (
            <select
              aria-label={t('groupFilter')}
              value={selectedTeam}
              onChange={(e) => setSelectedTeam(e.target.value)}
              className="px-3 py-1.5 text-sm bg-white border border-slate-300 rounded text-slate-600 focus:outline-none focus:ring-2 focus:ring-blue-500"
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
            className="px-3 py-1.5 text-sm bg-white border border-slate-300 rounded text-slate-600 focus:outline-none focus:ring-2 focus:ring-blue-500"
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
        <div className="grid grid-cols-1 md::grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-6">
          {filteredAndSortedStats.map((stat) => (
            <Card
              key={stat.member.userCode}
              className="flex flex-col hover:shadow-lg transition-shadow duration-200 cursor-pointer group"
              onClick={() => onViewDetails(stat)}
            >

              {/* Card Body */}
              <div className="p-5 flex-1 flex flex-col items-center text-center">
                <h3 className="font-bold text-base text-slate-800">{stat.member.userName || stat.member.userCode}({stat.member.userCode})</h3>
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
              <div className="px-5 py-3 border-t border-slate-100 bg-slate-50/50 flex justify-between items-center">
                <span className="text-xs text-slate-500">
                  {sortKey === 'commitCount' && t('totalCommits', { count: stat.commitCount || 0 })}
                  {sortKey === 'mergedCount' && t('totalMerges', { count: stat.mergedCount || 0 })}
                  {sortKey === 'issueCreatedCount' && t('issuesCreated', { count: stat.issueCreatedCount || 0 })}
                  {sortKey === 'bugFoundCount' && t('bugsFound', { count: stat.bugFoundCount || 0 })}
                </span>
                <TrendBadge trend={stat.trends?.[sortKey]} />
                <button className="text-xs font-semibold text-blue-600 flex items-center gap-1 group-hover:underline">
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
