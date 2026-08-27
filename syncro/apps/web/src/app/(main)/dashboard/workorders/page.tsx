import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { KanbanBoard } from "@/features/workorders/components/kanban-board";
import { RatingsPageContent } from "@/features/workorders/components/ratings-page-content";

/**
 * Workorders route (Epic 10). Lives inside the dashboard shell. Kanban and ratings are
 * two tabs of the same menu, not separate nav items.
 */
export default function WorkordersPage() {
  return (
    <Tabs defaultValue="kanban" className="w-full">
      <div className="flex items-end justify-between gap-4 px-6 pt-6">
        <div>
          <h1 className="font-semibold text-xl">Work Orders</h1>
          <p className="text-muted-foreground text-sm">
            Track open workorders by status and rate technician performance.
          </p>
        </div>
        <TabsList>
          <TabsTrigger value="kanban">Kanban</TabsTrigger>
          <TabsTrigger value="ratings">Ratings</TabsTrigger>
        </TabsList>
      </div>
      <div className="p-6">
        <TabsContent value="kanban">
          <KanbanBoard />
        </TabsContent>
        <TabsContent value="ratings">
          <RatingsPageContent />
        </TabsContent>
      </div>
    </Tabs>
  );
}
