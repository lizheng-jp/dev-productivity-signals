import {
    LayoutDashboard,
    BarChart2, User, ClipboardCheck, Settings, MessageSquareText, BookOpen
} from 'lucide-react';
import { useTranslations } from 'next-intl';
import { usePathname, Link } from '../../../i18n/routing';


import { cn } from '../ui/Common';

// Define Menu Items
export const MENU_ITEMS = [
    { id: 'dashboard', name: 'Dashboard', icon: LayoutDashboard, href: '/' },
    { id: 'comparison', name: 'Comparison', icon: BarChart2, href: '/comparison' },
    { id: 'developer', name: 'Developer Analytics', icon: User, href: '/developer' },
    { id: 'satisfaction', name: 'Satisfaction Survey', icon: ClipboardCheck, href: '/satisfaction' },
    { id: 'manual', name: 'Manual', icon: BookOpen, href: '/manual' },
    { id: 'feedback', name: 'Feedback', icon: MessageSquareText, href: '/feedback' },
];

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
    <aside className="hidden w-64 bg-white border-r border-slate-200 md:flex flex-col fixed h-full z-10">
        <div className="h-16 flex items-center px-6 border-b border-slate-200">
            <div className="w-8 h-8 bg-blue-600 rounded mr-3 flex items-center justify-center text-white">
                <LayoutDashboard className="w-5 h-5" />
            </div>
            <span className="font-bold text-lg tracking-tight text-slate-800">SPACE Analytics</span>
        </div>

        <nav className="flex-1 overflow-y-auto py-6 px-3 space-y-1">
            {MENU_ITEMS.map((item) => (
                <Link
                    key={item.id}
                    href={item.href}
                    className={cn(
                        "w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors",
                        activeId === item.id
                            ? "bg-blue-50 text-blue-700"
                            : "text-slate-600 hover:bg-slate-50 hover:text-slate-900"
                    )}
                >
                    <item.icon className="w-5 h-5" /> {t(item.id)}
                </Link>
            ))}
        </nav>
        <div className="border-t border-slate-200 p-3">
            <button
                type="button"
                onClick={onSettingsClick}
                className="flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium text-slate-600 transition-colors hover:bg-slate-50 hover:text-slate-900"
            >
                <Settings className="h-5 w-5" />
                {t('settings')}
            </button>
        </div>
    </aside>
    <nav className="fixed inset-x-0 bottom-0 z-50 flex overflow-x-auto border-t border-slate-200 bg-white px-2 py-2 md:hidden" aria-label="Mobile navigation">
        {MENU_ITEMS.map((item) => (
            <Link
                key={item.id}
                href={item.href}
                title={t(item.id)}
                className={cn(
                    "flex w-16 shrink-0 flex-col items-center gap-1 rounded-md px-1 py-1.5 text-[10px] font-medium",
                    activeId === item.id ? "bg-blue-50 text-blue-700" : "text-slate-500"
                )}
            >
                <item.icon className="h-5 w-5" />
                <span className="block w-full truncate text-center">{t(item.id)}</span>
            </Link>
        ))}
        <button type="button" onClick={onSettingsClick} title={t('settings')} aria-label={t('settings')} className="flex w-16 shrink-0 flex-col items-center gap-1 rounded-md px-1 py-1.5 text-[10px] font-medium text-slate-500">
            <Settings className="h-5 w-5" />
            <span className="block w-full truncate text-center">{t('settings')}</span>
        </button>
    </nav>
    </>
);
}
