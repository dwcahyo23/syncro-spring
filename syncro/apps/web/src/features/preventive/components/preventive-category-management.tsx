"use client";

import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

/**
 * Preventive program categories (FR-130, story 11-1). Currently a fixed enum
 * (MECHANICAL/ELECTRICAL) enforced by a DB CHECK constraint — shown read-only here.
 * Convert to a CRUD master table when preventive categories need to be configurable.
 */
const PREVENTIVE_CATEGORIES = [
  { code: "MECHANICAL", label: "Mechanical" },
  { code: "ELECTRICAL", label: "Electrical" },
];

export function PreventiveCategoryManagement() {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Preventive Categories</CardTitle>
      </CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Code</TableHead>
              <TableHead>Label</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {PREVENTIVE_CATEGORIES.map((category) => (
              <TableRow key={category.code}>
                <TableCell>
                  <Badge variant="outline">{category.code}</Badge>
                </TableCell>
                <TableCell className="text-sm">{category.label}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}