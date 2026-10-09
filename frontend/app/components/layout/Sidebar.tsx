import {
    LayoutDashboard,
    BarChart2, User, ClipboardCheck, Settings, MessageSquareText, BookOpen
} from 'lucide-react';
import { useTranslations } from 'next-intl';
import { usePathname, Link } from '../../../i18n/routing';


import { cn } from '../ui/Common';

// Define Menu Items
export const MENU_ITEMS = [
    { id: 'dashboard', name: 'Dashboard', icon: LayoutDashboard, href: '/', section: 'workspace' },
    { id: 'comparison', name: 'Comparison', icon: BarChart2, href: '/comparison', section: 'workspace' },
    { id: 'developer', name: 'Developer Analytics', icon: User, href: '/developer', section: 'workspace' },
    { id: 'satisfaction', name: 'Satisfaction Survey', icon: ClipboardCheck, href: '/satisfaction', section: 'workspace' },
    { id: 'manual', name: 'Manual', icon: BookOpen, href: '/manual', section: 'resources' },
    { id: 'feedback', name: 'Feedback', icon: MessageSquareText, href: '/feedback', section: 'resources' },
];

const SECTIONS = [
    { id: 'workspace', label: 'Workspace' },
    { id: 'resources', label: 'Resources' },
];

const navItemClass = (active: boolean) => cn(
    "relative flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition-all duration-200",
    active
        ? "bg-white text-slate-900 font-semibold shadow-sm ring-1 ring-slate-900/5"
        : "text-slate-600 hover:bg-white/70 hover:text-slate-900"
);

type SidebarProps = {
    onSettingsClick: () => void;
};

export const Sidebar = ({ onSettingsClick }: SidebarProps) => {
    const t = useTranslations('Sidebar');
    const pathname = usePathname();

    const getActiveId = (currentPath: string) => {
        // Find the best match. A longer href that matches is better.
        let bestMatch = 'dashboard';
        let longestMatch = 0;
        for (const item of MENU_ITEMS) {
            if (currentPath.startsWith(item.href) && item.href.length > longestMatch) {
                longestMatch = item.href.length;
                bestMatch = item.id;
            }
        }
        return bestMatch;
    };

    const activeId = getActiveId(pathname);

    return (
    <>
    <aside className="glass fixed z-10 hidden h-full w-64 flex-col border-r border-white/60 px-3.5 py-5 shadow-[1px_0_0_rgb(120_135_170/0.12)] md:flex">
        <div className="flex items-center gap-3 px-2 pb-6">
            <div className="bg-aurora relative flex h-9 w-9 items-center justify-center rounded-xl shadow-[0_6px_18px_-4px_rgb(79_123_255/0.6)]">
                <span className="h-3 w-3 rotate-45 rounded-[3px] border-2 border-white" />
            </div>
            <div className="leading-tight">
                <span className="block text-[15px] font-bold tracking-tight text-slate-900">Signals</span>
                <span className="kicker block text-[9.5px]">SPACE Analytics</span>
            </div>
        </div>

        <nav className="flex-1 space-y-5 overflow-y-auto">
            {SECTIONS.map(section => (
                <div key={section.id}>
                    <div className="kicker px-3 pb-2">{section.label}</div>
                    <div className="space-y-1">
                        {MENU_ITEMS.filter(item => item.section === section.id).map((item) => {
                            const active = activeId === item.id;
                            return (
                                <Link key={item.id} href={item.href} className={navItemClass(active)} aria-current={active ? 'page' : undefined}>
                                    {active && <span className="bg-aurora absolute -left-3.5 top-2 bottom-2 w-[3px] rounded-r-full shadow-[0_0_12px_rgb(79_123_255/0.9)]" />}
                                    <item.icon className={cn("h-[18px] w-[18px]", active ? "text-blue-500" : "text-slate-400")} />
                                    {t(item.id)}
                                </Link>
                            );
                        })}
                    </div>
                </div>
            ))}
        </nav>
        <div className="pt-3">
            <button type="button" onClick={onSettingsClick} className={navItemClass(false)}>
                <Settings className="h-[18px] w-[18px] text-slate-400" />
                {t('settings')}
            </button>
        </div>
    </aside>
    <nav className="glass fixed inset-x-0 bottom-0 z-50 flex overflow-x-auto border-t border-white/60 px-2 py-2 shadow-[0_-1px_0_rgb(120_135_170/0.12)] md:hidden" aria-label="Mobile navigation">
        {MENU_ITEMS.map((item) => (
            <Link
                key={item.id}
                href={item.href}
                title={t(item.id)}
                className={cn(
                    "flex w-16 shrink-0 flex-col items-center gap-1 rounded-xl px-1 py-1.5 text-[10px] font-medium",
                    activeId === item.id ? "bg-white text-blue-700 shadow-sm" : "text-slate-500"
                )}
            >
                <item.icon className="h-5 w-5" />
                <span className="block w-full truncate text-center">{t(item.id)}</span>
            </Link>
        ))}
        <button type="button" onClick={onSettingsClick} title={t('settings')} aria-label={t('settings')} className="flex w-16 shrink-0 flex-col items-center gap-1 rounded-xl px-1 py-1.5 text-[10px] font-medium text-slate-500">
            <Settings className="h-5 w-5" />
            <span className="block w-full truncate text-center">{t('settings')}</span>
        </button>
    </nav>
    </>
);
}
