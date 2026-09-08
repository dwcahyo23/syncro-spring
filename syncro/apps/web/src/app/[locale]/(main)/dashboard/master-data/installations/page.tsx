import { redirect } from "@/i18n/navigation";

/** Superseded by the consolidated Master Data → Machines tab page (Installations tab). */
export default async function OldInstallationsPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  redirect({ href: "/master-data/machines?tab=installations", locale });
}
