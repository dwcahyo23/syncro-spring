import { redirect } from "@/i18n/navigation";

/** Superseded by the dashboard-shell route at /dashboard/sparepart-requests. */
export default async function OldSparepartRequestsPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  redirect({ href: "/dashboard/sparepart-requests", locale });
}
