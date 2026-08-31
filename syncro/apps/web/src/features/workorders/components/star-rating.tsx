"use client";

import { Star } from "lucide-react";

import { cn } from "@/lib/utils";

export interface StarRatingProps {
  value: number;
  onChange?: (value: number) => void;
  readOnly?: boolean;
  label: string;
}

/**
 * 5-star clickable control (story 10-8, FR-121/FR-124). Small local component using
 * Lucide Star — no third-party rating widget dependency (spec Never section). When
 * readOnly it renders the current value for an already-submitted rating. Each star is
 * a toggle button with {@code aria-pressed} — an accessible pattern for a rating input.
 */
export function StarRating({ value, onChange, readOnly = false, label }: StarRatingProps) {
  return (
    <fieldset className="inline-flex items-center gap-0.5 border-0 p-0">
      <legend className="sr-only">{label}</legend>
      {[1, 2, 3, 4, 5].map((star) => {
        const filled = star <= value;
        return (
          <button
            key={star}
            type="button"
            disabled={readOnly}
            aria-label={`${label}: ${star} star${star > 1 ? "s" : ""}`}
            aria-pressed={filled}
            tabIndex={readOnly ? -1 : 0}
            className={cn(
              "status-icon-warning rounded-sm p-0.5 transition-colors",
              !readOnly && "hover:scale-110 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
            )}
            onClick={() => onChange?.(star)}
          >
            <Star className={cn("h-4 w-4", !filled && "fill-transparent text-muted-foreground/50")} />
          </button>
        );
      })}
    </fieldset>
  );
}
