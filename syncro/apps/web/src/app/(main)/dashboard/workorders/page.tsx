import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { KanbanBoard } from "@/features/workorders/components/kanban-board";
import { RatingsPageContent } from "@/features/workorders/components/ratings-page-content";
import { WorkOrderCategoryManagement } from "@/features/workorders/components/workorder-category-management";
import { WorkorderTable } from "@/features/workorders/components/workorder-table";

/**
 * Workorders route (Epic 10). Lives inside the dashboard shell. The workorder table is
 * the default tab (workorder-table story); kanban, ratings and category master data
 * remain accessible as secondary tabs.
 */
export default function WorkordersPage() {
  return (
    <Tabs defaultValue="table" className="w-full">
      <div className="flex items-end justify-between gap-4 px-6 pt-6">
        <div>
          <h1 className="font-semibold text-xl">Work Orders</h1>
          <p className="text-muted-foreground text-sm">View, filter, and manage maintenance workorders.</p>
        </div>
        <TabsList>
          <TabsTrigger value="table">Table</TabsTrigger>
          <TabsTrigger value="kanban">Kanban</TabsTrigger>
          <TabsTrigger value="ratings">Ratings</TabsTrigger>
          <TabsTrigger value="categories">Categories</TabsTrigger>
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
        <TabsContent value="categories">
          <WorkOrderCategoryManagement />
        </TabsContent>
      </div>
    </Tabs>
  );
}
