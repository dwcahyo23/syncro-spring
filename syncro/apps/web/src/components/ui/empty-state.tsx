import { AlertCircleIcon } from "lucide-react";

import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "./empty";

export interface EmptyStateProps {
  title: string;
  description?: string;
}

export function EmptyState({ title, description }: EmptyStateProps) {
  return (
    <Empty className="w-full">
      <EmptyHeader className="items-center gap-2 text-center md:text-left">
        <EmptyMedia variant="icon">
          <AlertCircleIcon className="size-5" />
        </EmptyMedia>
        <div className="flex flex-col items-start gap-2 text-left">
          <EmptyTitle>{title}</EmptyTitle>
          {description && <EmptyDescription>{description}</EmptyDescription>}
        </div>
      </EmptyHeader>
    </Empty>
  );
}
