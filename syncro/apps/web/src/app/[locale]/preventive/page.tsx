import { redirect } from "@/i18n/navigation";

/** Superseded by the dashboard-shell route at /dashboard/preventive. */
export default async function OldPreventivePage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  redirect({ href: "/dashboard/preventive", locale });
}
