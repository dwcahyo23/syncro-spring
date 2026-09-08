import createNextIntlPlugin from "next-intl/plugin";

import { dirname } from "node:path";
import { fileURLToPath } from "node:url";

const webRoot = dirname(fileURLToPath(import.meta.url));
const withNextIntl = createNextIntlPlugin("./src/i18n/request.ts");

/** @type {import('next').NextConfig} */
const nextConfig = {
  reactCompiler: true,
  compiler: {
    removeConsole: process.env.NODE_ENV === "production",
  },
  turbopack: {
    root: webRoot,
  },
  // Public URL contract preserved: every previously-public unprefixed path still
  // resolves — the next-intl proxy redirects `/foo` to `/id/foo` first, then the
  // locale-prefixed sources below match in lockstep under `app/[locale]/`.
  // `:locale` is constrained to the real locales so unknown two-segment paths
  // (e.g. /fr/settings) can never be rewritten to wrong-shaped destinations.
  async redirects() {
    return [
      {
        source: "/:locale(id|en)/dashboard",
        destination: "/:locale/operations-overview",
        permanent: false,
      },
    ];
  },
  async rewrites() {
    return [
      { source: "/:locale(id|en)/operations-overview", destination: "/:locale/dashboard/operations-overview" },
      { source: "/:locale(id|en)/machine-dashboard", destination: "/:locale/dashboard/machine-dashboard" },
      { source: "/:locale(id|en)/workorder-dashboard", destination: "/:locale/dashboard/workorder-dashboard" },
      { source: "/:locale(id|en)/preventive-dashboard", destination: "/:locale/dashboard/preventive-dashboard" },
      { source: "/:locale(id|en)/analytics", destination: "/:locale/dashboard/analytics" },
      { source: "/:locale(id|en)/telemetry", destination: "/:locale/dashboard/telemetry" },
      { source: "/:locale(id|en)/alerts", destination: "/:locale/dashboard/alerts" },
      { source: "/:locale(id|en)/master-data/:path*", destination: "/:locale/dashboard/master-data/:path*" },
      { source: "/:locale(id|en)/waha-templates", destination: "/:locale/dashboard/waha-templates" },
      { source: "/:locale(id|en)/audit-log", destination: "/:locale/dashboard/audit-log" },
      { source: "/:locale(id|en)/system-health", destination: "/:locale/dashboard/system-health" },
      { source: "/:locale(id|en)/settings", destination: "/:locale/dashboard/settings" },
    ];
  },
};

export default withNextIntl(nextConfig);
