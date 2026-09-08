import { redirect } from "@/i18n/navigation";

/** Superseded by the consolidated Master Data → Organization tab page (Plants tab). */
export default async function OldSetupPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  redirect({ href: "/master-data/organization?tab=plants", locale });
}
