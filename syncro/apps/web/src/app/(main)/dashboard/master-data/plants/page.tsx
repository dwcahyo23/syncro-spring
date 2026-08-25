import { RoleGuard } from "@/components/syncro/role-guard";
import { PlantManagement } from "@/features/master-data/plants/plant-management";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGER_MAINTENANCE", "AUDITOR"]} title="Plants">
      <PlantManagement />
    </RoleGuard>
  );
}
