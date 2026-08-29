"use client";

import Link from "next/link";
import { useParams } from "next/navigation";

import { RoleGuard } from "@/components/syncro/role-guard";
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from "@/components/ui/breadcrumb";
import { MachineHubPageContent } from "@/features/machine-hub/machine-hub-page-content";

/**
 * Machine hub resolved by machineId (DW-71). Machine codes are unique per plant only,
 * so code-only routes are ambiguous across plants; links that carry a machineId
 * (e.g. the system-health stale-machine list) must target this route.
 */
export default function Page() {
  const params = useParams();
  const machineId = String(params.machineId ?? "");

  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGER_MAINTENANCE", "AUDITOR"]} title="Machine Hub">
      <div className="flex flex-col gap-6">
        <Breadcrumb>
          <BreadcrumbList>
            <BreadcrumbItem>
              <BreadcrumbLink asChild>
                <Link href="/dashboard">Dashboard</Link>
              </BreadcrumbLink>
            </BreadcrumbItem>
            <BreadcrumbSeparator />
            <BreadcrumbItem>
              <BreadcrumbLink asChild>
                <Link href="/dashboard/master-data/machines">Machines</Link>
              </BreadcrumbLink>
            </BreadcrumbItem>
            <BreadcrumbSeparator />
            <BreadcrumbItem>
              <BreadcrumbPage>Machine detail</BreadcrumbPage>
            </BreadcrumbItem>
          </BreadcrumbList>
        </Breadcrumb>
        <MachineHubPageContent machineId={machineId} />
      </div>
    </RoleGuard>
  );
}
