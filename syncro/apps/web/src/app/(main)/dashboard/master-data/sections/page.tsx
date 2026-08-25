import { RoleGuard } from "@/components/syncro/role-guard";
import { SectionManagement } from "@/features/master-data/sections/section-management";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGER_MAINTENANCE", "AUDITOR"]} title="Sections">
      <SectionManagement />
    </RoleGuard>
  );
}
