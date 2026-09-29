// components/ComparisonCard.tsx
"use client";
import React, { useState, useMemo } from 'react';
import { Card } from '@/components/ui/Common';
import { Radar, RadarChart, PolarGrid, PolarAngleAxis, PolarRadiusAxis, ResponsiveContainer } from 'recharts';
import { DeveloperStat } from '@/types/developer';
import { ProjectStat } from '@/types/project';
import { useTranslations } from 'next-intl';
import { GROUP_UI_ENABLED } from '@/lib/feature-flags';
import type { MetricWeight } from '@/lib/api/metric-weights';

type ComparableEntity = {
    name: string;
    spaceMetrics: { subject: string, value: number }[];
};

const getDeveloperDisplayName = (stat: DeveloperStat) =>
    stat.member.userName?.trim() || stat.member.userCode;

const ComparisonSelector = ({ label, selected, onChange, options }: { label: string, selected: string, onChange: (e: React.ChangeEvent<HTMLSelectElement>) => void, options: { value: string, label: string }[] }) => (
    <div className="flex-1">
        <label className="block text-xs text-slate-500 mb-1">{label}</label>
        <select
            value={selected}
            onChange={onChange}
            className="w-full px-3 py-2 text-sm bg-white border border-slate-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500"
        >
            {options.map(opt => <option key={opt.value} value={opt.value}>{opt.label}</option>)}
        </select>
    </div>
);

const generateChartData = (entity1: ComparableEntity, entity2: ComparableEntity) => {
    const dataMap = new Map<string, { subject: string, A?: number, B?: number }>();
    entity1.spaceMetrics.forEach(metric => {
        dataMap.set(metric.subject, { subject: metric.subject, A: metric.value });
    });
    entity2.spaceMetrics.forEach(metric => {
        const existing = dataMap.get(metric.subject) || { subject: metric.subject };
        existing.B = metric.value;
        dataMap.set(metric.subject, existing);
    });
    return Array.from(dataMap.values());
};

type ScoredEntity = Pick<DeveloperStat,
    'performanceScore' | 'activityScore' | 'communicationScore' | 'efficiencyScore' | 'satisfactionScore'>;

export const ComparisonCard = ({ developerStats, projectStats, metricConfigs }: { developerStats: DeveloperStat[], projectStats: ProjectStat | null, metricConfigs: MetricWeight[] }) => {
    const t = useTranslations('Comparison');
    const tSpace = useTranslations('Space');
    const [entity1Id, setEntity1Id] = useState('proj-current');
    const [entity2Id, setEntity2Id] = useState('none');

    const activeDimensions = useMemo(() => {
        return new Set(
            metricConfigs
                .filter(g => g.active && !g.parentKey)
                .map(g => g.metricKey)
        );
    }, [metricConfigs]);

    const mapToSpaceMetrics = (data: ScoredEntity) => {
        const allDimensions = [
            { key: 'performance', subject: tSpace('performance'), value: Math.round(data.performanceScore || 0) },
            { key: 'activity', subject: tSpace('activity'), value: Math.round(data.activityScore || 0) },
            { key: 'communication', subject: tSpace('communication'), value: Math.round(data.communicationScore || 0) },
            { key: 'efficiency', subject: tSpace('efficiency'), value: Math.round(data.efficiencyScore || 0) },
            { key: 'satisfaction', subject: tSpace('satisfaction'), value: Math.round(data.satisfactionScore || 0) },
        ];
        return allDimensions.filter(dim => activeDimensions.has(dim.key));
    };

    const teamStats = useMemo(() => {
        const groups = new Map<string, DeveloperStat[]>();
        developerStats.forEach(stat => {
            const groupName = stat.member.groupName || 'Other';
            if (!groups.has(groupName)) groups.set(groupName, []);
            groups.get(groupName)!.push(stat);
        });

        return Array.from(groups.entries()).map(([name, stats]) => {
            const count = stats.length;
            return {
                id: `team-${name}`,
                name,
                performanceScore: stats.reduce((acc, s) => acc + (s.performanceScore || 0), 0) / count,
                activityScore: stats.reduce((acc, s) => acc + (s.activityScore || 0), 0) / count,
                communicationScore: stats.reduce((acc, s) => acc + (s.communicationScore || 0), 0) / count,
                efficiencyScore: stats.reduce((acc, s) => acc + (s.efficiencyScore || 0), 0) / count,
                satisfactionScore: stats.reduce((acc, s) => acc + (s.satisfactionScore || 0), 0) / count,
            };
        });
    }, [developerStats]);

    const findEntityData = (entityId: string): ComparableEntity | null => {
        if (entityId === 'none') return null;
        const [type, ...idParts] = entityId.split('-');
        const id = idParts.join('-');

        if (type === 'dev') {
            const devStat = developerStats.find(stat => stat.member.userCode === id);
            return devStat ? { name: getDeveloperDisplayName(devStat), spaceMetrics: mapToSpaceMetrics(devStat) } : null;
        }
        if (type === 'team') {
            const team = teamStats.find(t => t.name === id);
            return team ? { name: team.name, spaceMetrics: mapToSpaceMetrics(team) } : null;
        }
        if (type === 'proj') {
            return projectStats ? { name: projectStats.projectName, spaceMetrics: mapToSpaceMetrics(projectStats) } : null;
        }
        return null;
    }

    const entity1Data = findEntityData(entity1Id);
    const entity2Data = findEntityData(entity2Id);

    const chartData = useMemo(() => {
        if (entity1Data && entity2Data) {
            return generateChartData(entity1Data, entity2Data);
        }
        if (entity1Data) {
            return entity1Data.spaceMetrics.map(m => ({ subject: m.subject, A: m.value }));
        }
        return [];
    }, [entity1Data, entity2Data]);

    const projectOptions = projectStats ? [{ value: 'proj-current', label: `${t('projectPrefix')}: ${projectStats.projectName}` }] : [];
    const teamOptions = GROUP_UI_ENABLED
        ? teamStats.map(team => ({ value: `team-${team.name}`, label: `${t('groupPrefix')}: ${team.name}` }))
        : [];
    const devOptions = developerStats.map(stat => ({
        value: `dev-${stat.member.userCode}`,
        label: `${t('developerPrefix')}: ${getDeveloperDisplayName(stat)}(${stat.member.userCode})`,
    }));
    const allOptions = [{ value: 'none', label: t('select') }, ...projectOptions, ...teamOptions, ...devOptions];

    return (
        <Card className="p-6">
            <h3 className="font-semibold text-slate-700 mb-4">{t('title')}</h3>
            <div className="flex items-end gap-4">
                <ComparisonSelector
                    label={t('compare')}
                    selected={entity1Id}
                    onChange={e => setEntity1Id(e.target.value)}
                    options={allOptions}
                />

                <div className="text-slate-400 font-medium pt-6">vs.</div>

                <ComparisonSelector
                    label={t('with')}
                    selected={entity2Id}
                    onChange={e => setEntity2Id(e.target.value)}
                    options={allOptions}
                />
            </div>

            {entity1Data && (
                <div className="mt-8 pt-8 border-t border-slate-200">
                    <div className="p-8 grid grid-cols-1 md:grid-cols-3 gap-8 bg-white text-slate-800 rounded-xl border border-slate-200 shadow-sm">
                        {/* Entity 1 */}
                        <div className="flex flex-col items-center text-center">
                            <h3 className="text-lg font-semibold text-blue-600">{entity1Data.name}</h3>
                            <div className="mt-4 w-full">
                                {entity1Data.spaceMetrics.map((metric) => (
                                    <div key={metric.subject} className="flex justify-between py-1 border-b border-slate-50">
                                        <span className="text-sm text-slate-500">{metric.subject}</span>
                                        <span className="text-sm font-semibold">{metric.value}</span>
                                    </div>
                                ))}
                            </div>
                        </div>

                        {/* Radar Chart */}
                        <div className="col-span-1 flex flex-col items-center justify-center min-h-[300px]">
                            <ResponsiveContainer width="100%" height={300}>
                                <RadarChart cx="50%" cy="50%" outerRadius="80%" data={chartData}>
                                    <PolarGrid stroke="#e2e8f0" />
                                    <PolarAngleAxis dataKey="subject" stroke="#64748b" fontSize={12} />
                                    <PolarRadiusAxis angle={30} domain={[0, 100]} tick={false} axisLine={false} stroke="#e2e8f0" />
                                    <Radar name={entity1Data.name} dataKey="A" stroke="#3b82f6" fill="#3b82f6" fillOpacity={0.5} />
                                    {entity2Data && (
                                        <Radar name={entity2Data.name} dataKey="B" stroke="#10b981" fill="#10b981" fillOpacity={0.5} />
                                    )}
                                </RadarChart>
                            </ResponsiveContainer>
                            <div className="flex justify-center flex-wrap gap-4 mt-4 text-xs">
                                <div className="flex items-center space-x-2">
                                    <div className="w-3 h-3 rounded-full bg-blue-500"></div>
                                    <span className="font-medium">{entity1Data.name}</span>
                                </div>
                                {entity2Data && (
                                    <div className="flex items-center space-x-2">
                                        <div className="w-3 h-3 rounded-full bg-emerald-500"></div>
                                        <span className="font-medium">{entity2Data.name}</span>
                                    </div>
                                )}
                            </div>
                        </div>

                        {/* Entity 2 */}
                        <div className="flex flex-col items-center text-center">
                            {entity2Data ? (
                                <>
                                    <h3 className="text-lg font-semibold text-emerald-600">{entity2Data.name}</h3>
                                    <div className="mt-4 w-full">
                                        {entity2Data.spaceMetrics.map((metric) => (
                                            <div key={metric.subject} className="flex justify-between py-1 border-b border-slate-50">
                                                <span className="text-sm text-slate-500">{metric.subject}</span>
                                                <span className="text-sm font-semibold">{metric.value}</span>
                                            </div>
                                        ))}
                                    </div>
                                </>
                            ) : (
                                <div className="h-full flex flex-center items-center text-slate-300 italic text-sm">
                                    {t('selectToCompare')}
                                </div>
                            )}
                        </div>
                    </div>
                </div>
            )}
        </Card>
    );
};
