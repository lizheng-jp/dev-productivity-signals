import { redirect } from 'next/navigation';

export default async function AgentPage({ params }: { params: Promise<{ locale: string }> }) {
    const { locale } = await params;
    redirect(`/${locale}`);
}
