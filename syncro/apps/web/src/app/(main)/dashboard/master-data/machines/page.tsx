import { RoleGuard } from "@/components/syncro/role-guard";
import { MachineManagement } from "@/features/master-data/machines/machine-management";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGE", "VIEWER"]} title="Machines">
      <MachineManagement />
    </RoleGuard>
  );
}
