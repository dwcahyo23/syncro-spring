import { RoleGuard } from "@/components/syncro/role-guard";
import { MachineGroupManagement } from "@/features/master-data/machine-groups/machine-group-management";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGE", "VIEWER"]} title="Machine Groups">
      <MachineGroupManagement />
    </RoleGuard>
  );
}
