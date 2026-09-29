"use client";
import React, { useState, useMemo } from 'react';
import { Project } from '@/lib/api/projects';
import { DatePickerWithRange } from '@/components/ui/date-range-picker';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { cn } from '@/components/ui/Common';
import { FolderKanban, Search, ChevronDown, Check, Loader2, GitBranch } from 'lucide-react';
import { GROUP_UI_ENABLED } from '@/lib/feature-flags';

type SelectorOption = {
    value: string;
    label: string;
};

type EntityOptions = {
    devOptions: SelectorOption[];
    teamOptions: SelectorOption[];
    projOptions: SelectorOption[];
};

type TranslationFn = (key: string) => string;

type EntityControlProps = {
    projects: Project[];
    selectedProjectId: string;
    onProjectChange: (id: string) => void;
    date: React.ComponentProps<typeof DatePickerWithRange>['date'];
    setDate: React.ComponentProps<typeof DatePickerWithRange>['setDate'];
    entityId: string;
    onEntityChange: (value: string) => void;
    entityOptions: EntityOptions;
    branches: { name: string }[];
    selectedBranch: string;
    onBranchChange: (value: string) => void;
    isLoadingOptions: boolean;
    label: string;
    t: TranslationFn;
};

const fieldClassName = "h-10 w-full rounded-lg border border-slate-200 bg-white px-3 text-sm font-medium text-slate-700 shadow-sm transition-colors hover:border-slate-300 hover:bg-slate-50 focus:border-blue-500 focus:bg-white focus:outline-none focus:ring-4 focus:ring-blue-500/10 disabled:cursor-not-allowed disabled:bg-slate-100 disabled:text-slate-400 disabled:shadow-none";
const labelClassName = "mb-1.5 ml-1 block text-[10px] font-bold uppercase tracking-wide text-slate-500";

const ComparisonSelector = ({ label, selected, onChange, options, disabled, isLoading, t }: { label: string, selected: string, onChange: (value: string) => void, options: EntityOptions, disabled?: boolean, isLoading?: boolean, t: TranslationFn }) => (
    <div className="min-w-0 flex-1">
        <label className={labelClassName}>{label}</label>
        <div className="relative">
            {isLoading ? (
                <div
                    className={cn(fieldClassName, "flex items-center gap-2 bg-slate-100 text-slate-500 shadow-none")}
                    aria-live="polite"
                >
                    <Loader2 className="h-4 w-4 flex-shrink-0 animate-spin text-blue-500" />
                    <span className="truncate">{t('activeDevelopersLoading')}</span>
                </div>
            ) : (
                <>
                    <select
                        value={selected}
                        onChange={(e) => onChange(e.target.value)}
                        disabled={disabled}
                        className={cn(fieldClassName, "appearance-none pr-9")}
                    >
                        <option value="none">{t('select')}</option>
                        {options.projOptions.length > 0 && (
                            <optgroup label={t('projectPrefix')}>
                                {options.projOptions.map(opt => <option key={opt.value} value={opt.value}>{opt.label}</option>)}
                            </optgroup>
                        )}
                        {GROUP_UI_ENABLED && options.teamOptions.length > 0 && (
                            <optgroup label={t('groupPrefix')}>
                                {options.teamOptions.map(opt => <option key={opt.value} value={opt.value}>{opt.label}</option>)}
                            </optgroup>
                        )}
                        {options.devOptions.length > 0 && (
                            <optgroup label={t('developerPrefix')}>
                                {options.devOptions.map(opt => <option key={opt.value} value={opt.value}>{opt.label}</option>)}
                            </optgroup>
                        )}
                    </select>
                    <ChevronDown className="pointer-events-none absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
                </>
            )}
        </div>
    </div>
);

const ProjectSearchSelector = ({ projects, selectedProjectId, onProjectChange, t }: { projects: Project[], selectedProjectId: string, onProjectChange: (id: string) => void, t: TranslationFn }) => {
    const [searchQuery, setSearchQuery] = useState('');
    const [isOpen, setIsOpen] = useState(false);

    const sortedProjects = useMemo(() => [...projects].sort((a, b) => a.name.localeCompare(b.name)), [projects]);
    const filteredProjects = useMemo(() => {
        if (!searchQuery) return sortedProjects;
        const query = searchQuery.toLowerCase();
        return sortedProjects.filter(p => p.name.toLowerCase().includes(query) || p.description?.toLowerCase().includes(query));
    }, [sortedProjects, searchQuery]);

    const selectedProject = projects.find(p => p.id.toString() === selectedProjectId);
    const sourceLabel = (provider?: string) => t(
        provider === 'mock' ? 'sourceDemo' : provider === 'github' ? 'sourceGitHub' : 'sourceGitLab'
    );

    return (
        <Popover open={isOpen} onOpenChange={setIsOpen}>
            <PopoverTrigger asChild>
                <button className={cn(fieldClassName, "flex items-center justify-between gap-3 text-left group")}>
                    <div className="flex min-w-0 items-center gap-2">
                        <FolderKanban className="w-4 h-4 text-blue-500 flex-shrink-0" />
                        <span className="truncate">{selectedProject?.name || t('selectProject')}</span>
                        {selectedProject && <span className="shrink-0 rounded border border-slate-200 bg-white px-1.5 py-0.5 text-[10px] font-medium text-slate-500">{sourceLabel(selectedProject.provider)}</span>}
                    </div>
                    <ChevronDown className={cn("w-4 h-4 text-slate-400 transition-transform", isOpen && "rotate-180")} />
                </button>
            </PopoverTrigger>
            <PopoverContent className="w-[var(--radix-popover-trigger-width)] min-w-72 p-0 bg-white" align="start">
                <div className="p-2 border-b border-slate-100 bg-slate-50/80">
                    <div className="relative">
                        <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-slate-400" />
                        <input
                            type="text"
                            placeholder={t('searchProjects')}
                            className="h-9 w-full rounded-md border border-slate-200 bg-white py-2 pl-9 pr-3 text-sm outline-none transition focus:border-blue-500 focus:ring-4 focus:ring-blue-500/10"
                            value={searchQuery}
                            onChange={(e) => setSearchQuery(e.target.value)}
                        />
                    </div>
                </div>
                <div className="max-h-60 overflow-y-auto p-1">
                    {filteredProjects.length === 0 ? (
                        <div className="py-6 text-center text-slate-500 text-sm">{t('noProjectsFound')}</div>
                    ) : (
                        filteredProjects.map((project) => (
                            <button
                                key={project.id}
                                onClick={() => {
                                    onProjectChange(project.id.toString());
                                    setIsOpen(false);
                                }}
                                className={cn(
                                    "w-full text-left px-3 py-2 rounded-md transition-colors flex items-center justify-between gap-2 group/item",
                                    selectedProjectId === project.id.toString() ? "bg-blue-50" : "hover:bg-slate-50"
                                )}
                            >
                                <span className={cn("min-w-0 flex-1 text-sm font-medium truncate", selectedProjectId === project.id.toString() ? "text-blue-700" : "text-slate-700")}>
                                    {project.name}
                                </span>
                                <span className="shrink-0 rounded border border-slate-200 bg-white px-1.5 py-0.5 text-[10px] font-medium text-slate-500">
                                    {sourceLabel(project.provider)}
                                </span>
                                {selectedProjectId === project.id.toString() && <Check className="w-4 h-4 text-blue-600 flex-shrink-0" />}
                            </button>
                        ))
                    )}
                </div>
            </PopoverContent>
        </Popover>
    );
};

export const EntityControl = ({
    projects,
    selectedProjectId,
    onProjectChange,
    date,
    setDate,
    entityId,
    onEntityChange,
    entityOptions,
    branches,
    selectedBranch,
    onBranchChange,
    isLoadingOptions,
    label,
    t
}: EntityControlProps) => (
    <div className="min-w-0 flex-1 space-y-4 rounded-xl border border-slate-200 bg-slate-50/70 p-4" suppressHydrationWarning>
        <div className="font-bold text-sm text-slate-700 mb-2 flex items-center gap-2">
            <div className={cn("w-3 h-3 rounded-full", label.includes('A') ? "bg-blue-500" : "bg-emerald-500")}></div>
            {label}
        </div>

        <div className="grid grid-cols-1 gap-3">
            <div className="min-w-0">
                <label className={labelClassName}>{t('projectPrefix')}</label>
                <ProjectSearchSelector
                    projects={projects}
                    selectedProjectId={selectedProjectId}
                    onProjectChange={onProjectChange}
                    t={t}
                />
            </div>

            <div className="min-w-0">
                <label className={labelClassName}>{t('branch')}</label>
                <div className="relative">
                    <select
                        value={selectedBranch}
                        onChange={(e) => onBranchChange(e.target.value)}
                        disabled={!selectedProjectId}
                        className={cn(fieldClassName, "appearance-none pl-9 pr-9")}
                    >
                        <option value="">{t('allBranches')}</option>
                        {branches.map((branch) => (
                            <option key={branch.name} value={branch.name}>{branch.name}</option>
                        ))}
                    </select>
                    <GitBranch className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-blue-500" />
                    <ChevronDown className="pointer-events-none absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
                </div>
            </div>

            <div className="min-w-0">
                <label className={labelClassName}>{t('dateRange')}</label>
                <DatePickerWithRange
                    date={date}
                    setDate={setDate}
                    triggerClassName="bg-white font-medium text-slate-700 hover:bg-slate-50"
                />
            </div>

            <ComparisonSelector
                label={t('compare')}
                selected={entityId}
                onChange={onEntityChange}
                options={entityOptions}
                disabled={!selectedProjectId || isLoadingOptions}
                isLoading={Boolean(selectedProjectId && isLoadingOptions)}
                t={t}
            />
        </div>
    </div>
);
