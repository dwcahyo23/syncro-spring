import { RoleGuard } from "@/components/syncro/role-guard";
import { SectionManagement } from "@/features/master-data/sections/section-management";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGE", "VIEWER"]} title="Sections">
      <SectionManagement />
    </RoleGuard>
  );
}
