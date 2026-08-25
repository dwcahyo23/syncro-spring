import { RoleGuard } from "@/components/syncro/role-guard";
import { InstallationManagement } from "@/features/master-data/installations/installation-management";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGER_MAINTENANCE", "AUDITOR"]} title="Installations">
      <InstallationManagement />
    </RoleGuard>
  );
}
