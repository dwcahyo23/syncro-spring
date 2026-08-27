import { RoleGuard } from "@/components/syncro/role-guard";
import { UserManagement } from "@/features/organization/components/user-management";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGER_MAINTENANCE", "AUDITOR"]} title="Users">
      <UserManagement />
    </RoleGuard>
  );
}
