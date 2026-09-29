import React from 'react';
import { MainLayoutClientShell } from './MainLayoutClientShell';

export default function MainLayout({ children }: { children: React.ReactNode }) {
    return (
        <MainLayoutClientShell>{children}</MainLayoutClientShell>
    );
}
