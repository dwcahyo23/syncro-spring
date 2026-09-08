"use client";

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
import { Link } from "@/i18n/navigation";

export default function Page() {
  const params = useParams();
  const machineCode = decodeURIComponent(String(params.machineCode ?? ""));

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
              <BreadcrumbPage>{machineCode}</BreadcrumbPage>
            </BreadcrumbItem>
          </BreadcrumbList>
        </Breadcrumb>
        <MachineHubPageContent machineCode={machineCode} />
      </div>
    </RoleGuard>
  );
}
