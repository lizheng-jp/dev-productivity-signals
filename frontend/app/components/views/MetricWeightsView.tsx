// frontend/app/components/views/MetricWeightsView.tsx
"use client";
import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { useTranslations } from 'next-intl';
import { getMetricWeights, updateMetricWeights, MetricWeight } from '@/lib/api/metric-weights';
import { Button } from '@/components/ui/button';
import { Save, Loader2, AlertTriangle, TrendingUp, TrendingDown, RotateCcw } from 'lucide-react';
import { spaceGroupsConfig } from '@/lib/space-config';
import { cn } from '@/components/ui/Common';

const Switch = ({ checked, onChange }: { checked: boolean, onChange: (checked: boolean) => void }) => (
    <button
        type="button"
        role="switch"
        aria-checked={checked}
        onClick={() => onChange(!checked)}
        className={cn(
            "relative inline-flex h-6 w-11 flex-shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2",
            checked ? "bg-blue-600" : "bg-slate-200"
        )}
    >
        <span
            aria-hidden="true"
            className={cn(
                "pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out",
                checked ? "translate-x-5" : "translate-x-0"
            )}
        />
    </button>
);

type MetricUpdateHandler = <K extends keyof MetricWeight>(field: K, value: MetricWeight[K]) => void;

const MetricItem = ({ metric, onUpdate }: { metric: MetricWeight, onUpdate: MetricUpdateHandler }) => {
    const tTable = useTranslations('Table');
    const tGeneral = useTranslations('General');
    const metricConfig = spaceGroupsConfig.flatMap(g => g.metrics as readonly { labelKey: string; key: string; unit: string | undefined }[]).find(m => m.key === metric.metricKey);
    const label = metricConfig ? tTable(metricConfig.labelKey as never) : metric.metricKey;

    return (
        <div className="bg-surface border border-slate-200 rounded-lg p-4 shadow-sm transition-colors hover:border-slate-300 space-y-4">
            <div className="flex items-center justify-between">
                <label htmlFor={`active-${metric.id}`} className="text-sm font-bold text-slate-700 truncate pr-2">{label}</label>
                <Switch
                    checked={metric.active}
                    onChange={(isChecked) => onUpdate('active', isChecked)}
                />
            </div>
            <div className="space-y-3">
                <label htmlFor={`weight-${metric.id}`} className="text-[10px] font-bold text-slate-500 uppercase">{tGeneral('weight')}</label>
                <div className="flex items-center gap-3">
                    <input
                        id={`weight-${metric.id}`}
                        type="range"
                        min="0"
                        max="100"
                        step="0.01"
                        value={metric.weight * 100}
                        onChange={(e) => onUpdate('weight', parseFloat(e.target.value) / 100)}
                        className="w-full h-2 bg-slate-200 rounded-lg appearance-none cursor-pointer"
                        disabled={!metric.active}
                    />
                    <input
                        type="number"
                        step="0.01"
                        min="0"
                        max="1"
                        value={metric.weight}
                        onChange={(e) => onUpdate('weight', parseFloat(e.target.value))}
                        className="text-sm font-mono bg-slate-100 rounded-md px-2 py-1 w-20 text-center border border-slate-200 focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
                        disabled={!metric.active}
                    />
                </div>
            </div>
            <div className="grid grid-cols-2 gap-2">
                <div>
                    <label className="text-[10px] font-bold text-slate-500 uppercase">{tGeneral('minThreshold')}</label>
                    <input
                        type="number"
                        value={metric.minThreshold ?? ''}
                        onChange={(e) => onUpdate('minThreshold', e.target.value === '' ? null : parseInt(e.target.value, 10))}
                        className="mt-1 block w-full rounded-md border-slate-300 shadow-sm focus:border-blue-300 focus:ring focus:ring-blue-200 focus:ring-opacity-50 text-sm"
                        disabled={!metric.active}
                    />
                </div>
                <div>
                    <label className="text-[10px] font-bold text-slate-500 uppercase">{tGeneral('maxThreshold')}</label>
                    <input
                        type="number"
                        value={metric.maxThreshold ?? ''}
                        onChange={(e) => onUpdate('maxThreshold', e.target.value === '' ? null : parseInt(e.target.value, 10))}
                        className="mt-1 block w-full rounded-md border-slate-300 shadow-sm focus:border-blue-300 focus:ring focus:ring-blue-200 focus:ring-opacity-50 text-sm"
                        disabled={!metric.active}
                    />
                </div>
            </div>
            <div className="text-xs text-center text-slate-500 pt-2 flex items-center justify-center gap-1">
                {metric.positiveMetric ? <TrendingUp className="w-4 h-4 text-green-500" /> : <TrendingDown className="w-4 h-4 text-red-500" />}
                <span>{metric.positiveMetric ? tGeneral('higherIsBetter') : tGeneral('lowerIsBetter')}</span>
            </div>
        </div>
    );
};


export const MetricWeightsView = ({ onSaveSuccess }: { onSaveSuccess: () => void }) => {
    const [weights, setWeights] = useState<MetricWeight[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [isSaving, setIsSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [hasChanges, setHasChanges] = useState(false);
    const [showSuccess, setShowSuccess] = useState(false);


    const tSpace = useTranslations('Space');
    const tGeneral = useTranslations('General');

    useEffect(() => {
        const fetchWeights = async () => {
            try {
                setIsLoading(true);
                const data = await getMetricWeights();
                setWeights(data);
                setError(null);
            } catch (e) {
                setError('loadingError');
                console.error(e);
            } finally {
                setIsLoading(false);
            }
        };
        fetchWeights();
    }, []);

    const handleResetGroup = useCallback((parentKey: string) => {
        setHasChanges(true);
        setError(null);
        setWeights(currentWeights => {
            const newWeights = currentWeights.map(weight => ({ ...weight }));
            const activeChildren = newWeights.filter(w => w.parentKey === parentKey && w.active);
            const activeCount = activeChildren.length;
            if (activeCount === 0) return currentWeights;

            const averageWeight = 1 / activeCount;
            activeChildren.forEach(child => {
                const index = newWeights.findIndex(w => w.id === child.id);
                if (index !== -1) newWeights[index].weight = averageWeight;
            });
            return newWeights;
        });
    }, []);

    const handleUpdate = useCallback(<K extends keyof MetricWeight,>(
        id: number,
        field: K,
        value: MetricWeight[K]
    ) => {
        setHasChanges(true);
        setError(null);
        setWeights(currentWeights => currentWeights.map(
            metric => metric.id === id ? { ...metric, [field]: value } : metric
        ));
    }, []);

    const handleSave = async () => {
        const parentMetrics = weights.filter(w => !w.parentKey && w.active);
        const parentSum = parentMetrics.reduce((sum, m) => sum + m.weight, 0);

        if (
            parentMetrics.length === 0
            || parentMetrics.some(metric => !Number.isFinite(metric.weight) || metric.weight < 0 || metric.weight > 1)
            || Math.abs(parentSum - 1.0) > 0.001
        ) {
            setError(tGeneral('weightSumError', { sum: Number.isFinite(parentSum) ? parentSum.toFixed(2) : '-' }));
            return;
        }

        for (const parent of parentMetrics) {
            const children = weights.filter(w => w.parentKey === parent.metricKey && w.active);
            if (children.length > 0) {
                const childrenSum = children.reduce((sum, m) => sum + m.weight, 0);
                if (
                    children.some(metric => !Number.isFinite(metric.weight) || metric.weight < 0 || metric.weight > 1)
                    || Math.abs(childrenSum - 1.0) > 0.001
                ) {
                    setError(tGeneral('dimensionWeightSumError', {
                        dimension: tSpace(parent.metricKey as never),
                        sum: Number.isFinite(childrenSum) ? childrenSum.toFixed(2) : '-'
                    }));
                    return;
                }
            }
        }

        try {
            setIsSaving(true);
            setError(null);
            await updateMetricWeights(weights);
            setHasChanges(false);
            setShowSuccess(true);
            onSaveSuccess(); // Notify parent of success
            setTimeout(() => setShowSuccess(false), 3000);
        } catch (e) {
            setError('savingError');
            console.error(e);
        } finally {
            setIsSaving(false);
        }
    };

    const groupedMetrics = useMemo(() => {
        const parentMetrics = weights.filter(w => !w.parentKey);
        return parentMetrics.map(parent => ({
            ...parent,
            children: weights.filter(w => w.parentKey === parent.metricKey)
        }));
    }, [weights]);


    if (isLoading) {
        return <div className="flex items-center justify-center h-64"><Loader2 className="w-8 h-8 animate-spin text-slate-400" /></div>;
    }

    if (error && !weights.length) {
        return (
            <div className="flex flex-col items-center justify-center h-64 bg-red-50 border border-red-200 rounded-lg p-8">
                <AlertTriangle className="w-10 h-10 text-red-500 mb-4" />
                <p className="text-red-700 font-semibold">{tGeneral('loadingError')}</p>
            </div>
        );
    }

    return (
        <div className="animate-in fade-in slide-in-from-bottom-4 duration-500 space-y-8">
            {spaceGroupsConfig.map(groupConfig => {
                const parentMetric = groupedMetrics.find(m => m.metricKey === groupConfig.nameKey);
                if (!parentMetric) return null;

                return (
                    <div key={parentMetric.id} className="space-y-6 p-6 bg-slate-50/50 rounded-xl border border-slate-200">
                        <div className="flex items-center gap-4 border-b border-slate-200 pb-4">
                            <div>
                                <h3 className="text-lg font-bold text-slate-800">{tSpace(groupConfig.nameKey as never)}</h3>
                                <p className="max-w-3xl text-xs leading-5 text-slate-500">{tSpace(groupConfig.descriptionKey as never)}</p>
                            </div>
                            <div className="ml-auto flex items-center gap-4 pl-4">
                                <div className="flex items-center gap-3 w-64">
                                    <input
                                        id={`weight-${parentMetric.id}`}
                                        type="range"
                                        min="0"
                                        max="100"
                                        step="0.01"
                                        value={parentMetric.weight * 100}
                                        onChange={(e) => handleUpdate(parentMetric.id, 'weight', parseFloat(e.target.value) / 100)}
                                        className="w-full h-2 bg-slate-200 rounded-lg appearance-none cursor-pointer"
                                        disabled={!parentMetric.active}
                                    />
                                    <input
                                        type="number"
                                        step="0.01"
                                        min="0"
                                        max="1"
                                        value={parentMetric.weight}
                                        onChange={(e) => handleUpdate(parentMetric.id, 'weight', parseFloat(e.target.value))}
                                        className="text-sm font-mono bg-surface border border-slate-200 rounded-md px-2 py-1 w-20 text-center focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
                                        disabled={!parentMetric.active}
                                    />
                                    <button type="button" onClick={() => handleResetGroup(parentMetric.metricKey)} className="p-1.5 text-slate-400 hover:bg-slate-200 rounded-md" title={tGeneral('reset')}>
                                        <RotateCcw className="w-4 h-4" />
                                    </button>
                                </div>
                                <Switch
                                    checked={parentMetric.active}
                                    onChange={(isChecked) => handleUpdate(parentMetric.id, 'active', isChecked)}
                                />
                            </div>
                        </div>

                        {parentMetric.children.length > 0 ? (
                            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                                {parentMetric.children.map(metric => (
                                    <MetricItem
                                        key={metric.id}
                                        metric={metric}
                                        onUpdate={(field, value) => handleUpdate(metric.id, field, value)}
                                    />
                                ))}
                            </div>
                        ) : (
                            <div className="text-sm text-slate-500 italic text-center py-4">{tSpace('noSubMetrics')}</div>
                        )}
                    </div>
                );
            })}

            <div className="pt-6 flex justify-end items-center gap-4">
                {showSuccess && !isSaving && <p className="text-sm text-green-600 font-semibold animate-in fade-in">{tGeneral('savedSuccessfully')}</p>}
                {error && (
                    <p className="text-sm text-red-600">
                        {error === 'savingError' ? tGeneral('savingError') : error}
                    </p>
                )}
                <Button onClick={handleSave} disabled={isSaving || !hasChanges}>
                    {isSaving ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <Save className="mr-2 h-4 w-4" />}
                    {tGeneral('saveWeights')}
                </Button>
            </div>
        </div>
    );
};
