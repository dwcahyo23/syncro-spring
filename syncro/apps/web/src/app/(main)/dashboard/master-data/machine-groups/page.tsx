import { ModulePlaceholder } from "@/components/syncro/module-placeholder";
import { RoleGuard } from "@/components/syncro/role-guard";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGE"]} title="Machine Groups">
      <ModulePlaceholder
        title="Machine Groups"
        description="Plant-scoped process line management shell."
        sections={["Plant filter", "Machine group list", "Duplicate-name validation"]}
      />
    </RoleGuard>
  );
}
