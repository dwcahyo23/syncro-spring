"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";

import { RoleGuard } from "@/components/syncro/role-guard";
import { MachineHubPageContent } from "@/features/machine-hub/machine-hub-page-content";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGE", "VIEWER"]} title="Machine Hub">
      <MachineHubPageContent />
    </RoleGuard>
  );
}
