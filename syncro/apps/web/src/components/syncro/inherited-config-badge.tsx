import { Badge } from "@/components/ui/badge";

export interface InheritedConfigBadgeProps {
  source?: string;
}

/** States group inheritance for machine-level config resolved by the backend. */
export function InheritedConfigBadge({ source }: InheritedConfigBadgeProps) {
  if (source !== "MACHINE_GROUP") {
    return null;
  }
  return <Badge variant="secondary">Inherited from group</Badge>;
}
