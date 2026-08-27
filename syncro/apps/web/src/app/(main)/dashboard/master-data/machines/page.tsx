import { Suspense } from "react";

import { RoleGuard } from "@/components/syncro/role-guard";

import { MachinesTabsContent } from "./tabs-content";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGER_MAINTENANCE", "AUDITOR"]} title="Machines">
      <div className="space-y-6">
        <Suspense fallback={<div className="p-4 text-muted-foreground">Loading machines...</div>}>
          <MachinesTabsContent />
        </Suspense>
      </div>
    </RoleGuard>
  );
}
