import type { NextConfig } from 'next';

const nextConfig: NextConfig = {
  output: 'standalone',
  devIndicators: false,
  // Keep development-time framework hints from creating extra instruction files.
  agentRules: false,
};

export default nextConfig;
