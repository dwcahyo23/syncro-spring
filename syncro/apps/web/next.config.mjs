/** @type {import('next').NextConfig} */
const nextConfig = {
  reactCompiler: true,
  compiler: {
    removeConsole: process.env.NODE_ENV === "production",
  },
  async redirects() {
    return [
      {
        source: "/dashboard",
        destination: "/operations-overview",
        permanent: false,
      },
    ];
  },
  async rewrites() {
    return [
      { source: "/operations-overview", destination: "/dashboard/operations-overview" },
      { source: "/telemetry", destination: "/dashboard/telemetry" },
      { source: "/alerts", destination: "/dashboard/alerts" },
      { source: "/master-data/:path*", destination: "/dashboard/master-data/:path*" },
      { source: "/waha-templates", destination: "/dashboard/waha-templates" },
      { source: "/audit-log", destination: "/dashboard/audit-log" },
      { source: "/system-health", destination: "/dashboard/system-health" },
      { source: "/settings", destination: "/dashboard/settings" },
    ];
  },
};

export default nextConfig;
