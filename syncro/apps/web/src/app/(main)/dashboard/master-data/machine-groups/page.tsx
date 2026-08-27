import { redirect } from "next/navigation";

/** Superseded by the consolidated Master Data → Machines tab page (Machine Groups tab). */
export default function OldMachineGroupsPage() {
  redirect("/master-data/machines");
}
