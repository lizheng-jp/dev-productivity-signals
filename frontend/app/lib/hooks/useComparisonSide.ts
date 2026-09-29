import { useState, useEffect } from 'react';
import { DateRange } from "react-day-picker";
import { addDays } from "date-fns";
import { Project, getBranches, Branch } from '@/lib/api/projects';
import { getActiveProjectMembers, ProjectMember } from '@/lib/api/members';
import { formatLocalDate } from '@/lib/date-format';

type ComparisonSideInitialState = {
    branch?: string;
    date?: DateRange;
    members?: ProjectMember[];
};

export function useComparisonSide(
    initialProjectId: string,
    projects: Project[],
    initialEntityId = 'proj-current',
    initialState?: ComparisonSideInitialState
) {
    const [projectId, setProjectId] = useState<string>(initialProjectId);
    const [branches, setBranches] = useState<Branch[]>([]);
    const [activeMembers, setActiveMembers] = useState<ProjectMember[]>(initialState?.members || []);
    const [isLoadingOptions, setIsLoadingOptions] = useState(Boolean(initialProjectId));
    const [isLoadingActiveMembers, setIsLoadingActiveMembers] = useState(Boolean(initialProjectId));
    const [branch, setBranch] = useState<string>(initialState?.branch || '');
    const [date, setDate] = useState<DateRange | undefined>(initialState?.date || {
        from: addDays(new Date(), -30),
        to: new Date(),
    });
    const [entityId, setEntityId] = useState(initialEntityId);

    useEffect(() => {
        if (!projectId) return;

        let ignore = false;
        getBranches(projectId)
            .then(branchResults => {
                if (ignore) return;
                setBranches(branchResults);
            })
            .catch((error) => {
                if (ignore) return;
                console.error(error);
                setBranches([]);
            })
            .finally(() => {
                if (!ignore) setIsLoadingOptions(false);
            });

        return () => {
            ignore = true;
        };
    }, [projectId]);

    useEffect(() => {
        const since = formatLocalDate(date?.from);
        const until = formatLocalDate(date?.to);

        if (!projectId || !since || !until) {
            return;
        }

        let ignore = false;
        getActiveProjectMembers(projectId, {
            since,
            until,
            refName: branch || undefined,
        })
            .then(activeMemberResults => {
                if (ignore) return;
                setActiveMembers(activeMemberResults);
            })
            .catch(error => {
                if (ignore) return;
                console.error(error);
                setActiveMembers([]);
            })
            .finally(() => {
                if (!ignore) setIsLoadingActiveMembers(false);
            });

        return () => {
            ignore = true;
        };
    }, [projectId, branch, date?.from, date?.to]);

    const handleProjectChange = (nextProjectId: string) => {
        setProjectId(nextProjectId);
        setBranches([]);
        setActiveMembers([]);
        setBranch('');
        setEntityId(initialEntityId);
        setIsLoadingOptions(Boolean(nextProjectId));
        setIsLoadingActiveMembers(Boolean(nextProjectId));
    };

    const handleBranchChange = (branchName: string) => {
        setBranch(branchName);
        setActiveMembers([]);
        setIsLoadingActiveMembers(Boolean(projectId && date?.from && date?.to));
        if (branchName) {
            const b = branches.find(x => x.name === branchName);
            if (b?.first_commit_at && b?.last_commit_at) {
                setDate({
                    from: new Date(b.first_commit_at),
                    to: new Date(b.last_commit_at)
                });
                setIsLoadingActiveMembers(Boolean(projectId));
            }
        }
    };

    const handleDateChange = (nextDate: DateRange | undefined) => {
        setDate(nextDate);
        setActiveMembers([]);
        setIsLoadingActiveMembers(Boolean(projectId && nextDate?.from && nextDate?.to));
    };

    const selectedProject = projects.find(project => project.id.toString() === projectId) || null;

    return {
        projectId,
        setProjectId: handleProjectChange,
        selectedProject,
        branches,
        members: activeMembers,
        isLoadingOptions: isLoadingOptions || isLoadingActiveMembers,
        branch,
        handleBranchChange,
        date,
        setDate: handleDateChange,
        entityId,
        setEntityId,
    };
}
