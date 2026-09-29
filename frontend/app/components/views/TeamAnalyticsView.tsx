// components/views/TeamAnalyticsView.tsx
import React, { useState, useEffect } from 'react';
import { Card } from '@/components/ui/Common';
import { SpaceRadarChart } from '@/components/ui/Charts';
import { Users, Code, GitPullRequest } from 'lucide-react';
import { getGroups, Group } from '@/lib/api/groups';
import { DeveloperStat } from '@/types/developer';

type Props = {
    developerStats: DeveloperStat[];
    isLoadingStats: boolean;
}

// Placeholder for SPACE metrics until backend API is ready
const PLACEHOLDER_SPACE_METRICS = [
    { subject: 'Satisfaction', value: 0 },
    { subject: 'Performance', value: 0 },
    { subject: 'Activity', value: 0 },
    { subject: 'Communication', value: 0 },
    { subject: 'Efficiency', value: 0 },
    { subject: 'Quality', value: 0 },
];

export const TeamAnalyticsView = ({ developerStats, isLoadingStats }: Props) => {
    // 2. Get all custom groups
    const [groups, setGroups] = useState<Group[]>([]);
    const [isLoadingGroups, setIsLoadingGroups] = useState(true);

    useEffect(() => {
        getGroups().then(data => {
            setGroups(data);
            setIsLoadingGroups(false);
        }).catch(err => console.error(err));
    }, []);

    // 3. Aggregate Logic

  const groupStats = groups.map(group => {
    const groupUserCodes = new Set(group.users.map(u => u.userCode));
    // Filter developers who are both in this group AND in the fetched project stats
    const membersInStats = developerStats.filter(stat => groupUserCodes.has(stat.member.userCode));

    const totalCommits = membersInStats.reduce((sum, stat) => sum + (stat.commitCount || 0), 0);
    const totalMerges = membersInStats.reduce((sum, stat) => sum + (stat.mergedCount || 0), 0);
    // Only count members who have contributed/are part of the project
    const memberCount = membersInStats.length;

    return {
      name: group.groupName,
      members: memberCount,
      commits: totalCommits,
      merges: totalMerges,
      score: 0, // Placeholder
      spaceMetrics: PLACEHOLDER_SPACE_METRICS, // Placeholder
      id: group.groupId
    };
  }).filter(g => g.members > 0); // Optional: Only show groups that have members in this project? Or show all?
  // Let's show all groups, or maybe only those with activity.
  // Usually showing all groups assigned to the project is better, but here we are intersecting "All Groups" with "Project Members".
  // If a group has NO members in the current project, it might be distracting to show it with 0 stats.
  // However, if the user explicitly wants to see which groups are active, filtering is good.
  // Let's keep all groups for now but visual indication of 0 activity is clear.
  // Actually, let's show all groups to be consistent with "Team Analytics".

  if (isLoadingStats || isLoadingGroups) {
    return (
      <div className="flex justify-center py-20">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600"></div>
      </div>
    );
  }

  return (
    <div className="space-y-6 pb-10">
      <div className="flex justify-between items-center">
        <h2 className="text-lg font-bold text-slate-800">Team Performance Overview</h2>
        <button className="px-3 py-1.5 text-sm bg-white border border-slate-300 rounded text-slate-600 hover:bg-slate-50">
          Export Report
        </button>
      </div>

      {groupStats.length === 0 ? (
        <div className="text-center py-12 bg-white rounded-lg border border-slate-200">
          <p className="text-slate-500">No groups found or no group members assigned to this project.</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {groupStats.map((team) => (
            <Card key={team.id} className="flex flex-col hover:shadow-lg transition-shadow duration-200">
              {/* Card Header */}
              <div className="px-6 py-4 border-b border-slate-100 flex items-center justify-between bg-slate-50/50">
                <div>
                  <h3 className="font-bold text-base text-slate-800">{team.name}</h3>
                  <p className="text-xs text-slate-500">Group ID: {team.id}</p>
                </div>
                <span className="bg-white border border-slate-200 text-slate-600 text-xs px-2.5 py-1 rounded-full font-medium shadow-sm">
                  {team.members} Active Members
                </span>
              </div>

              {/* Card Body */}
              <div className="p-6 flex-1 flex flex-col gap-6">
                <div className="w-full h-64 relative opacity-50 grayscale">
                  {/* Placeholder styling for chart since data is fake */}
                  <div className="absolute inset-0 flex items-center justify-center z-10">
                    <span className="bg-slate-100/80 px-2 py-1 rounded text-xs text-slate-500 font-medium">Metric Data Coming Soon</span>
                  </div>
                  <SpaceRadarChart chartData={team.spaceMetrics} />
                </div>

                <div className="grid grid-cols-3 gap-3">
                  <div className="p-3 bg-slate-50 rounded-lg border border-slate-100 flex flex-col items-center justify-center">
                    <Code className="w-4 h-4 text-blue-500 mb-1" />
                    <span className="font-bold text-slate-800 text-lg">{team.commits}</span>
                    <span className="text-[10px] uppercase tracking-wider text-slate-400 font-semibold">Commits</span>
                  </div>
                  <div className="p-3 bg-slate-50 rounded-lg border border-slate-100 flex flex-col items-center justify-center">
                    <GitPullRequest className="w-4 h-4 text-purple-500 mb-1" />
                    <span className="font-bold text-slate-800 text-lg">{team.merges}</span>
                    <span className="text-[10px] uppercase tracking-wider text-slate-400 font-semibold">MRs</span>
                  </div>
                  <div className="p-3 bg-slate-50 rounded-lg border border-slate-100 flex flex-col items-center justify-center opacity-50">
                    <Users className="w-4 h-4 text-emerald-500 mb-1" />
                    <span className="font-bold text-slate-800 text-lg">-</span>
                    <span className="text-[10px] uppercase tracking-wider text-slate-400 font-semibold">SPACE</span>
                  </div>
                </div>
              </div>

              {/* Card Footer - Action */}
              <div className="px-6 py-3 border-t border-slate-100 bg-slate-50/30">
                <button className="w-full text-center text-xs font-semibold text-blue-600 hover:text-blue-700 hover:underline">
                  View Detailed Analytics &rarr;
                </button>
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
};