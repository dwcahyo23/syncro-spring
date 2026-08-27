import { RoleGuard } from "@/components/syncro/role-guard";
import { DepartmentManagement } from "@/features/organization/components/department-management";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGER_MAINTENANCE", "AUDITOR"]} title="Departments">
      <DepartmentManagement />
    </RoleGuard>
  );
}
