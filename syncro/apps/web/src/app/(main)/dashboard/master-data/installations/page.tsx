import { RoleGuard } from "@/components/syncro/role-guard";
import { InstallationManagement } from "@/features/master-data/installations/installation-management";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGE", "VIEWER"]} title="Installations">
      <InstallationManagement />
    </RoleGuard>
  );
}
