"use client";

import { CheckCircle2, Circle } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { RequestPartDialog } from "@/features/sparepart-requests/components/request-part-dialog";
import type { WorkOrderKanbanItem } from "@/features/workorders/types";

export interface WorkOrderCardProps {
  item: WorkOrderKanbanItem;
}

export function WorkOrderCard({ item }: WorkOrderCardProps) {
  const doneTodos = item.todos.filter((todo) => todo.status === "COMPLETED").length;
  const cancelledTodos = item.todos.filter((todo) => todo.status === "CANCELLED").length;
  const totalTodos = item.todos.length;

  return (
    <Card className="shadow-sm">
      <CardHeader className="space-y-1 p-3">
        <div className="flex items-center justify-between gap-2">
          <CardTitle className="font-medium text-sm leading-tight">{item.id}</CardTitle>
          {item.categoryCode ? <Badge variant="outline">{item.categoryCode}</Badge> : null}
        </div>
        <p className="line-clamp-2 text-muted-foreground text-xs">{item.description ?? "No description"}</p>
      </CardHeader>
      <CardContent className="p-3 pt-0">
        {item.todos.length > 0 ? (
          <ul className="space-y-1">
            {item.todos.map((todo) => {
              const done = todo.status === "COMPLETED";
              return (
                <li key={todo.id} className="flex items-start gap-1.5 text-xs">
                  {done ? (
                    <CheckCircle2 className="mt-0.5 h-3.5 w-3.5 shrink-0 text-chart-3" />
                  ) : (
                    <Circle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-muted-foreground" />
                  )}
                  <span className={done ? "text-muted-foreground line-through" : undefined}>{todo.title}</span>
                </li>
              );
            })}
          </ul>
        ) : (
          <p className="text-muted-foreground text-xs">No todos</p>
        )}
        <p className="mt-2 text-xs text-muted-foreground">
          {doneTodos}/{totalTodos} done
          {cancelledTodos > 0 ? ` · ${cancelledTodos} cancelled` : ""}
        </p>
        <div className="mt-2">
          <RequestPartDialog workOrderId={item.id} />
        </div>
      </CardContent>
    </Card>
  );
}
