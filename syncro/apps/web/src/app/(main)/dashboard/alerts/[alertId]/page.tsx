import { AlertDetailPageContent } from "@/features/alerts/alert-detail-page-content";

interface PageProps {
  params: Promise<{ alertId: string }>;
}

export default async function Page({ params }: PageProps) {
  const { alertId } = await params;
  return (
    <div className="mx-auto max-w-3xl space-y-4 py-2">
      <AlertDetailPageContent alertId={alertId} />
    </div>
  );
}
