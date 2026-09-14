import { redirect } from "@/i18n/navigation";

/** Legacy alias: superseded by the canonical /maintenance/preventive URL. */
export default async function OldPreventivePage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  redirect({ href: "/maintenance/preventive", locale });
}
