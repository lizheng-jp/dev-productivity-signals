// components/ui/Charts.tsx
'use client';
import React from 'react';
import { useTranslations } from 'next-intl';
import {
  Radar, RadarChart, PolarGrid, PolarAngleAxis, PolarRadiusAxis,
  ResponsiveContainer, Tooltip, LineChart, Line, CartesianGrid, XAxis, YAxis, BarChart, Bar
} from 'recharts';

export const SpaceRadarChart = ({ chartData }: { chartData: { subject: string; value: number }[] }) => {
  const t = useTranslations('Table');
  const gradientId = `radar-${React.useId().replace(/:/g, '')}`;
  return (
  <ResponsiveContainer width="100%" height="100%">
    <RadarChart cx="50%" cy="50%" outerRadius="70%" data={chartData}>
      <defs>
        <linearGradient id={gradientId} x1="0" x2="1" y1="0" y2="1">
          <stop offset="0" stopColor="var(--color-aurora-blue)" />
          <stop offset="0.55" stopColor="var(--color-aurora-violet)" />
          <stop offset="1" stopColor="var(--color-aurora-cyan)" />
        </linearGradient>
      </defs>
      <PolarGrid gridType="polygon" stroke="var(--color-slate-200, #e2e8f0)" />
      <PolarAngleAxis dataKey="subject" tick={{ fill: 'var(--color-slate-500, #64748b)', fontSize: 10 }} />
      <PolarRadiusAxis angle={30} domain={[0, 100]} tick={false} axisLine={false} />
      <Radar name={t('score')} dataKey="value" stroke={`url(#${gradientId})`} strokeWidth={2} fill={`url(#${gradientId})`} fillOpacity={0.25} />
      <Tooltip itemStyle={{ fontSize: '12px' }} />
    </RadarChart>
  </ResponsiveContainer>
);
};

export const TrendLineChart = ({ data, dataKey, color = "var(--color-blue-500, #3b82f6)" }: { data: Record<string, string | number>[]; dataKey: string; color?: string }) => (
  <ResponsiveContainer width="100%" height="100%">
    <LineChart data={data}>
      <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="var(--color-slate-100, #f1f5f9)" />
      <XAxis dataKey="day" axisLine={false} tickLine={false} tick={{fill: 'var(--color-slate-400, #94a3b8)', fontSize: 12}} dy={10} />
      <YAxis axisLine={false} tickLine={false} tick={{fill: 'var(--color-slate-400, #94a3b8)', fontSize: 12}} />
      <Tooltip />
      <Line type="monotone" dataKey={dataKey} stroke={color} strokeWidth={3} dot={{r: 4}} />
    </LineChart>
  </ResponsiveContainer>
);

export const CommitActivityChart = ({ chartData }: { chartData: { day: string; commits: number }[] }) => {
  const t = useTranslations('Table');
  return (
  <ResponsiveContainer width="100%" height="100%">
    <BarChart data={chartData}>
      <CartesianGrid strokeDasharray="3 3" vertical={false} />
      <XAxis dataKey="day" tick={{ fill: 'var(--color-slate-500, #64748b)', fontSize: 10 }} />
      <YAxis tick={{ fill: 'var(--color-slate-500, #64748b)', fontSize: 10 }} />
      <Tooltip />
      <Bar name={t('commits')} dataKey="commits" fill="var(--color-blue-500, #3b82f6)" radius={[4, 4, 0, 0]} />
    </BarChart>
  </ResponsiveContainer>
);
};
