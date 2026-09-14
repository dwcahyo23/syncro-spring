import { redirect } from "@/i18n/navigation";

/** Legacy alias: superseded by the canonical /maintenance/workorders URL. */
export default async function OldKanbanPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  redirect({ href: "/maintenance/workorders", locale });
}
