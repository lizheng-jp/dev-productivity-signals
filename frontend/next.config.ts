import type { NextConfig } from "next";
import createNextIntlPlugin from 'next-intl/plugin';

const withNextIntl = createNextIntlPlugin('./i18n/request.ts');

const nextConfig: NextConfig = {
  output: "standalone",
  experimental: {
    proxyTimeout: 300000,
  },
  images: {
    domains: ['via.placeholder.com'],
  },
  async rewrites() {
    return [
      {
        source: '/api/:path*',
        destination: 'http://tomcat:8080/api/:path*'
      },
    ]
  },
  /* config options here */
};

export default withNextIntl(nextConfig);