import type { Metadata } from "next";
// import { Noto_Sans_JP, Geist_Mono } from "next/font/google";
import localFont from "next/font/local";
import "@/globals.css";
import "react-day-picker/dist/style.css"; // Import react-day-picker styles
import { cn } from "@/components/ui/Common"; // Import cn utility
import { NextIntlClientProvider } from 'next-intl';
import { getMessages } from 'next-intl/server';

const notoSansJP = localFont({
  display: 'swap',
  variable: '--font-geist-sans',
  preload: true,
  src: '../../public/fonts/NotoSansJP-Regular.woff2',
});

const geistMono = localFont({
  variable: "--font-geist-mono",
  src: '../../public/fonts/GeistMono-Regular.woff2',
});

export const metadata: Metadata = {
  title: "Dev Productivity Signals",
  description: "Explore developer productivity signals with metrics and traceable project evidence",
  robots: {
    index: false,
    follow: false,
    nocache: true,
    googleBot: {
      index: false,
      follow: false,
      noimageindex: true,
      nosnippet: true,
    },
  },
};

export default async function RootLayout({
  children,
  params
}: Readonly<{
  children: React.ReactNode;
  params: Promise<{ locale: string }>;
}>) {
  const { locale } = await params;
  const messages = await getMessages({ locale });

  return (
    <html lang={locale} suppressHydrationWarning>
      <body
        className={cn(
          "min-h-screen bg-background font-sans antialiased",
          notoSansJP.variable,
          geistMono.variable
        )}
      >
        <NextIntlClientProvider messages={messages}>
          {children}
        </NextIntlClientProvider>
      </body>
    </html>
  );
}
