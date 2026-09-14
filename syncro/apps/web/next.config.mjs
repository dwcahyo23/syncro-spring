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
      // Bare group URL has no page of its own; land it on the group's first
      // area, same trick as the legacy `/dashboard` entry above.
      {
        source: "/:locale(id|en)/maintenance",
        destination: "/:locale/maintenance/workorders",
        permanent: false,
      },
      // Maintenance alias regroup: `/maintenance/<area>` is the public URL; the
      // old `/dashboard/<area>` links (WhatsApp ack messages, browser bookmarks,
      // shared print deep links) must keep working. `permanent: false` (307) so
      // the redirect is never cached against a future move, and Next passes the
      // query string (`?token=` on ack links) through to the destination.
      // The backend's WA ack links are locale-unprefixed, so such a link takes
      // two hops: the next-intl proxy adds the `/id` prefix first, then this
      // redirect applies — the query survives both.
      {
        source: "/:locale(id|en)/dashboard/workorders/:path*",
        destination: "/:locale/maintenance/workorders/:path*",
        permanent: false,
      },
      {
        source: "/:locale(id|en)/dashboard/preventive/:path*",
        destination: "/:locale/maintenance/preventive/:path*",
        permanent: false,
      },
      {
        source: "/:locale(id|en)/dashboard/sparepart-requests/:path*",
        destination: "/:locale/maintenance/sparepart-requests/:path*",
        permanent: false,
      },
      {
        source: "/:locale(id|en)/dashboard/stock/:path*",
        destination: "/:locale/maintenance/stock/:path*",
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
      // Maintenance areas keep their files under `(main)/dashboard/*`; these
      // aliases hide that internal folder from the public URL, same as the
      // dashboard and configuration aliases above. Non-maintenance areas are
      // deliberately still served directly from `/dashboard/*` (phased
      // regroup policy, not a defect).
      {
        source: "/:locale(id|en)/maintenance/workorders/:path*",
        destination: "/:locale/dashboard/workorders/:path*",
      },
      {
        source: "/:locale(id|en)/maintenance/preventive/:path*",
        destination: "/:locale/dashboard/preventive/:path*",
      },
      {
        source: "/:locale(id|en)/maintenance/sparepart-requests/:path*",
        destination: "/:locale/dashboard/sparepart-requests/:path*",
      },
      {
        source: "/:locale(id|en)/maintenance/stock/:path*",
        destination: "/:locale/dashboard/stock/:path*",
      },
      { source: "/:locale(id|en)/waha-templates", destination: "/:locale/dashboard/waha-templates" },
      { source: "/:locale(id|en)/audit-log", destination: "/:locale/dashboard/audit-log" },
      { source: "/:locale(id|en)/system-health", destination: "/:locale/dashboard/system-health" },
      { source: "/:locale(id|en)/settings/:path*", destination: "/:locale/dashboard/settings/:path*" },
    ];
  },
};

export default withNextIntl(nextConfig);
