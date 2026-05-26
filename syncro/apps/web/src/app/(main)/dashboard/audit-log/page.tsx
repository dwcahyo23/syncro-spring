import { ModulePlaceholder } from "@/components/syncro/module-placeholder";
import { RoleGuard } from "@/components/syncro/role-guard";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGE"]} title="Audit Log">
      <ModulePlaceholder
        title="Audit Log"
        description="Immutable master data change history shell."
        sections={["Entity filters", "Actor filters", "Before/after detail"]}
      />
    </RoleGuard>
  );
}
