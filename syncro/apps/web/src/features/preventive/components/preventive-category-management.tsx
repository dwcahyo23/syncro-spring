"use client";

import { useTranslations } from "next-intl";

import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

/**
 * Preventive program categories (FR-130, story 11-1). Currently a fixed enum
 * (MECHANICAL/ELECTRICAL) enforced by a DB CHECK constraint — shown read-only here.
 * Convert to a CRUD master table when preventive categories need to be configurable.
 * Codes are the contract; display labels come from `preventive.category.<CODE>`.
 */
const PREVENTIVE_CATEGORIES = ["MECHANICAL", "ELECTRICAL"] as const;

export function PreventiveCategoryManagement() {
  const t = useTranslations("preventive");
  const tc = useTranslations("common");
  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("categories.title")}</CardTitle>
      </CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{tc("code")}</TableHead>
              <TableHead>{t("categories.label")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {PREVENTIVE_CATEGORIES.map((code) => (
              <TableRow key={code}>
                <TableCell>
                  <Badge variant="outline">{code}</Badge>
                </TableCell>
                <TableCell className="text-sm">{t(`category.${code}`)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}
