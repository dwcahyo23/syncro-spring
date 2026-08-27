import { redirect } from "next/navigation";

/** Superseded by the consolidated Master Data → Machines tab page (Installations tab). */
export default function OldInstallationsPage() {
  redirect("/master-data/machines?tab=installations");
}
