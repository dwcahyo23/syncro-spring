import { RoleGuard } from "@/components/syncro/role-guard";
import { MachineGroupManagement } from "@/features/master-data/machine-groups/machine-group-management";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGER_MAINTENANCE", "AUDITOR"]} title="Machine Groups">
      <MachineGroupManagement />
    </RoleGuard>
  );
}
