import { AlertListPageContent } from "@/features/alerts/alert-list-page-content";

export default function Page() {
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-xl font-semibold">Alerts</h1>
        <p className="text-sm text-muted-foreground">
          Sparepart lifetime threshold alerts across all machines.
        </p>
      </div>
      <AlertListPageContent />
    </div>
  );
}
