import { WahaTemplatePageContent } from "@/features/waha-templates/waha-template-page-content";

export default function Page() {
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-xl font-semibold">WAHA Templates</h1>
        <p className="text-sm text-muted-foreground">
          WhatsApp alert message templates for sparepart threshold notifications.
        </p>
      </div>
      <WahaTemplatePageContent />
    </div>
  );
}
