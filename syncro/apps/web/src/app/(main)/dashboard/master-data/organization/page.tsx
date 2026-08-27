import { Suspense } from "react";

import { RoleGuard } from "@/components/syncro/role-guard";

import { OrganizationTabsContent } from "./tabs-content";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGER_MAINTENANCE", "AUDITOR"]} title="Organization">
      <div className="space-y-6">
        <Suspense fallback={<div className="p-4 text-muted-foreground">Loading organization...</div>}>
          <OrganizationTabsContent />
        </Suspense>
      </div>
    </RoleGuard>
  );
}
