import { redirect } from "@/i18n/navigation";

/** Superseded by the consolidated Master Data → Organization tab page (Responsibility tab). */
export default async function OldResponsibilitiesPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  redirect({ href: "/master-data/organization?tab=responsibility", locale });
}
