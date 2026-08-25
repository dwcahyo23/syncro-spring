import { RoleGuard } from "@/components/syncro/role-guard";
import { MachineManagement } from "@/features/master-data/machines/machine-management";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGER_MAINTENANCE", "AUDITOR"]} title="Machines">
      <MachineManagement />
    </RoleGuard>
  );
}
