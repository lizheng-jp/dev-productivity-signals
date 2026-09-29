"use client";

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import {
    RadarChart,
    PolarGrid,
    PolarAngleAxis,
    PolarRadiusAxis,
    ResponsiveContainer,
    Radar,
    Tooltip,
    type TooltipContentProps,
} from 'recharts';
import { CircleHelp, Loader2 } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Card, Badge, cn } from '@/components/ui/Common';
import type { Project } from '@/lib/api/projects';
import type { MetricWeight } from '@/lib/api/metric-weights';
import { getSpaceMetrics, type SpaceMetricsResponse } from '@/lib/api/space-metrics';
import { AiAnalysisProgress, ComparisonAnalysisResponse, getAiAnalysisProgress, getComparisonAnalysis } from '@/lib/api/ai';
import { useComparisonSide } from '@/lib/hooks/useComparisonSide';
import { spaceGroupsConfig } from '@/lib/space-config';
import { EntityControl } from '@/components/views/ComparisonControls';
import { AiEvaluationSection } from '@/components/views/AiEvaluationSection';
import { formatLocalDate } from '@/lib/date-format';
import { useMainLayout } from '@/contexts/MainLayoutContext';
import type { ProjectMember } from '@/lib/api/members';
import { formatAiEvaluationText } from '@/lib/ai-evaluation-display';
import { GROUP_UI_ENABLED } from '@/lib/feature-flags';

type EntityKind = 'proj' | 'dev';

type ComparableEntity = {
    id: string;
    name: string;
    description?: string;
    type: EntityKind;
    rawStats: SpaceMetricsResponse;
};

type EntityOptions = {
    projOptions: { value: string; label: string }[];
    teamOptions: { value: string; label: string }[];
    devOptions: { value: string; label: string }[];
};

type ComparisonSideState = ReturnType<typeof useComparisonSide>;
type SpaceTranslationKey = 'performance' | 'activity' | 'communication' | 'efficiency' | 'satisfaction';
type ComparisonTranslation = (key: string, values?: Record<string, string | number>) => string;

const formatDate = (date?: Date) => date ? date.toLocaleDateString() : '-';
const parseLocalDate = (value: string) => new Date(`${value}T00:00:00`);

const getUserNameFromEntityId = (entityId: string) => {
    if (!entityId.startsWith('dev-')) return undefined;
    return entityId.slice(4);
};

const getMemberDisplayName = (member: { userName?: string | null; userCode?: string | null }) =>
    member.userName?.trim() || member.userCode?.trim() || 'Unknown';

const getMemberCode = (member: { userCode?: string | null }) => member.userCode?.trim() || '';

const getAiCorrectedTotalScore = (entity: ComparableEntity | null) => {
    const aiCorrected = entity?.rawStats?.aiCorrected;
    if (!aiCorrected || typeof aiCorrected !== 'object') return null;
    const score = (aiCorrected as SpaceMetricsResponse).spaceTotalScore;
    return typeof score === 'number' ? score : null;
};

const getRawTotalScore = (entity: ComparableEntity | null) => {
    const rawScore = Number(entity?.rawStats?.spaceTotalScore ?? NaN);
    return Number.isFinite(rawScore) ? rawScore : null;
};

const getDisplayTotalScore = (entity: ComparableEntity | null, aiEnabled: boolean) => {
    const rawScore = getRawTotalScore(entity);
    const correctedScore = aiEnabled ? getAiCorrectedTotalScore(entity) : null;
    return correctedScore ?? rawScore;
};

const TotalScoreValue = ({
    entity,
    aiEnabled,
    isAiLoading,
    t,
}: {
    entity: ComparableEntity | null;
    aiEnabled: boolean;
    isAiLoading: boolean;
    t: ComparisonTranslation;
}) => {
    const rawScore = getRawTotalScore(entity);
    const correctedScore = aiEnabled ? getAiCorrectedTotalScore(entity) : null;
    const displayScore = correctedScore ?? rawScore;

    if (displayScore == null) return <span className="text-slate-300">-</span>;

    const hasAiCorrection = correctedScore != null && rawScore != null;
    const delta = hasAiCorrection ? correctedScore - rawScore : 0;
    const sign = delta > 0 ? '+' : '';

    return (
        <div className="flex flex-col gap-1">
            <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
                <span className="text-2xl font-black">{displayScore.toFixed(1)}</span>
                <span className="rounded bg-white/15 px-1.5 py-0.5 text-[10px] font-bold text-blue-50">
                    {hasAiCorrection ? t('aiCorrectedScore') : t('rawScore')}
                </span>
                {hasAiCorrection && entity?.rawStats.aiCorrection?.reason && (
                    <HelpTooltip text={formatAiEvaluationText(entity.rawStats.aiCorrection.reason)} />
                )}
                {aiEnabled && isAiLoading && !hasAiCorrection && (
                    <span className="inline-flex items-center gap-1 rounded bg-white/15 px-1.5 py-0.5 text-[10px] font-bold text-blue-50">
                        <Loader2 className="h-3 w-3 animate-spin" />
                        AI補正中
                    </span>
                )}
            </div>
            {hasAiCorrection && (
                <>
                    <span className="text-xs font-semibold text-blue-50">
                        {t('rawScore')}: {rawScore.toFixed(1)} / {t('aiCorrectionDelta', { delta: `${sign}${delta.toFixed(1)}` })}
                    </span>
                </>
            )}
        </div>
    );
};

const HelpTooltip = ({ text }: { text: string }) => {
    const triggerRef = useRef<HTMLButtonElement>(null);
    const [isOpen, setIsOpen] = useState(false);
    const [position, setPosition] = useState({ left: 0, top: 0, width: 256 });

    const updatePosition = useCallback(() => {
        const trigger = triggerRef.current;
        if (!trigger) return;

        const viewportMargin = 12;
        const gap = 8;
        const preferredWidth = 256;
        const width = Math.min(preferredWidth, window.innerWidth - viewportMargin * 2);
        const triggerRect = trigger.getBoundingClientRect();
        const centeredLeft = triggerRect.left + triggerRect.width / 2 - width / 2;
        const left = Math.min(
            window.innerWidth - width - viewportMargin,
            Math.max(viewportMargin, centeredLeft)
        );

        setPosition({
            left,
            top: triggerRect.bottom + gap,
            width,
        });
    }, []);

    const openTooltip = () => {
        updatePosition();
        setIsOpen(true);
    };

    useEffect(() => {
        if (!isOpen) return;

        window.addEventListener('resize', updatePosition);
        // capture=true also observes scrolling inside the metric list container.
        window.addEventListener('scroll', updatePosition, true);
        return () => {
            window.removeEventListener('resize', updatePosition);
            window.removeEventListener('scroll', updatePosition, true);
        };
    }, [isOpen, updatePosition]);

    return (
        <span
            className="inline-flex"
            onMouseEnter={openTooltip}
            onMouseLeave={() => setIsOpen(false)}
        >
            <button
                ref={triggerRef}
                type="button"
                aria-label={text}
                onFocus={openTooltip}
                onBlur={() => setIsOpen(false)}
                className="inline-flex h-4 w-4 items-center justify-center rounded-full text-slate-400 transition hover:bg-slate-100 hover:text-slate-700 focus:bg-slate-100 focus:text-slate-700 focus:outline-none"
            >
                <CircleHelp className="h-3.5 w-3.5" />
            </button>
            {isOpen && typeof document !== 'undefined' && createPortal(
                <span
                    role="tooltip"
                    className="pointer-events-none fixed z-[100] max-h-[min(20rem,calc(100vh-2rem))] overflow-y-auto whitespace-pre-line rounded-md border border-slate-200 bg-white p-3 text-[11px] font-medium normal-case leading-5 text-slate-600 shadow-lg"
                    style={{
                        left: position.left,
                        top: position.top,
                        width: position.width,
                    }}
                >
                    {text}
                </span>,
                document.body
            )}
        </span>
    );
};

const RadarScoreTooltip = ({
    active,
    payload,
    label,
    scoreLabel,
}: Pick<
    TooltipContentProps<
        number | string | ReadonlyArray<number | string>,
        number | string
    >,
    'active' | 'payload' | 'label'
> & { scoreLabel: string }) => {
    if (!active || payload.length === 0) return null;

    return (
        <div className="min-w-40 rounded-lg border border-slate-200 bg-white px-3 py-2.5 text-xs shadow-lg">
            <p className="mb-2 font-bold text-slate-700">{label}</p>
            <div className="space-y-1.5">
                {payload.map((entry) => {
                    const numericValue = typeof entry.value === 'number'
                        ? entry.value
                        : Number(entry.value);
                    return (
                        <div
                            key={String(entry.dataKey ?? entry.name)}
                            className="flex items-center justify-between gap-4"
                        >
                            <span className="flex min-w-0 items-center gap-1.5 font-medium text-slate-600">
                                <span
                                    className="h-2.5 w-2.5 shrink-0 rounded-full"
                                    style={{ backgroundColor: entry.color }}
                                />
                                <span className="truncate">{entry.name}</span>
                            </span>
                            <span className="shrink-0 font-mono font-bold tabular-nums text-slate-800">
                                {scoreLabel}: {Number.isFinite(numericValue) ? Math.round(numericValue) : '-'}
                            </span>
                        </div>
                    );
                })}
            </div>
        </div>
    );
};

const MetricValue = ({
    entity,
    metricKey,
    unit,
    scoreLabel,
}: {
    entity: ComparableEntity | null;
    metricKey: string;
    unit?: string;
    scoreLabel: string;
}) => {
    const currentValue = entity?.rawStats?.[metricKey];
    const score = entity?.rawStats?.[`${metricKey}Score`];
    const formattedValue = typeof currentValue === 'number'
        ? currentValue.toFixed(metricKey === 'reviewCommentCount'
            || metricKey === 'mergedLeadTimeHours'
            || metricKey === 'contextSwitchFrequency'
            ? 2
            : (unit === 'h' ? 1 : 0))
        : currentValue == null ? null : String(currentValue);
    const formattedScore = typeof score === 'number' && Number.isFinite(score)
        ? score.toFixed(1)
        : null;

    return (
        <div className="flex min-w-0 flex-wrap items-center justify-between gap-x-2 gap-y-1">
            <span
                className={cn(
                    "min-w-0 max-w-full break-all text-base font-bold leading-tight tabular-nums",
                    formattedValue == null
                        ? "text-slate-300"
                        : entity?.type === 'dev' ? "text-blue-600" : "text-slate-800"
                )}
                title={formattedValue == null ? undefined : `${formattedValue}${unit ?? ''}`}
            >
                {formattedValue ?? '-'}
                {formattedValue != null && unit && (
                    <span className="ml-0.5 text-[10px] font-normal text-slate-400">{unit}</span>
                )}
            </span>
            <span
                className={cn(
                    "max-w-full rounded-md border px-1.5 py-0.5 text-[10px] font-bold leading-tight",
                    formattedScore == null
                        ? "border-slate-100 bg-slate-50 text-slate-300"
                        : "border-blue-100 bg-blue-50 text-blue-700"
                )}
            >
                {scoreLabel}: {formattedScore ?? '-'}
            </span>
        </div>
    );
};

const LoadingOverlay = ({ label }: { label: string }) => (
    <div className="absolute inset-0 z-20 flex items-center justify-center rounded-lg bg-white/75 backdrop-blur-[1px]">
        <div className="flex items-center gap-2 rounded-full border border-slate-200 bg-white px-4 py-2 text-sm font-semibold text-slate-600 shadow-sm">
            <Loader2 className="h-4 w-4 animate-spin text-blue-600" />
            <span>{label}</span>
        </div>
    </div>
);

export const ComparisonView = ({
    projects: propProjects,
    metricConfigs: propMetricConfigs,
}: {
    projects?: Project[];
    metricConfigs?: MetricWeight[];
} = {}) => {
    const t = useTranslations('Comparison');
    const tGeneral = useTranslations('General');
    const tSpace = useTranslations('Space');
    const tTable = useTranslations('Table');
    const tSettings = useTranslations('Settings');
    const {
        aiEnabled,
        setAiEnabled,
        projects: contextProjects,
        metricConfigs: contextMetricConfigs,
        comparisonSeed,
        fetchMetricConfigs,
    } = useMainLayout();
    const projects = propProjects ?? contextProjects;
    const metricConfigs = propMetricConfigs ?? contextMetricConfigs;
    const reusableSeed = comparisonSeed && projects.some(project => project.id.toString() === comparisonSeed.projectId)
        ? comparisonSeed
        : null;
    const initialProjectId = reusableSeed?.projectId || '';

    const sideA = useComparisonSide(
        initialProjectId,
        projects,
        reusableSeed ? `dev-${reusableSeed.userCode}` : 'none',
        reusableSeed ? {
            branch: reusableSeed.refName,
            date: {
                from: parseLocalDate(reusableSeed.since),
                to: parseLocalDate(reusableSeed.until),
            },
            members: [reusableSeed.member],
        } : undefined
    );
    const sideB = useComparisonSide('', projects, 'none');

    const [analysisA, setAnalysisA] = useState<ComparisonAnalysisResponse | null>(() => reusableSeed
        ? { spaceMetrics: reusableSeed.spaceMetrics, aiEvaluation: null }
        : null);
    const [analysisB, setAnalysisB] = useState<ComparisonAnalysisResponse | null>(null);
    const [isLoadingA, setIsLoadingA] = useState(false);
    const [isLoadingB, setIsLoadingB] = useState(false);
    const [errorA, setErrorA] = useState<string | null>(null);
    const [errorB, setErrorB] = useState<string | null>(null);
    const [isAiLoadingA, setIsAiLoadingA] = useState(false);
    const [isAiLoadingB, setIsAiLoadingB] = useState(false);
    const [aiErrorA, setAiErrorA] = useState<string | null>(null);
    const [aiErrorB, setAiErrorB] = useState<string | null>(null);
    const [progressA, setProgressA] = useState<AiAnalysisProgress | null>(null);
    const [progressB, setProgressB] = useState<AiAnalysisProgress | null>(null);
    const aiRunKeys = useRef({ A: '', B: '' });
    const metricConfigsRequested = useRef(false);
    const sideASeedActive = useRef(Boolean(reusableSeed));

    const makeEntityOptions = (side: ComparisonSideState, fallbackMember?: ProjectMember): EntityOptions => {
        const projectLabel = side.selectedProject?.name || t('projectPrefix');
        const members = [...side.members];
        if (fallbackMember && !members.some(member => getMemberCode(member) === getMemberCode(fallbackMember))) {
            members.push(fallbackMember);
        }
        return {
            projOptions: side.selectedProject
                ? [{ value: 'proj-current', label: `${projectLabel} ${t('allMembers')}` }]
                : [],
            teamOptions: [],
            devOptions: members
                .filter(member => getMemberCode(member))
                .sort((a, b) => getMemberDisplayName(a).localeCompare(getMemberDisplayName(b)))
                .map(member => {
                    const userCode = getMemberCode(member);
                    return {
                        value: `dev-${userCode}`,
                        label: `${getMemberDisplayName(member)} (${userCode})`,
                    };
                }),
        };
    };

    const resolveEntity = (
        side: ComparisonSideState,
        analysis: ComparisonAnalysisResponse | null,
        fallbackMember?: ProjectMember
    ): ComparableEntity | null => {
        if (!analysis || side.entityId === 'none') return null;

        if (side.entityId === 'proj-current') {
            return {
                id: side.projectId,
                name: side.selectedProject?.name || t('projectPrefix'),
                description: t('projectEntityDescription'),
                type: 'proj',
                rawStats: analysis.spaceMetrics,
            };
        }

        const userCode = getUserNameFromEntityId(side.entityId);
        const member = side.members.find(item => getMemberCode(item) === userCode)
            || (fallbackMember && getMemberCode(fallbackMember) === userCode ? fallbackMember : null);
        if (!userCode || !member) return null;

        return {
            id: userCode,
            name: getMemberDisplayName(member),
            description: GROUP_UI_ENABLED
                ? `${member.groupName || tGeneral('noGroup')} / ${userCode}`
                : userCode,
            type: 'dev',
            rawStats: analysis.spaceMetrics,
        };
    };

    const buildRequestParams = (side: ComparisonSideState) => ({
        projectId: side.projectId,
        since: side.date?.from ? formatLocalDate(side.date.from) : undefined,
        until: side.date?.to ? formatLocalDate(side.date.to) : undefined,
        userName: getUserNameFromEntityId(side.entityId),
        refName: side.branch || undefined,
    });

    const buildAiRunKey = (side: ComparisonSideState, snapshotId?: string) => {
        const params = buildRequestParams(side);
        return `${params.projectId}|${params.since || ''}|${params.until || ''}|${params.userName || ''}|${params.refName || ''}|${snapshotId || ''}`;
    };

    const fetchSpaceMetricsOnly = (
        side: ComparisonSideState,
        setAnalysis: (value: ComparisonAnalysisResponse | null) => void,
        setLoading: (value: boolean) => void,
        setError: (value: string | null) => void,
        setAiError: (value: string | null) => void,
        setProgress: React.Dispatch<React.SetStateAction<AiAnalysisProgress | null>>
    ) => {
        if (!side.projectId || side.entityId === 'none' || !side.date?.from || !side.date?.to) {
            setAnalysis(null);
            setLoading(false);
            setError(null);
            setAiError(null);
            setProgress(null);
            return () => {};
        }

        const params = buildRequestParams(side);
        if (!params.since || !params.until) {
            return () => {};
        }

        let ignore = false;
        setLoading(true);
        setError(null);
        setAiError(null);
        setAnalysis(null);
        setProgress(null);

        getSpaceMetrics(side.projectId, {
            since: params.since,
            until: params.until,
            userName: params.userName,
            refName: params.refName,
            aiEnabled: false,
        })
            .then(spaceMetrics => {
                if (ignore) return;
                setAnalysis({ spaceMetrics, aiEvaluation: null });
            })
            .catch(error => {
                if (ignore) return;
                console.error(error);
                setError('Failed to load SPACE metrics');
                setAnalysis(null);
            })
            .finally(() => {
                if (!ignore) setLoading(false);
            });

        return () => {
            ignore = true;
        };
    };

    const generateAiEvaluation = (
        side: ComparisonSideState,
        setAnalysis: (updater: (value: ComparisonAnalysisResponse | null) => ComparisonAnalysisResponse | null) => void,
        setAiLoading: (value: boolean) => void,
        setAiError: (value: string | null) => void,
        setProgress: React.Dispatch<React.SetStateAction<AiAnalysisProgress | null>>,
        snapshotId?: string
    ) => {
        if (!side.projectId || side.entityId === 'none' || !side.date?.from || !side.date?.to) {
            return () => {};
        }

        const params = { ...buildRequestParams(side), aiEnabled: true, snapshotId };
        if (!params.since || !params.until) {
            return () => {};
        }

        let ignore = false;
        setAiLoading(true);
        setAiError(null);
        setProgress(null);

        const pollProgress = () => {
            getAiAnalysisProgress(params)
                .then(progress => {
                    if (ignore) return;
                    setProgress(progress);
                })
                .catch(error => {
                    console.warn('Failed to load AI analysis progress', error);
                });
        };

        pollProgress();
        const progressTimer = setInterval(pollProgress, 1000);

        getComparisonAnalysis(params)
            .then(result => {
                if (ignore) return;
                setAnalysis(() => result);
                setProgress(previous => previous && previous.total > 0
                    ? { ...previous, current: previous.total, processed: previous.total, status: 'completed' }
                    : previous);
            })
            .catch(error => {
                if (ignore) return;
                console.error(error);
                setAiError('Failed to generate AI evaluation');
            })
            .finally(() => {
                clearInterval(progressTimer);
                if (!ignore) setAiLoading(false);
            });

        return () => {
            ignore = true;
            clearInterval(progressTimer);
        };
    };

    useEffect(() => {
        const matchesSeed = reusableSeed
            && sideA.projectId === reusableSeed.projectId
            && sideA.entityId === `dev-${reusableSeed.userCode}`
            && (sideA.branch || '') === (reusableSeed.refName || '')
            && formatLocalDate(sideA.date?.from) === reusableSeed.since
            && formatLocalDate(sideA.date?.to) === reusableSeed.until;

        if (sideASeedActive.current && matchesSeed) {
            setIsLoadingA(false);
            setErrorA(null);
            setAiErrorA(null);
            setProgressA(null);
            return;
        }

        sideASeedActive.current = false;
        return fetchSpaceMetricsOnly(sideA, setAnalysisA, setIsLoadingA, setErrorA, setAiErrorA, setProgressA);
    }, [
        sideA.projectId,
        sideA.entityId,
        sideA.branch,
        sideA.date?.from,
        sideA.date?.to,
        reusableSeed,
    ]);

    useEffect(() => fetchSpaceMetricsOnly(sideB, setAnalysisB, setIsLoadingB, setErrorB, setAiErrorB, setProgressB), [
        sideB.projectId,
        sideB.entityId,
        sideB.branch,
        sideB.date?.from,
        sideB.date?.to,
    ]);

    useEffect(() => {
        if (propMetricConfigs || metricConfigs.length > 0) return;
        if (metricConfigsRequested.current) return;

        metricConfigsRequested.current = true;
        void fetchMetricConfigs();
    }, [propMetricConfigs, metricConfigs.length, fetchMetricConfigs]);

    useEffect(() => {
        if (!aiEnabled || !analysisA || analysisA.aiEvaluation) {
            if (!aiEnabled) {
                aiRunKeys.current.A = '';
                Promise.resolve().then(() => setIsAiLoadingA(false));
            }
            return;
        }

        const snapshotId = analysisA.spaceMetrics.snapshotId;
        const runKey = buildAiRunKey(sideA, snapshotId);
        if (aiRunKeys.current.A === runKey) return;
        aiRunKeys.current.A = runKey;

        return generateAiEvaluation(sideA, setAnalysisA, setIsAiLoadingA, setAiErrorA, setProgressA, snapshotId);
    }, [
        aiEnabled,
        analysisA,
        sideA.projectId,
        sideA.entityId,
        sideA.branch,
        sideA.date?.from,
        sideA.date?.to,
    ]);

    useEffect(() => {
        if (!aiEnabled || !analysisB || analysisB.aiEvaluation || getAiCorrectedTotalScore(resolveEntity(sideB, analysisB)) != null) {
            if (!aiEnabled) {
                aiRunKeys.current.B = '';
                Promise.resolve().then(() => setIsAiLoadingB(false));
            }
            return;
        }

        const snapshotId = analysisB.spaceMetrics.snapshotId;
        const runKey = buildAiRunKey(sideB, snapshotId);
        if (aiRunKeys.current.B === runKey) return;
        aiRunKeys.current.B = runKey;

        return generateAiEvaluation(sideB, setAnalysisB, setIsAiLoadingB, setAiErrorB, setProgressB, snapshotId);
    }, [
        aiEnabled,
        analysisB,
        sideB.projectId,
        sideB.entityId,
        sideB.branch,
        sideB.date?.from,
        sideB.date?.to,
    ]);

    const entity1Data = resolveEntity(sideA, analysisA, reusableSeed?.member);
    const entity2Data = resolveEntity(sideB, analysisB);

    const getAiSelectionItems = (side: ComparisonSideState, entity: ComparableEntity | null) => [
        { label: t('projectPrefix'), value: side.selectedProject?.name || '-' },
        { label: t('branch'), value: side.branch || t('allBranches') },
        {
            label: t('dateRange'),
            value: side.date?.from && side.date?.to
                ? `${formatLocalDate(side.date.from)} - ${formatLocalDate(side.date.to)}`
                : '-',
        },
        { label: t('target'), value: entity?.name || '-' },
    ];
    const selectionItemsA = getAiSelectionItems(sideA, entity1Data);
    const selectionItemsB = getAiSelectionItems(sideB, entity2Data);

    const activeMetricsByGroup = useMemo(() => {
        if (metricConfigs.length === 0) {
            return {
                activeMetrics: new Set(spaceGroupsConfig.flatMap(group => group.metrics.map(metric => metric.key))),
                activeGroups: new Set(spaceGroupsConfig.map(group => group.scoreKey.replace('Score', ''))),
            };
        }

        const activeMetrics = new Set(
            metricConfigs
                .filter(metric => metric.active && metric.parentKey)
                .map(metric => metric.metricKey)
        );
        const activeGroups = new Set(
            metricConfigs
                .filter(metric => metric.active && !metric.parentKey)
                .map(metric => metric.metricKey)
        );

        return { activeMetrics, activeGroups };
    }, [metricConfigs]);

    const spaceGroups = useMemo(() => spaceGroupsConfig.map(group => ({
        name: tSpace(group.nameKey as SpaceTranslationKey),
        description: tSpace(group.descriptionKey as never),
        scoreKey: group.scoreKey,
        metricKey: group.scoreKey.replace('Score', ''),
        color: group.color,
        metrics: group.metrics.map(metric => ({
            label: tTable(metric.labelKey as never),
            description: t(`metricDescriptions.${metric.key}` as never),
            key: metric.key,
            unit: metric.unit,
        })),
    })), [t, tSpace, tTable]);

    const chartData = spaceGroups.map(group => ({
        subject: group.name,
        A: Number(entity1Data?.rawStats?.[group.scoreKey] ?? 0),
        B: Number(entity2Data?.rawStats?.[group.scoreKey] ?? 0),
    }));

    const visibleSpaceGroups = (
        spaceGroups.filter(group => activeMetricsByGroup.activeGroups.has(group.metricKey))
    );

    const isComparisonLoading = isLoadingA || isLoadingB;
    const spaceError = errorA || errorB;

    const renderEntityCaption = (side: ComparisonSideState, entity: ComparableEntity | null, color: 'blue' | 'green', isAiLoading: boolean) => {
        if (!entity) return null;
        const hasAiTotalScore = aiEnabled && getAiCorrectedTotalScore(entity) != null;
        return (
            <div className="flex flex-col items-center">
                <div className="flex items-center gap-2 text-center">
                    <div className={cn("w-3 h-3 rounded", color === 'blue' ? "bg-blue-500" : "bg-emerald-500")} />
                    <span className="text-sm font-bold text-slate-700">{entity.name}</span>
                    <Badge color={color}>{Number(getDisplayTotalScore(entity, aiEnabled) ?? 0).toFixed(1)}</Badge>
                    {aiEnabled && isAiLoading && !hasAiTotalScore && <Loader2 className="h-3.5 w-3.5 animate-spin text-blue-500" />}
                </div>
                {(hasAiTotalScore || (aiEnabled && isAiLoading)) && (
                    <span className="text-[10px] font-semibold text-blue-500 mt-1">
                        {hasAiTotalScore ? t('aiCorrectedScore') : 'AI補正中'}
                    </span>
                )}
                <span className="text-[10px] text-slate-400 mt-1">{entity.description}</span>
                <span className="text-[10px] text-slate-400">
                    {formatDate(side.date?.from)} - {formatDate(side.date?.to)}
                </span>
            </div>
        );
    };

    return (
        <div className="space-y-6">
            <Card className="p-6 bg-slate-50/30">
                <div className="flex flex-col md:flex-row items-stretch gap-6 mb-8">
                    <EntityControl
                        label="Side A"
                        projects={projects}
                        selectedProjectId={sideA.projectId}
                        onProjectChange={sideA.setProjectId}
                        date={sideA.date}
                        setDate={sideA.setDate}
                        entityId={sideA.entityId}
                        onEntityChange={sideA.setEntityId}
                        entityOptions={makeEntityOptions(sideA, reusableSeed?.member)}
                        branches={sideA.branches}
                        selectedBranch={sideA.branch}
                        onBranchChange={sideA.handleBranchChange}
                        isLoadingOptions={sideA.isLoadingOptions}
                        t={t}
                    />

                    <div className="flex items-center justify-center">
                        <div className="bg-white px-3 py-1 border border-slate-200 rounded-full text-slate-400 font-bold text-xs shadow-sm">VS</div>
                    </div>

                    <EntityControl
                        label="Side B"
                        projects={projects}
                        selectedProjectId={sideB.projectId}
                        onProjectChange={sideB.setProjectId}
                        date={sideB.date}
                        setDate={sideB.setDate}
                        entityId={sideB.entityId}
                        onEntityChange={sideB.setEntityId}
                        entityOptions={makeEntityOptions(sideB)}
                        branches={sideB.branches}
                        selectedBranch={sideB.branch}
                        onBranchChange={sideB.handleBranchChange}
                        isLoadingOptions={sideB.isLoadingOptions}
                        t={t}
                    />
                </div>

                <div
                    className="relative grid grid-cols-1 lg:grid-cols-3 gap-8 bg-white p-6 rounded-lg shadow-sm border border-slate-200"
                    aria-busy={isComparisonLoading}
                >
                    {isComparisonLoading && <LoadingOverlay label={tTable('loading')} />}
                    {spaceError && (
                        <div className="absolute left-6 right-6 top-6 z-20 rounded-lg border border-rose-100 bg-rose-50 px-4 py-3 text-sm font-semibold text-rose-700">
                            {spaceError}
                        </div>
                    )}
                    <div className="lg:col-span-2 bg-slate-50 rounded-lg p-6 border border-slate-100 min-h-[400px] flex flex-col items-center justify-center">
                        <h4 className="text-sm font-bold text-slate-500 uppercase mb-6 tracking-wider">{t('radarTitle')}</h4>
                        <ResponsiveContainer width="100%" height={350}>
                            <RadarChart cx="50%" cy="50%" outerRadius="80%" data={chartData}>
                                <PolarGrid stroke="#e2e8f0" />
                                <PolarAngleAxis dataKey="subject" stroke="#64748b" fontSize={12} fontWeight={500} />
                                <PolarRadiusAxis angle={30} domain={[0, 100]} tick={false} axisLine={false} stroke="#e2e8f0" />
                                <Tooltip
                                    cursor={false}
                                    content={(props) => (
                                        <RadarScoreTooltip {...props} scoreLabel={tTable('score')} />
                                    )}
                                />
                                {entity1Data && (
                                    <Radar
                                        name={entity1Data.name}
                                        dataKey="A"
                                        stroke="#3b82f6"
                                        fill="#3b82f6"
                                        fillOpacity={0.35}
                                        strokeWidth={2}
                                        dot={{ r: 4, fill: '#ffffff', strokeWidth: 2 }}
                                        activeDot={{ r: 6, fill: '#ffffff', strokeWidth: 3 }}
                                    />
                                )}
                                {entity2Data && (
                                    <Radar
                                        name={entity2Data.name}
                                        dataKey="B"
                                        stroke="#10b981"
                                        fill="#10b981"
                                        fillOpacity={0.35}
                                        strokeWidth={2}
                                        dot={{ r: 4, fill: '#ffffff', strokeWidth: 2 }}
                                        activeDot={{ r: 6, fill: '#ffffff', strokeWidth: 3 }}
                                    />
                                )}
                            </RadarChart>
                        </ResponsiveContainer>

                        <div className="flex justify-center flex-wrap gap-x-8 gap-y-2 mt-4">
                            {renderEntityCaption(sideA, entity1Data, 'blue', isAiLoadingA)}
                            {renderEntityCaption(sideB, entity2Data, 'green', isAiLoadingB)}
                        </div>
                    </div>

                    <div className="space-y-4">
                        <div className="flex items-center justify-between gap-3 mb-2">
                            <h4 className="text-sm font-bold text-slate-500 uppercase tracking-wider">{t('metricsBreakdown')}</h4>
                            <label htmlFor="comparison-ai-enabled" className="inline-flex items-center gap-2">
                                <span className="text-xs font-bold text-slate-500">{tSettings('aiMode')}</span>
                                <HelpTooltip text={tSettings('aiModeDescription')} />
                                <span className="relative inline-flex h-6 w-11 shrink-0 cursor-pointer items-center">
                                    <input
                                        type="checkbox"
                                        id="comparison-ai-enabled"
                                        className="peer sr-only"
                                        checked={aiEnabled}
                                        onChange={(event) => setAiEnabled(event.target.checked)}
                                    />
                                    <span className="absolute inset-0 rounded-full bg-slate-300 transition-colors peer-checked:bg-blue-600 peer-focus-visible:ring-4 peer-focus-visible:ring-blue-500/20" />
                                    <span className="absolute left-0.5 top-0.5 h-5 w-5 rounded-full bg-white shadow-sm transition-transform peer-checked:translate-x-5" />
                                </span>
                            </label>
                        </div>

                        <section className="grid grid-cols-2 rounded-lg bg-slate-50 ring-1 ring-inset ring-slate-100" aria-label={t('selectedConditions')}>
                            {[
                                { label: 'Side A', items: selectionItemsA, tone: 'blue' },
                                { label: 'Side B', items: selectionItemsB, tone: 'emerald' },
                            ].map(side => (
                                <div key={side.label} className="min-w-0 px-3 py-2.5 [&+&]:border-l [&+&]:border-slate-200">
                                    <div className="flex items-center">
                                        <span className={cn(
                                            'shrink-0 rounded px-1.5 py-0.5 text-[10px] font-bold',
                                            side.tone === 'blue' ? 'bg-blue-100 text-blue-700' : 'bg-emerald-100 text-emerald-700'
                                        )}>{side.label}</span>
                                    </div>
                                    <dl className="mt-1.5 space-y-0.5 text-[11px] leading-4">
                                        {side.items.map(item => (
                                            <div key={item.label} className="grid min-w-0 grid-cols-[4.5rem_minmax(0,1fr)] gap-2">
                                                <dt className="text-slate-400">{item.label}</dt>
                                                <dd className="break-words font-medium text-slate-600">{item.value}</dd>
                                            </div>
                                        ))}
                                    </dl>
                                </div>
                            ))}
                        </section>

                        <div className="bg-blue-600 rounded-lg p-4 shadow-md text-white">
                            <div className="mb-2 flex items-center gap-1.5 text-xs font-bold uppercase opacity-80">
                                <span>{tTable('score')}</span>
                                <HelpTooltip text={t('metricDescriptions.spaceTotalScore')} />
                            </div>
                            <div className="grid grid-cols-2 gap-4 items-end">
                                <div className="flex flex-col gap-1">
                                    <TotalScoreValue entity={entity1Data} aiEnabled={aiEnabled} isAiLoading={isAiLoadingA} t={t} />
                                </div>
                                <div className="border-l border-blue-400 pl-4">
                                    <div className="flex flex-col gap-1">
                                        <TotalScoreValue entity={entity2Data} aiEnabled={aiEnabled} isAiLoading={isAiLoadingB} t={t} />
                                    </div>
                                </div>
                            </div>
                        </div>

                        <div className="max-h-[500px] overflow-y-auto pr-2 space-y-6 pt-2">
                            {visibleSpaceGroups.length === 0 ? (
                                <div className="py-8 text-center text-sm text-slate-400">{t('noMetrics')}</div>
                            ) : (
                                visibleSpaceGroups.map(group => (
                                    <div key={group.scoreKey} className="space-y-3">
                                        <div className="flex items-center gap-2 border-b border-slate-100 pb-1">
                                            <div className={cn("w-1 h-4 rounded-full",
                                                group.color === 'blue' ? "bg-blue-500" :
                                                    group.color === 'amber' ? "bg-amber-500" :
                                                        group.color === 'purple' ? "bg-purple-500" :
                                                            group.color === 'emerald' ? "bg-emerald-500" : "bg-rose-500"
                                            )} />
                                            <h5 className="text-[11px] font-black text-slate-400 uppercase tracking-widest">{group.name}</h5>
                                            <HelpTooltip text={group.description} />
                                            <div className="ml-auto flex gap-2">
                                                <Badge color="gray">{Number(entity1Data?.rawStats?.[group.scoreKey] ?? 0).toFixed(0)}</Badge>
                                                <Badge color="gray">{Number(entity2Data?.rawStats?.[group.scoreKey] ?? 0).toFixed(0)}</Badge>
                                            </div>
                                        </div>

                                        <div className="space-y-2">
                                            {group.metrics
                                                .filter(metric => activeMetricsByGroup.activeMetrics.has(metric.key))
                                                .map(metric => (
                                                    <div key={metric.key} className="bg-white border border-slate-200 rounded-lg p-3 shadow-sm transition-colors hover:border-slate-300">
                                                        <div className="mb-1.5 flex items-center gap-1.5 text-[10px] font-bold uppercase text-slate-500">
                                                            <span>{metric.label}</span>
                                                            <HelpTooltip text={metric.description} />
                                                        </div>
                                                        <div className="grid grid-cols-2 gap-4 items-center">
                                                            <MetricValue
                                                                entity={entity1Data}
                                                                metricKey={metric.key}
                                                                unit={metric.unit}
                                                                scoreLabel={tTable('score')}
                                                            />
                                                            <div className="border-l border-slate-100 pl-4">
                                                                <MetricValue
                                                                    entity={entity2Data}
                                                                    metricKey={metric.key}
                                                                    unit={metric.unit}
                                                                    scoreLabel={tTable('score')}
                                                                />
                                                            </div>
                                                        </div>
                                                    </div>
                                                ))}
                                            {group.metrics.filter(metric => activeMetricsByGroup.activeMetrics.has(metric.key)).length === 0 && (
                                                <div className="text-[10px] text-slate-400 italic text-center py-2">{t('noMetrics')}</div>
                                            )}
                                        </div>
                                    </div>
                                ))
                            )}
                        </div>
                    </div>
                </div>
            </Card>

            {aiEnabled && <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">
                {sideA.entityId !== 'none' && (
                    <AiEvaluationSection
                        entityName={entity1Data?.name || sideA.selectedProject?.name || 'Side A'}
                        selectionTitle={t('selectedConditions')}
                        selectionItems={selectionItemsA}
                        evaluation={analysisA?.aiEvaluation || null}
                        isLoading={isAiLoadingA}
                        error={aiErrorA}
                        progress={progressA}
                        isActionDisabled={isLoadingA || isAiLoadingA || !analysisA}
                    />
                )}
                {sideB.entityId !== 'none' && (
                    <AiEvaluationSection
                        entityName={entity2Data?.name || sideB.selectedProject?.name || 'Side B'}
                        selectionTitle={t('selectedConditions')}
                        selectionItems={selectionItemsB}
                        evaluation={analysisB?.aiEvaluation || null}
                        isLoading={isAiLoadingB}
                        error={aiErrorB}
                        progress={progressB}
                        isActionDisabled={isLoadingB || isAiLoadingB || !analysisB}
                    />
                )}
            </div>}
        </div>
    );
};
