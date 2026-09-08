import { redirect } from "@/i18n/navigation";

/** Superseded by the consolidated Master Data → Organization tab page (Teams tab). */
export default async function OldTeamsPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  redirect({ href: "/master-data/organization?tab=teams", locale });
}
