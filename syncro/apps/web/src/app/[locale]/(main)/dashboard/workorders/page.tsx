import { getTranslations } from "next-intl/server";

import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { AckTaskList } from "@/features/workorders/components/ack-task-list";
import { KanbanBoard } from "@/features/workorders/components/kanban-board";
import { RatingsPageContent } from "@/features/workorders/components/ratings-page-content";
import { WorkOrderCategoryManagement } from "@/features/workorders/components/workorder-category-management";
import { WorkorderTable } from "@/features/workorders/components/workorder-table";

/**
 * Workorders route (Epic 10). Lives inside the dashboard shell. The workorder table is
 * the default tab (workorder-table story); kanban, ratings, category master data and the
 * 4-hour ack task list (story 14-4, FR-181) remain accessible as secondary tabs.
 */
export default async function WorkordersPage() {
  const t = await getTranslations("workOrders.page");
  return (
    <Tabs defaultValue="table" className="w-full">
      <div className="flex items-end justify-between gap-4 px-6 pt-6">
        <div>
          <h1 className="font-semibold text-xl">{t("title")}</h1>
          <p className="text-muted-foreground text-sm">{t("subtitle")}</p>
        </div>
        <TabsList>
          <TabsTrigger value="table">{t("tabTable")}</TabsTrigger>
          <TabsTrigger value="kanban">{t("tabKanban")}</TabsTrigger>
          <TabsTrigger value="ratings">{t("tabRatings")}</TabsTrigger>
          <TabsTrigger value="ack-task-list">{t("tabAckTaskList")}</TabsTrigger>
          <TabsTrigger value="categories">{t("tabCategories")}</TabsTrigger>
        </TabsList>
      </div>
      <div className="p-6">
        <TabsContent value="table">
          <WorkorderTable />
        </TabsContent>
        <TabsContent value="kanban">
          <KanbanBoard />
        </TabsContent>
        <TabsContent value="ratings">
          <RatingsPageContent />
        </TabsContent>
        <TabsContent value="ack-task-list">
          <AckTaskList />
        </TabsContent>
        <TabsContent value="categories">
          <WorkOrderCategoryManagement />
        </TabsContent>
      </div>
    </Tabs>
  );
}
