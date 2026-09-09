import { ChevronLeftIcon, ChevronRightIcon, ChevronsLeftIcon, ChevronsRightIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { Button } from "@/components/ui/button";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

export interface DataTablePaginationProps {
  page: number;
  size: number;
  totalElements?: number;
  onPageChange: (page: number) => void;
  onSizeChange: (size: number) => void;
  pageSizeOptions?: number[];
}

export function DataTablePagination({
  page,
  size,
  totalElements = 0,
  onPageChange,
  onSizeChange,
  pageSizeOptions = [10, 15, 20, 50, 100],
}: DataTablePaginationProps) {
  const t = useTranslations("ui.pagination")
  const pageCount = Math.ceil(totalElements / size);
  const startItem = totalElements === 0 ? 0 : page * size + 1;
  const endItem = Math.min((page + 1) * size, totalElements);

  return (
    <div className="flex flex-col items-center justify-between gap-4 px-2 py-4 sm:flex-row">
      <div className="text-muted-foreground text-sm">
        {t("showing", { start: startItem, end: endItem, total: totalElements })}
      </div>
      <div className="flex flex-col items-center gap-4 sm:flex-row sm:gap-6 lg:gap-8">
        <div className="flex items-center gap-2">
          <p className="font-medium text-sm">{t("rowsPerPage")}</p>
          <Select
            value={`${size}`}
            onValueChange={(value) => {
              onSizeChange(Number(value));
            }}
          >
            <SelectTrigger className="h-8 w-[70px]">
              <SelectValue placeholder={size} />
            </SelectTrigger>
            <SelectContent side="top">
              {pageSizeOptions.map((pageSize) => (
                <SelectItem key={pageSize} value={`${pageSize}`}>
                  {pageSize}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="flex w-[100px] items-center justify-center font-medium text-sm">
          {t("pageOf", { page: page + 1, total: Math.max(1, pageCount) })}
        </div>
        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            className="hidden size-8 p-0 lg:flex"
            onClick={() => onPageChange(0)}
            disabled={page === 0}
          >
            <span className="sr-only">{t("firstPage")}</span>
            <ChevronsLeftIcon className="size-4" />
          </Button>
          <Button
            variant="outline"
            className="size-8 p-0"
            onClick={() => onPageChange(page - 1)}
            disabled={page === 0}
          >
            <span className="sr-only">{t("previousPage")}</span>
            <ChevronLeftIcon className="size-4" />
          </Button>
          <Button
            variant="outline"
            className="size-8 p-0"
            onClick={() => onPageChange(page + 1)}
            disabled={page >= pageCount - 1}
          >
            <span className="sr-only">{t("nextPage")}</span>
            <ChevronRightIcon className="size-4" />
          </Button>
          <Button
            variant="outline"
            className="hidden size-8 p-0 lg:flex"
            onClick={() => onPageChange(pageCount - 1)}
            disabled={page >= pageCount - 1 || pageCount === 0}
          >
            <span className="sr-only">{t("lastPage")}</span>
            <ChevronsRightIcon className="size-4" />
          </Button>
        </div>
      </div>
    </div>
  );
}
