import { redirect } from "@/i18n/navigation";

/** Superseded by the consolidated Master Data → Organization tab page (Sections tab). */
export default async function OldSectionsPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  redirect({ href: "/master-data/organization?tab=sections", locale });
}
