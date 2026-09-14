import { redirect } from "@/i18n/navigation";

/** Legacy alias: superseded by the canonical /maintenance/sparepart-requests URL. */
export default async function OldSparepartRequestsPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  redirect({ href: "/maintenance/sparepart-requests", locale });
}
