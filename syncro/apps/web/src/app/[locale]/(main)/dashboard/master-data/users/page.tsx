import { redirect } from "@/i18n/navigation";

/** Superseded by the consolidated Master Data → Organization tab page (Users tab). */
export default async function OldUsersPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  redirect({ href: "/master-data/organization?tab=users", locale });
}
