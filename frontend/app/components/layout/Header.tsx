import React, { useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { type DateRange } from 'react-day-picker';
import { Branch, getBranches, Project } from '@/lib/api/projects';
import { getMergeRequestActivityPeriod } from '@/lib/api/merges';
import { DatePickerWithRange } from '@/components/ui/date-range-picker';
import { useTranslations } from 'next-intl';
import { GitBranch, Search, ChevronDown, Check, FolderKanban, FolderGit2 } from 'lucide-react';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { cn } from '../ui/Common';

interface HeaderProps {
    projects: Project[];
    isLoadingProjects: boolean;
    errorProjects: string | null;
    selectedProjectId: string | null;
    onProjectChange: (projectId: string) => void;
    selectedBranch: string;
    onBranchChange: (branchName: string) => void;
    date: DateRange | undefined;
    setDate: (date: DateRange | undefined) => void;
}

const defaultBranchName = (branchList: Branch[]) => {
    return branchList.find(branch => branch.default)?.name || branchList[0]?.name || '';
};

const parseBranchDate = (value?: string) => {
    if (!value) return undefined;
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? undefined : date;
};

const latestUpdatedBranch = (branchList: Branch[]) => {
    return branchList.reduce<Branch | undefined>((latest, branch) => {
        if (!latest) return branch;
        const latestTime = parseBranchDate(latest.last_commit_at)?.getTime() ?? Number.NEGATIVE_INFINITY;
        const branchTime = parseBranchDate(branch.last_commit_at)?.getTime() ?? Number.NEGATIVE_INFINITY;
        return branchTime > latestTime ? branch : latest;
    }, undefined);
};

const branchActivityRange = async (projectId: string, branch?: Branch): Promise<DateRange | undefined> => {
    if (!branch) return undefined;
    const from = parseBranchDate(branch.first_commit_at);
    let to = parseBranchDate(branch.last_commit_at);

    try {
        const period = await getMergeRequestActivityPeriod(projectId, branch.name);
        to = parseBranchDate(period.lastMergeMergedDate || undefined) || to;
    } catch (error) {
        console.error('Failed to load MR activity period:', error);
    }

    if (!from && !to) return undefined;
    return { from: from || to, to: to || from };
};

export const Header = (props: HeaderProps) => {
    const {
        projects,
        isLoadingProjects,
        errorProjects,
        selectedProjectId,
        onProjectChange,
        selectedBranch,
        onBranchChange,
        date,
        setDate,
    } = props;
    const t = useTranslations('Header');

    const [searchQuery, setSearchQuery] = useState('');
    const [isPopoverOpen, setIsPopoverOpen] = useState(false);
    const [branchSearchQuery, setBranchSearchQuery] = useState('');
    const [isBranchPopoverOpen, setIsBranchPopoverOpen] = useState(false);
    const [branches, setBranches] = useState<Branch[]>([]);
    const [isLoadingBranches, setIsLoadingBranches] = useState(false);
    const selectedBranchRef = useRef(selectedBranch);
    const isMounted = useSyncExternalStore(
        () => () => {},
        () => true,
        () => false
    );

    useEffect(() => {
        selectedBranchRef.current = selectedBranch;
    }, [selectedBranch]);

    // Sort projects alphabetically by name
    const sortedProjects = useMemo(() => {
        return [...projects].sort((a, b) => a.name.localeCompare(b.name));
    }, [projects]);

    // Filter projects based on search query
    const filteredProjects = useMemo(() => {
        if (!searchQuery) return sortedProjects;
        const query = searchQuery.toLowerCase();
        return sortedProjects.filter(p =>
            p.name.toLowerCase().includes(query) ||
            p.description?.toLowerCase().includes(query)
        );
    }, [sortedProjects, searchQuery]);

    const selectedProject = useMemo(() => {
        return projects.find(p => p.id.toString() === selectedProjectId);
    }, [projects, selectedProjectId]);

    const sortedBranches = useMemo(() => {
        return [...branches].sort((a, b) => a.name.localeCompare(b.name));
    }, [branches]);

    const filteredBranches = useMemo(() => {
        if (!branchSearchQuery) return sortedBranches;
        const query = branchSearchQuery.toLowerCase();
        return sortedBranches.filter(branch => branch.name.toLowerCase().includes(query));
    }, [sortedBranches, branchSearchQuery]);

    const selectedBranchLabel = selectedBranch || t('selectBranch');
    const SelectedProjectIcon = selectedProject?.provider === 'github' ? FolderGit2 : FolderKanban;
    const projectSourceLabel = (provider?: string) => t(
        provider === 'mock' ? 'sourceDemo' : provider === 'github' ? 'sourceGitHub' : 'sourceGitLab'
    );

    useEffect(() => {
        if (!selectedProjectId) {
            return;
        }

        let ignore = false;
        Promise.resolve()
            .then(() => {
                if (!ignore) setIsLoadingBranches(true);
                return getBranches(selectedProjectId);
            })
            .then(results => {
                if (ignore) return;
                setBranches(results);
                const currentBranch = selectedBranchRef.current;
                if (!currentBranch || !results.some(branch => branch.name === currentBranch)) {
                    const branch = results.find(item => item.name === defaultBranchName(results)) || latestUpdatedBranch(results);
                    onBranchChange(branch?.name || '');
                }
            })
            .catch(error => {
                if (ignore) return;
                console.error('Failed to load branches:', error);
                setBranches([]);
            })
            .finally(() => {
                if (!ignore) {
                    setIsLoadingBranches(false);
                }
            });

        return () => {
            ignore = true;
        };
    }, [selectedProjectId, onBranchChange]);

    const handleProjectSelect = (projectId: string) => {
        onProjectChange(projectId);
        setBranches([]);
        setBranchSearchQuery('');
        setIsBranchPopoverOpen(false);
        setIsPopoverOpen(false);
    };

    const handleBranchSelect = async (branchName: string) => {
        const range = selectedProjectId
            ? await branchActivityRange(selectedProjectId, branches.find(branch => branch.name === branchName))
            : undefined;
        onBranchChange(branchName);
        if (range) {
            setDate(range);
        }
        setIsBranchPopoverOpen(false);
    };

    return (
        <header className="h-16 bg-white border-b border-slate-200 flex items-center px-8 sticky top-0 z-20" suppressHydrationWarning>
            <div className="flex items-center gap-5">
                <div className="flex items-center gap-2">
                    <div className="w-20 text-right text-xs font-bold text-slate-500">{t('projectLabel')}</div>
                    <div className="relative group">
                    {isLoadingProjects && <span className="text-gray-500 text-sm animate-pulse">{t('loadingProjects')}</span>}
                    {errorProjects && <span className="text-red-500 text-sm">{t('error', { message: errorProjects })}</span>}

                    {!isLoadingProjects && !errorProjects && !isMounted && (
                        <button
                            type="button"
                            className="flex items-center justify-between gap-3 text-sm font-semibold text-slate-800 bg-slate-100 border border-slate-200 px-4 py-2 rounded-lg transition-all w-72 shadow-sm group"
                        >
                            <div className="flex items-center gap-2 truncate">
                                <SelectedProjectIcon className="w-4 h-4 text-blue-500" />
                                <span className="truncate">{selectedProject?.name || t('noProjects')}</span>
                            </div>
                            <ChevronDown className="w-4 h-4 text-slate-400" />
                        </button>
                    )}

                    {!isLoadingProjects && !errorProjects && isMounted && (
                        <Popover open={isPopoverOpen} onOpenChange={setIsPopoverOpen}>
                            <PopoverTrigger asChild>
                                <button
                                    type="button"
                                    className="flex items-center justify-between gap-3 text-sm font-semibold text-slate-800 bg-slate-100 hover:bg-slate-200 border border-slate-200 px-4 py-2 rounded-lg transition-all w-72 shadow-sm group"
                                >
                                    <div className="flex items-center gap-2 truncate">
                                        <SelectedProjectIcon className="w-4 h-4 text-blue-500" />
                                        <span className="truncate">{selectedProject?.name || t('noProjects')}</span>
                                    </div>
                                    <ChevronDown className={cn("w-4 h-4 text-slate-400 transition-transform", isPopoverOpen && "rotate-180")} />
                                </button>
                            </PopoverTrigger>
                            <PopoverContent className="p-0 w-80 bg-white" align="start">
                                <div className="p-2 border-b border-slate-100 bg-slate-50">
                                    <div className="relative">
                                        <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-slate-400" />
                                        <input
                                            type="text"
                                            placeholder={t('searchProjects')}
                                            className="w-full bg-white border border-slate-200 rounded-md py-2 pl-9 pr-3 text-sm outline-none focus:ring-2 focus:ring-blue-500/20 focus:border-blue-500"
                                            value={searchQuery}
                                            onChange={(e) => setSearchQuery(e.target.value)}
                                        />
                                    </div>
                                </div>
                                <div className="max-h-80 overflow-y-auto p-1">
                                    {filteredProjects.length === 0 ? (
                                        <div className="py-6 text-center text-slate-500 text-sm">
                                            {t('noProjects')}
                                        </div>
                                    ) : (
                                        filteredProjects.map((project) => (
                                            <button
                                                key={project.id}
                                                onClick={() => handleProjectSelect(project.id.toString())}
                                                className={cn(
                                                    "w-full text-left px-3 py-2.5 rounded-md transition-colors flex items-start gap-3 group/item",
                                                    selectedProjectId === project.id.toString()
                                                        ? "bg-blue-50"
                                                        : "hover:bg-slate-50"
                                                )}
                                            >
                                                <div className="flex-1 min-w-0">
                                                    <div className="flex items-center justify-between gap-2">
                                                        <span className={cn(
                                                            "text-sm font-medium truncate",
                                                            selectedProjectId === project.id.toString() ? "text-blue-700" : "text-slate-700"
                                                        )}>
                                                            {project.name}
                                                        </span>
                                                        <span className="shrink-0 rounded border border-slate-200 bg-white px-1.5 py-0.5 text-[10px] font-medium text-slate-500">
                                                            {projectSourceLabel(project.provider)}
                                                        </span>
                                                        {selectedProjectId === project.id.toString() && (
                                                            <Check className="w-4 h-4 text-blue-600 flex-shrink-0" />
                                                        )}
                                                    </div>
                                                    {project.description && (
                                                        <p className="text-xs text-slate-400 line-clamp-2 mt-0.5 font-normal">
                                                            {project.description}
                                                        </p>
                                                    )}
                                                </div>
                                            </button>
                                        ))
                                    )}
                                </div>
                            </PopoverContent>
                        </Popover>
                    )}
                    </div>
                </div>
                <div className="flex items-center gap-2">
                    <div className="w-16 text-right text-xs font-bold text-slate-500">{t('branchLabel')}</div>
                    <Popover open={isBranchPopoverOpen} onOpenChange={setIsBranchPopoverOpen}>
                        <PopoverTrigger asChild>
                            <button
                                type="button"
                                disabled={!selectedProjectId || isLoadingBranches}
                                className="flex w-56 items-center justify-between gap-3 rounded-lg border border-slate-200 bg-slate-100 px-4 py-2 text-sm font-semibold text-slate-800 shadow-sm transition-all hover:bg-slate-200 disabled:cursor-not-allowed disabled:text-slate-400 disabled:hover:bg-slate-100"
                            >
                                <div className="flex min-w-0 items-center gap-2">
                                    <GitBranch className="h-4 w-4 flex-shrink-0 text-blue-500" />
                                    <span className="truncate">{selectedBranchLabel}</span>
                                </div>
                                <ChevronDown className={cn("h-4 w-4 flex-shrink-0 text-slate-400 transition-transform", isBranchPopoverOpen && "rotate-180")} />
                            </button>
                        </PopoverTrigger>
                        <PopoverContent className="w-80 bg-white p-0" align="start">
                            <div className="border-b border-slate-100 bg-slate-50 p-2">
                                <div className="relative">
                                    <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-slate-400" />
                                    <input
                                        type="text"
                                        placeholder={t('searchBranches')}
                                        className="w-full rounded-md border border-slate-200 bg-white py-2 pl-9 pr-3 text-sm outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20"
                                        value={branchSearchQuery}
                                        onChange={(event) => setBranchSearchQuery(event.target.value)}
                                    />
                                </div>
                            </div>
                            <div className="max-h-80 overflow-y-auto p-1">
                                {filteredBranches.length === 0 ? (
                                    <div className="py-6 text-center text-sm text-slate-500">
                                        {t('noBranches')}
                                    </div>
                                ) : (
                                    filteredBranches.map(branch => (
                                        <button
                                            key={branch.name}
                                            type="button"
                                            onClick={() => handleBranchSelect(branch.name)}
                                            className={cn(
                                                "flex w-full items-center justify-between gap-2 rounded-md px-3 py-2.5 text-left transition-colors",
                                                selectedBranch === branch.name ? "bg-blue-50" : "hover:bg-slate-50"
                                            )}
                                        >
                                            <span className={cn("truncate text-sm font-medium", selectedBranch === branch.name ? "text-blue-700" : "text-slate-700")}>
                                                {branch.name}
                                            </span>
                                            {selectedBranch === branch.name && <Check className="h-4 w-4 flex-shrink-0 text-blue-600" />}
                                        </button>
                                    ))
                                )}
                            </div>
                        </PopoverContent>
                    </Popover>
                </div>
                <div className="flex items-center gap-2">
                    <div className="w-12 text-right text-xs font-bold text-slate-500">{t('periodLabel')}</div>
                    <div className="w-72">
                    <DatePickerWithRange date={date} setDate={setDate} />
                    </div>
                </div>
            </div>
        </header>
    );
}
