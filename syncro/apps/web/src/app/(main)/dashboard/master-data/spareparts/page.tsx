import { ModulePlaceholder } from "@/components/syncro/module-placeholder";
import { RoleGuard } from "@/components/syncro/role-guard";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGE"]} title="Spareparts">
      <ModulePlaceholder
        title="Spareparts"
        description="Sparepart master data shell for taxonomy-backed spareparts."
        sections={["Taxonomy references", "Sparepart list", "Dense table placeholder"]}
      />
    </RoleGuard>
  );
}
