import { redirect } from "@/i18n/navigation";

/** Superseded by the dashboard-shell route at /dashboard/workorders. */
export default async function OldKanbanPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  redirect({ href: "/dashboard/workorders", locale });
}
