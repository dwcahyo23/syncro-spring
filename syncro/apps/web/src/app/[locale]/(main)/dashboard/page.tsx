import { redirect } from "@/i18n/navigation";

export default async function DashboardPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  redirect({ href: "/operations-overview", locale });
}
