import { PackageSearch } from "lucide-react";

import { Card, CardContent } from "@/components/ui/card";

/**
 * Sparepart requests route (story 12-1). Placeholder until the backend list endpoint
 * lands in 12-2; keeps the nav item live and consistent with the dashboard shell.
 */
export default function SparepartRequestsPage() {
  return (
    <div className="p-6">
      <div className="flex items-end justify-between gap-4">
        <div>
          <h1 className="font-semibold text-xl">Sparepart Requests</h1>
          <p className="text-muted-foreground text-sm">
            Track sparepart, consumable, and external-service requests against workorders.
          </p>
        </div>
      </div>
      <Card className="mt-6">
        <CardContent className="flex flex-col items-center justify-center gap-3 py-16 text-center">
          <PackageSearch className="size-10 text-muted-foreground" />
          <div>
            <p className="font-medium">No requests to display yet.</p>
            <p className="text-muted-foreground text-sm">
              The request list will appear here once the request state machine is wired up.
            </p>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
