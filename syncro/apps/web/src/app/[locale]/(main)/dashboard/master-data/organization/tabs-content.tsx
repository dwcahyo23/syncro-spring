"use client";

import { useSearchParams } from "next/navigation";

import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { PlantManagement } from "@/features/master-data/plants/plant-management";
import { ResponsibilityManagement } from "@/features/master-data/responsibilities/responsibility-management";
import { SectionManagement } from "@/features/master-data/sections/section-management";
import { TeamManagement } from "@/features/master-data/teams/team-management";
import { DepartmentManagement } from "@/features/organization/components/department-management";
import { RoleMapping } from "@/features/organization/components/role-mapping";
import { UserManagement } from "@/features/organization/components/user-management";

const TABS = ["departments", "users", "roles", "sections", "teams", "responsibility", "plants"] as const;
const TAB_LABELS: Record<(typeof TABS)[number], string> = {
  departments: "Departments",
  users: "Users",
  roles: "Roles",
  sections: "Sections",
  teams: "Teams",
  responsibility: "Responsibility",
  plants: "Plants",
};

export function OrganizationTabsContent() {
  const searchParams = useSearchParams();
  const tab = searchParams.get("tab");
  const defaultTab = tab && TABS.includes(tab as (typeof TABS)[number]) ? tab : "departments";

  return (
    <Tabs defaultValue={defaultTab} className="w-full">
      <TabsList className="mb-4">
        {TABS.map((value) => (
          <TabsTrigger key={value} value={value}>
            {TAB_LABELS[value]}
          </TabsTrigger>
        ))}
      </TabsList>
      <TabsContent value="departments">
        <DepartmentManagement />
      </TabsContent>
      <TabsContent value="users">
        <UserManagement />
      </TabsContent>
      <TabsContent value="roles">
        <RoleMapping />
      </TabsContent>
      <TabsContent value="sections">
        <SectionManagement />
      </TabsContent>
      <TabsContent value="teams">
        <TeamManagement />
      </TabsContent>
      <TabsContent value="responsibility">
        <ResponsibilityManagement />
      </TabsContent>
      <TabsContent value="plants">
        <PlantManagement />
      </TabsContent>
    </Tabs>
  );
}
