import { ModulePlaceholder } from "@/components/syncro/module-placeholder";
import { RoleGuard } from "@/components/syncro/role-guard";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGE"]} title="Plants">
      <ModulePlaceholder
        title="Plants"
        description="Plant management shell for future create, edit, and view workflows."
        sections={["Plant list", "Create plant", "Validation states"]}
      />
    </RoleGuard>
  );
}
