'use client';

import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
  BarChart,
  Bar,
} from 'recharts';

const trendingData = [
  { name: 'Day 1', 'Developer A': 4000, 'Developer B': 2400 },
  { name: 'Day 2', 'Developer A': 3000, 'Developer B': 1398 },
  { name: 'Day 3', 'Developer A': 2000, 'Developer B': 9800 },
  { name: 'Day 4', 'Developer A': 2780, 'Developer B': 3908 },
  { name: 'Day 5', 'Developer A': 1890, 'Developer B': 4800 },
  { name: 'Day 6', 'Developer A': 2390, 'Developer B': 3800 },
  { name: 'Day 7', 'Developer A': 3490, 'Developer B': 4300 },
];

const topContributorsData = [
  { name: 'Developer A', commits: 120 },
  { name: 'Developer B', commits: 110 },
  { name: 'Developer C', commits: 100 },
  { name: 'Developer D', commits: 90 },
  { name: 'Developer E', commits: 80 },
];

export default function Analytics() {
  return (
    <div className="flex flex-col space-y-8">
      <div>
        <h2 className="text-lg font-semibold mb-4">Trending Developers (Last 7 Days)</h2>
        <ResponsiveContainer width="100%" height={300}>
          <LineChart data={trendingData}>
            <CartesianGrid strokeDasharray="3 3" stroke="rgba(255, 255, 255, 0.1)" />
            <XAxis dataKey="name" stroke="rgba(255, 255, 255, 0.5)" />
            <YAxis stroke="rgba(255, 255, 255, 0.5)" />
            <Tooltip
              contentStyle={{
                backgroundColor: 'rgba(30, 41, 59, 0.9)',
                borderColor: 'rgba(255, 255, 255, 0.2)',
              }}
            />
            <Legend />
            <Line type="monotone" dataKey="Developer A" stroke="#8884d8" />
            <Line type="monotone" dataKey="Developer B" stroke="#82ca9d" />
          </LineChart>
        </ResponsiveContainer>
      </div>
      <div>
        <h2 className="text-lg font-semibold mb-4">Top 5 Contributors by Commits</h2>
        <ResponsiveContainer width="100%" height={300}>
          <BarChart data={topContributorsData} layout="vertical">
            <CartesianGrid strokeDasharray="3 3" stroke="rgba(255, 255, 255, 0.1)" />
            <XAxis type="number" stroke="rgba(255, 255, 255, 0.5)" />
            <YAxis type="category" dataKey="name" stroke="rgba(255, 255, 255, 0.5)" />
            <Tooltip
              contentStyle={{
                backgroundColor: 'rgba(30, 41, 59, 0.9)',
                borderColor: 'rgba(255, 255, 255, 0.2)',
              }}
            />
            <Legend />
            <Bar dataKey="commits" fill="#8884d8" />
          </BarChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
