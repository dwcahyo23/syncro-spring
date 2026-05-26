import { RoleGuard } from "@/components/syncro/role-guard";
import { PlantScopedModulePlaceholder } from "@/features/plant-scope/plant-scoped-module-placeholder";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGE"]} title="Machines">
      <PlantScopedModulePlaceholder
        title="Machines"
        description="Machine management shell and future Machine Hub entry point."
        sections={["Machine list", "Manual active state", "Machine detail links"]}
      />
    </RoleGuard>
  );
}
