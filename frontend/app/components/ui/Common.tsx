// components/ui/Common.tsx
import React from 'react';
import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export const Card = ({ children, className, onClick }: { children: React.ReactNode, className?: string, onClick?: () => void }) => (
  <div className={cn("bg-white border border-slate-200 rounded-lg shadow-sm", className)} onClick={onClick}>
    {children}
  </div>
);

export const Badge = ({ children, color = 'blue' }: { children: React.ReactNode, color?: 'blue' | 'green' | 'yellow' | 'red' | 'gray' }) => {
  const styles = {
    blue: 'bg-blue-100 text-blue-800',
    green: 'bg-emerald-100 text-emerald-800',
    yellow: 'bg-yellow-100 text-yellow-800',
    red: 'bg-red-100 text-red-800',
    gray: 'bg-gray-100 text-gray-800',
  };
  return (
    <span className={cn("px-2.5 py-0.5 rounded-full text-xs font-medium", styles[color])}>
      {children}
    </span>
  );
};

// --- Mock Data (Centralized) ---
export const MOCK_DATA = {
  SPACE_RADAR: [
    { subject: 'Satisfaction', A: 90, B: 70, fullMark: 100 },
    { subject: 'Performance', A: 85, B: 80, fullMark: 100 },
    { subject: 'Activity', A: 95, B: 60, fullMark: 100 },
    { subject: 'Communication', A: 70, B: 75, fullMark: 100 },
    { subject: 'Efficiency', A: 88, B: 90, fullMark: 100 },
  ],
  TEAMS: [
    {
      name: '基盤開発グループ', members: 12, commits: 450, score: 8.9,
      spaceMetrics: [
        { subject: 'Satisfaction', value: 85 },
        { subject: 'Performance', value: 90 },
        { subject: 'Activity', value: 88 },
        { subject: 'Communication', value: 75 },
        { subject: 'Efficiency', value: 92 },
        { subject: 'Quality', value: 80 },
      ]
    },
    {
      name: 'オープン開発グループ', members: 8, commits: 320, score: 8.5,
      spaceMetrics: [
        { subject: 'Satisfaction', value: 90 },
        { subject: 'Performance', value: 82 },
        { subject: 'Activity', value: 85 },
        { subject: 'Communication', value: 88 },
        { subject: 'Efficiency', value: 80 },
        { subject: 'Quality', value: 91 },
      ]
    },
    {
      name: 'パートナー', members: 5, commits: 150, score: 7.8,
      spaceMetrics: [
        { subject: 'Satisfaction', value: 78 },
        { subject: 'Performance', value: 80 },
        { subject: 'Activity', value: 75 },
        { subject: 'Communication', value: 92 },
        { subject: 'Efficiency', value: 85 },
        { subject: 'Quality', value: 70 },
      ]
    },
  ],
  DEVELOPERS: [
    {
      id: 1, name: 'Taro Yamada', team: '基盤開発グループ', commits: 145, space: 9.2, grade: 'A',
      spaceMetrics: [
        { subject: 'Satisfaction', value: 90 },
        { subject: 'Performance', value: 85 },
        { subject: 'Activity', value: 95 },
        { subject: 'Communication', value: 70 },
        { subject: 'Efficiency', value: 88 },
        { subject: 'Quality', value: 92 },
      ]
    },
    {
      id: 2, name: 'Hanako Suzuki', team: 'オープン開発グループ ', commits: 132, space: 8.9, grade: 'A',
      spaceMetrics: [
        { subject: 'Satisfaction', value: 80 },
        { subject: 'Performance', value: 92 },
        { subject: 'Activity', value: 85 },
        { subject: 'Communication', value: 90 },
        { subject: 'Efficiency', value: 82 },
        { subject: 'Quality', value: 88 },
      ]
    },
    {
      id: 3, name: 'John Smith', team: 'パートナー', commits: 110, space: 8.5, grade: 'B',
      spaceMetrics: [
        { subject: 'Satisfaction', value: 75 },
        { subject: 'Performance', value: 70 },
        { subject: 'Activity', value: 80 },
        { subject: 'Communication', value: 95 },
        { subject: 'Efficiency', value: 90 },
        { subject: 'Quality', value: 78 },
      ]
    },
  ]
};

export type SubMetric = {
  label: string;
  value: string | number;
  unit?: string;
  trend?: 'up' | 'down' | 'neutral';
  status: 'good' | 'warning' | 'bad';
  isMock?: boolean;
};

export type SpaceDimensionDetail = {
  name: string; // e.g., "Activity"
  score: number; // e.g., 92
  description: string;
  metrics: SubMetric[];
};

export const MOCK_DEV_DETAIL = {
  id: 1,
  name: 'Taro Yamada',
  role: 'Senior Backend Engineer',
  team: '基盤開発グループ',
  avatar: 'TY',
  totalScore: 9.2,
  dimensions: [
    {
      name: 'Satisfaction',
      score: 90,
      description: 'Developer happiness and burnout risk.',
      metrics: [
        { label: 'eNPS Score', value: 9, status: 'good', unit: '/10' },
        { label: 'Retention Prob.', value: 'High', status: 'good' },
        { label: 'Overtime Hours', value: 2.5, status: 'good', unit: 'h/week' },
      ]
    },
    {
      name: 'Performance',
      score: 85,
      description: 'Quality and impact of code delivered.',
      metrics: [
        { label: 'マージ数', value: 25, status: 'good' },
        { label: 'マージのリードタイム', value: '1.5', unit: 'days', status: 'good' },
        { label: 'バグ件数', value: 3, status: 'good', trend: 'down' },
        { label: 'バグ修正リードタイム', value: '2.1', unit: 'days', status: 'good' },
      ]
    },
    {
      name: 'Activity',
      score: 95,
      description: 'Volume of work performed.',
      metrics: [
        { label: 'コミット数', value: 145, status: 'good', trend: 'up' },
        { label: 'バグ発見件数', value: 18, status: 'good' },
        { label: '修正済みバグ件数', value: 18, status: 'good' },
        {
          label: 'コミット期間',
          chartData: [
            { day: 'Mon', commits: 5 }, { day: 'Tue', commits: 8 }, { day: 'Wed', commits: 12 },
            { day: 'Thu', commits: 7 }, { day: 'Fri', commits: 10 }, { day: 'Sat', commits: 2 }, { day: 'Sun', commits: 1 }
          ]
        },
      ]
    },
    {
      name: 'Communication & Collaboration',
      score: 70,
      description: 'Collaboration and knowledge sharing.',
      metrics: [
        { label: 'レビュアー数', value: 8, status: 'good' },
        { label: 'コメント頻度', value: '12', unit: 'comments/week', status: 'warning' },
      ]
    },
    {
      name: 'Efficiency & Flow',
      score: 88,
      description: 'Flow and speed of delivery.',
      metrics: [
        { label: 'レビュー開始までの待ち時間', value: '4.5', unit: 'hours', status: 'good' },
        { label: 'コメント数', value: 35, status: 'good' },
      ]
    },
  ] as SpaceDimensionDetail[]
};

export const MOCK_PROJECT_DETAIL = {
  id: 1,
  name: 'IDMS System',
  totalScore: 8.5,
  dimensions: [
    {
      name: 'Satisfaction',
      score: 80,
      description: 'Team happiness and morale.',
      metrics: [
        { label: 'eNPS Score', value: 8, status: 'good', unit: '/10' },
        { label: 'Team Burnout Rate', value: '15%', status: 'warning' },
        { label: 'Avg. Overtime', value: 3.0, status: 'warning', unit: 'h/week' },
      ]
    },
    {
      name: 'Performance',
      score: 75,
      description: 'Overall project delivery quality and impact.',
      metrics: [
        { label: 'マージ数', value: 120, status: 'good' },
        { label: 'マージのリードタイム', value: '2.5', unit: 'days', status: 'warning' },
        { label: 'バグ件数', value: 15, status: 'bad', trend: 'up' },
        { label: 'バグ修正リードタイム', value: '3.5', unit: 'days', status: 'bad' },
      ]
    },
    {
      name: 'Activity',
      score: 90,
      description: 'Project activity and output volume.',
      metrics: [
        { label: 'コミット数', value: 500, status: 'good', trend: 'up' },
        { label: '修正済みバグ件数', value: 40, status: 'good' },
        {
          label: 'コミット期間',
          chartData: [
            { day: 'Mon', commits: 20 }, { day: 'Tue', commits: 35 }, { day: 'Wed', commits: 50 },
            { day: 'Thu', commits: 30 }, { day: 'Fri', commits: 45 }, { day: 'Sat', commits: 10 }, { day: 'Sun', commits: 5 }
          ]
        },
      ]
    },
    {
      name: 'Communication & Collaboration',
      score: 85,
      description: 'Team collaboration and information flow.',
      metrics: [
        { label: 'レビュアー数', value: 15, status: 'good' },
        { label: 'コメント頻度', value: '25', unit: 'comments/week', status: 'good' },
      ]
    },
    {
      name: 'Efficiency & Flow',
      score: 80,
      description: 'Project delivery speed and efficiency.',
      metrics: [
        { label: 'レビュー開始までの待ち時間', value: '6.0', unit: 'hours', status: 'warning' },
        { label: 'コメント数', value: 70, status: 'good' },
      ]
    },
  ] as SpaceDimensionDetail[]
};