import { redirect } from "@/i18n/navigation";

/** Superseded by the consolidated Master Data → Machines tab page (Machine Groups tab). */
export default async function OldMachineGroupsPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  redirect({ href: "/master-data/machines", locale });
}
