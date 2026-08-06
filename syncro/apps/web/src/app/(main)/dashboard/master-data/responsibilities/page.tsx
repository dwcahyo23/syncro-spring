import { ResponsibilityManagement } from '@/features/master-data/responsibilities/responsibility-management';
import { Metadata } from 'next';

export const metadata: Metadata = {
  title: 'Machine Responsibilities | Syncro',
  description: 'Manage machine responsibility assignments',
};

export default function ResponsibilitiesPage() {
  return (
    <div className="flex flex-col gap-6 w-full">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Machine Responsibilities</h1>
        <p className="text-muted-foreground">Assign responsibility levels to users for specific machines.</p>
      </div>
      <ResponsibilityManagement />
    </div>
  );
}
