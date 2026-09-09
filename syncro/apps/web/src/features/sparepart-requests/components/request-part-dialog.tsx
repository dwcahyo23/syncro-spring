"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { useQuery } from "@tanstack/react-query";
import { PackageSearchIcon, PlusIcon, ShoppingCartIcon, Trash2Icon } from "lucide-react";
import { useFormatter, useTranslations } from "next-intl";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Textarea } from "@/components/ui/textarea";
import { useCreateSparepartRequest } from "@/features/sparepart-requests/hooks/use-sparepart-requests";
import type { SparepartRequestType } from "@/features/sparepart-requests/types";
import type { SparepartTaxonomyView } from "@/lib/api/generated/model";
import { SparepartTaxonomyRequestDimension } from "@/lib/api/generated/model";
import { useCreateSparepartTaxonomy, useListSparepartTaxonomies } from "@/lib/api/generated/syncro";
import { syncroFetch } from "@/lib/api/orval-mutator";

interface CartItem {
  key: string;
  requestType: SparepartRequestType;
  /** BOM code (or sparepart id) when a known sparepart is picked; raw material code otherwise. */
  materialCode: string | null;
  quantity: number;
  purchaseUrl: string;
  estUnitPrice: string;
  notes: string;
  sparepartLabel?: string;
  categoryId?: string;
  brandId?: string;
  kindId?: string;
  typeId?: string;
}

let _cartKey = 0;
function nextKey() {
  return `cart-${++_cartKey}`;
}

/**
 * Upgraded request-part dialog (2026-08-28): searchable BOM lookup + local draft cart.
 * Items sit in the cart until the user clicks "Send to warehouse" — no premature
 * submission. Unknown parts (no material code) start PENDING_COMPLETION per FR-144.
 */
export function RequestPartDialog({
  workOrderId,
  open: controlledOpen,
  onOpenChange: controlledOnOpenChange,
}: {
  workOrderId?: string | null;
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
}) {
  const t = useTranslations("sparepartRequests");
  const tc = useTranslations("common");
  const format = useFormatter();
  const createRequest = useCreateSparepartRequest();
  const createTaxonomy = useCreateSparepartTaxonomy();
  const [internalOpen, setInternalOpen] = useState(false);
  const open = controlledOpen ?? internalOpen;
  const setOpen = controlledOnOpenChange ?? setInternalOpen;

  // ── Cart state ──────────────────────────────────────────────────────
  const [cart, setCart] = useState<CartItem[]>([]);

  // ── Add-item form ───────────────────────────────────────────────────
  const [requestType, setRequestType] = useState<SparepartRequestType>("SPAREPART");
  const [materialCode, setMaterialCode] = useState("");
  const [quantity, setQuantity] = useState(1);
  const [purchaseUrl, setPurchaseUrl] = useState("");
  const [estUnitPrice, setEstUnitPrice] = useState("");
  const [notes, setNotes] = useState("");
  const [searchQuery, setSearchQuery] = useState("");
  const [debouncedSearch, setDebouncedSearch] = useState("");
  const searchTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [categoryId, setCategoryId] = useState("");
  const [brandId, setBrandId] = useState("");
  const [kindId, setKindId] = useState("");
  const [typeId, setTypeId] = useState("");
  const { data: taxonomyRes } = useListSparepartTaxonomies(undefined, {
    query: { enabled: open, staleTime: 60_000 },
  });
  const taxonomyItems = (taxonomyRes?.data?.items ?? []) as SparepartTaxonomyView[];

  const handleSearchChange = useCallback((value: string) => {
    setSearchQuery(value);
    clearTimeout(searchTimerRef.current ?? undefined);
    searchTimerRef.current = setTimeout(() => {
      setDebouncedSearch(value);
    }, 300);
  }, []);

  // Cleanup the pending debounce timer on unmount.
  useEffect(() => {
    return () => clearTimeout(searchTimerRef.current ?? undefined);
  }, []);

  // ── BOM search ──────────────────────────────────────────────────────
  const { data: bomRes, isLoading: bomLoading } = useQuery({
    queryKey: ["/api/v1/spareparts", debouncedSearch],
    queryFn: async () => {
      const params = new URLSearchParams();
      if (debouncedSearch) params.set("search", debouncedSearch);
      params.set("page", "0");
      params.set("size", "20");
      const res = await syncroFetch<{
        data: {
          items: Array<{
            id: string;
            code: string;
            materialCode: string | null;
            kind?: { name: string };
            brand?: { name: string };
          }>;
        };
      }>(`/api/v1/spareparts?${params.toString()}`, { method: "GET" });
      return res.data.items ?? [];
    },
    enabled: open,
    staleTime: 30_000,
  });

  const selectedBom = useMemo(() => {
    if (!materialCode) return null;
    return (bomRes ?? []).find(
      (s) => s.code === materialCode || s.materialCode === materialCode || s.id === materialCode,
    );
  }, [bomRes, materialCode]);

  // Clicking a search result selects the sparepart: keep the BOM code as the
  // material-code input (backend resolves by code) and remember the label.
  const selectBom = (sparepart: {
    id: string;
    code: string;
    materialCode: string | null;
    kind?: { name: string };
    brand?: { name: string };
  }) => {
    setMaterialCode(sparepart.code);
    setSearchQuery("");
    setDebouncedSearch("");
    // An existing BOM is used as-is — taxonomy is only for new parts.
    setCategoryId("");
    setBrandId("");
    setKindId("");
    setTypeId("");
  };

  // ── Taxonomy (new-part creation, DW-148) ─────────────────────────────
  const taxonomyByDimension = useMemo(() => {
    const groups = new Map<string, SparepartTaxonomyView[]>();
    for (const item of taxonomyItems) {
      if (item.id && item.dimension) {
        const list = groups.get(item.dimension) ?? [];
        list.push(item);
        groups.set(item.dimension, list);
      }
    }
    return groups;
  }, [taxonomyItems]);

  const linkedOptions = (dimension: SparepartTaxonomyView["dimension"]) =>
    (taxonomyByDimension.get(dimension ?? "") ?? []).filter((item) => !categoryId || item.categoryId === categoryId);

  const taxonomyCode = (name: string) =>
    name
      .trim()
      .toUpperCase()
      .replace(/[^A-Z0-9]+/g, "_")
      .replace(/^_+|_+$/g, "");

  const handleCreateTaxonomy = async (dimension: SparepartTaxonomyView["dimension"], name: string) => {
    const res = await createTaxonomy.mutateAsync({
      data: {
        dimension: dimension as SparepartTaxonomyRequestDimension,
        code: taxonomyCode(name),
        name: name.trim(),
        categoryId: categoryId || undefined,
      },
    });
    const createdId = res.data.id;
    if (createdId) {
      if (dimension === "BRAND") setBrandId(createdId);
      else if (dimension === "KIND") setKindId(createdId);
      else if (dimension === "TYPE") setTypeId(createdId);
    }
  };

  // ── Reset form ──────────────────────────────────────────────────────
  const resetForm = () => {
    setRequestType("SPAREPART");
    setMaterialCode("");
    setQuantity(1);
    setPurchaseUrl("");
    setEstUnitPrice("");
    setNotes("");
    setSearchQuery("");
    setDebouncedSearch("");
    setCategoryId("");
    setBrandId("");
    setKindId("");
    setTypeId("");
  };

  const resetAll = () => {
    setCart([]);
    resetForm();
  };

  // ── Add to cart ─────────────────────────────────────────────────────
  const addToCart = () => {
    const item: CartItem = {
      key: nextKey(),
      requestType: requestType as SparepartRequestType,
      materialCode: materialCode || null,
      quantity,
      purchaseUrl: purchaseUrl || "",
      estUnitPrice: estUnitPrice || "",
      notes: notes || "",
      sparepartLabel: selectedBom ? `${selectedBom.kind?.name ?? ""} · ${selectedBom.brand?.name ?? ""}` : undefined,
      categoryId: selectedBom ? undefined : categoryId || undefined,
      brandId: selectedBom ? undefined : brandId || undefined,
      kindId: selectedBom ? undefined : kindId || undefined,
      typeId: selectedBom ? undefined : typeId || undefined,
    };
    setCart((prev) => [...prev, item]);
    resetForm();
  };

  const removeFromCart = (key: string) => {
    setCart((prev) => prev.filter((i) => i.key !== key));
  };

  // ── Submit all ──────────────────────────────────────────────────────
  const [submitting, setSubmitting] = useState(false);
  const handleSubmitAll = async () => {
    if (cart.length === 0) return;
    setSubmitting(true);
    // Fire all creates in parallel; failures are handled individually by the hook's toast.
    await Promise.allSettled(
      cart.map((item) =>
        createRequest.mutateAsync({
          requestType: item.requestType,
          workOrderId: workOrderId ?? null,
          machineId: null,
          materialCode: item.materialCode,
          quantity: item.quantity,
          estUnitPrice: item.estUnitPrice ? Number(item.estUnitPrice) : null,
          purchaseReferenceUrl: item.purchaseUrl || null,
          notes: item.notes || null,
          categoryId: item.categoryId,
          brandId: item.brandId,
          kindId: item.kindId,
          typeId: item.typeId,
        }),
      ),
    );
    setSubmitting(false);
    setOpen(false);
    resetAll();
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) resetAll();
      }}
    >
      {controlledOpen === undefined && (
        <DialogTrigger asChild>
          <Button type="button" variant="default" size="sm" className="h-7 px-2 text-xs">
            <PackageSearchIcon className="mr-1 size-3" />
            {t("requestPart.trigger")}
          </Button>
        </DialogTrigger>
      )}
      <DialogContent className="top-4 max-h-[calc(100svh-2rem)] translate-y-0 overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{t("requestPart.title")}</DialogTitle>
          <DialogDescription>{t("requestPart.description")}</DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          {/* ── BOM search ───────────────────────────────── */}
          <div className="space-y-1">
            <Label>{t("requestPart.searchLabel")}</Label>
            <Input
              placeholder={t("requestPart.searchPlaceholder")}
              value={searchQuery}
              onChange={(e) => handleSearchChange(e.target.value)}
            />
            {bomLoading && <Skeleton className="h-8 w-full" />}
            {bomRes && bomRes.length > 0 && debouncedSearch && (
              <div className="max-h-32 overflow-y-auto rounded border">
                {bomRes.map((sp) => (
                  <button
                    key={sp.id}
                    type="button"
                    className="flex w-full items-center justify-between px-3 py-1.5 text-left text-xs hover:bg-muted"
                    onClick={() => selectBom(sp)}
                  >
                    <span className="font-medium">{sp.code}</span>
                    <span className="text-muted-foreground">
                      {sp.kind?.name} · {sp.brand?.name}
                      {sp.materialCode ? t("requestPart.mcInResult", { code: sp.materialCode }) : ""}
                    </span>
                  </button>
                ))}
              </div>
            )}
          </div>

          {selectedBom && (
            <div className="status-banner-healthy rounded-md border px-3 py-2 text-xs">
              <span className="font-medium">{t("requestPart.selected")}</span> {selectedBom.code} ·{" "}
              {selectedBom.kind?.name} · {selectedBom.brand?.name}
              {selectedBom.materialCode ? t("requestPart.mcInSelected", { code: selectedBom.materialCode }) : ""}
            </div>
          )}

          {/* ── New-part taxonomy (DW-148) ───────────────────── */}
          {!selectedBom && (
            <div className="grid grid-cols-2 gap-3 rounded-lg border p-3">
              <p className="col-span-2 text-muted-foreground text-xs">{t("requestPart.noBomHint")}</p>
              <TaxonomySelect
                label={t("taxonomy.category")}
                word={t("taxonomy.categoryWord")}
                value={categoryId}
                items={linkedOptions(SparepartTaxonomyRequestDimension.CATEGORY)}
                creatable={false}
                onChange={(v) => {
                  setCategoryId(v);
                  setBrandId("");
                  setKindId("");
                  setTypeId("");
                }}
              />
              <TaxonomySelect
                label={t("taxonomy.kind")}
                word={t("taxonomy.kindWord")}
                value={kindId}
                items={linkedOptions(SparepartTaxonomyRequestDimension.KIND)}
                creatable={Boolean(categoryId)}
                onCreate={(name) => handleCreateTaxonomy(SparepartTaxonomyRequestDimension.KIND, name)}
                onChange={setKindId}
              />
              <TaxonomySelect
                label={t("taxonomy.brand")}
                word={t("taxonomy.brandWord")}
                value={brandId}
                items={linkedOptions(SparepartTaxonomyRequestDimension.BRAND)}
                creatable={Boolean(categoryId)}
                onCreate={(name) => handleCreateTaxonomy(SparepartTaxonomyRequestDimension.BRAND, name)}
                onChange={setBrandId}
              />
              <TaxonomySelect
                label={t("taxonomy.type")}
                word={t("taxonomy.typeWord")}
                value={typeId}
                items={linkedOptions(SparepartTaxonomyRequestDimension.TYPE)}
                creatable={Boolean(categoryId)}
                onCreate={(name) => handleCreateTaxonomy(SparepartTaxonomyRequestDimension.TYPE, name)}
                onChange={setTypeId}
              />
            </div>
          )}

          {/* ── Add-item form ─────────────────────────────── */}
          <div className="grid grid-cols-2 gap-3 rounded-lg border p-3">
            <div className="space-y-1">
              <Label>{t("type")}</Label>
              <Select value={requestType} onValueChange={(v) => setRequestType(v as SparepartRequestType)}>
                <SelectTrigger className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="SPAREPART">{t("types.SPAREPART")}</SelectItem>
                  <SelectItem value="CONSUMABLE">{t("types.CONSUMABLE")}</SelectItem>
                  <SelectItem value="SERVICE_EXTERNAL">{t("types.SERVICE_EXTERNAL")}</SelectItem>
                </SelectContent>
              </Select>
            </div>

            <div className="space-y-1">
              <Label>{t("requestPart.materialCodeLabel")}</Label>
              <Input
                placeholder={t("requestPart.materialCodePlaceholder")}
                value={materialCode}
                onChange={(e) => setMaterialCode(e.target.value)}
              />
            </div>

            <div className="space-y-1">
              <Label>{t("qty")}</Label>
              <Input type="number" min={1} value={quantity} onChange={(e) => setQuantity(Number(e.target.value))} />
            </div>

            <div className="space-y-1">
              <Label>{t("requestPart.priceLabel")}</Label>
              <Input type="number" min={0} value={estUnitPrice} onChange={(e) => setEstUnitPrice(e.target.value)} />
            </div>

            <div className="space-y-1">
              <Label>{t("requestPart.urlLabel")}</Label>
              <Input
                placeholder={t("requestPart.urlPlaceholder")}
                value={purchaseUrl}
                onChange={(e) => setPurchaseUrl(e.target.value)}
              />
            </div>

            <div className="col-span-2 space-y-1">
              <Label>{t("requestPart.notesLabel")}</Label>
              <Textarea value={notes} onChange={(e) => setNotes(e.target.value)} rows={2} />
            </div>

            <div className="col-span-2 flex justify-end">
              <Button type="button" size="sm" variant="outline" onClick={addToCart} disabled={quantity < 1}>
                <PlusIcon className="mr-1 size-4" />
                {t("requestPart.addToCart")}
              </Button>
            </div>
          </div>

          {/* ── Cart ──────────────────────────────────────── */}
          <div className="space-y-2">
            <div className="flex items-center gap-2">
              <ShoppingCartIcon className="size-4 text-muted-foreground" />
              <span className="font-medium text-sm">{t("requestPart.cart")}</span>
              <Badge variant="secondary" className="ml-auto">
                {t("requestPart.cartCount", { count: cart.length })}
              </Badge>
              {/* ponytail: keep badge single-line; length never needs truncation */}
            </div>

            {cart.length === 0 ? (
              <p className="text-muted-foreground text-xs">{t("requestPart.cartEmpty")}</p>
            ) : (
              <ScrollArea className="max-h-48">
                <div className="space-y-2">
                  {cart.map((item) => (
                    <div key={item.key} className="flex items-start justify-between rounded-md border p-2 text-xs">
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-2">
                          <span className="font-medium">{item.materialCode ?? t("requestPart.newPart")}</span>
                          <Badge className={`text-xs ${requestTypeClass(item.requestType)}`}>
                            {t.has(`types.${item.requestType}`) ? t(`types.${item.requestType}`) : item.requestType}
                          </Badge>
                        </div>
                        {item.sparepartLabel && <p className="text-muted-foreground">{item.sparepartLabel}</p>}
                        <p className="text-muted-foreground">
                          {t("requestPart.qtyLine", { qty: item.quantity })}
                          {item.estUnitPrice
                            ? ` · ${format.number(Number(item.estUnitPrice), {
                                style: "currency",
                                currency: "IDR",
                                maximumFractionDigits: 0,
                              })}`
                            : ""}
                        </p>
                        {item.notes && <p className="italic">{item.notes}</p>}
                      </div>
                      <Button
                        type="button"
                        variant="ghost"
                        size="icon"
                        className="size-6 shrink-0"
                        onClick={() => removeFromCart(item.key)}
                      >
                        <Trash2Icon className="size-3" />
                      </Button>
                    </div>
                  ))}
                </div>
              </ScrollArea>
            )}
          </div>
        </div>

        <DialogFooter className="flex-row justify-between gap-2">
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={() => {
              setOpen(false);
              resetAll();
            }}
          >
            {tc("cancel")}
          </Button>
          <Button size="sm" onClick={handleSubmitAll} disabled={cart.length === 0 || submitting}>
            {submitting ? t("requestPart.submitting") : t("requestPart.sendToWarehouse", { count: cart.length })}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Creatable taxonomy picker (mirrors the create-sparepart dialog pattern). */
function TaxonomySelect({
  label,
  word,
  value,
  items,
  creatable,
  onCreate,
  onChange,
}: {
  label: string;
  word: string;
  value: string;
  items: SparepartTaxonomyView[];
  creatable: boolean;
  onCreate?: (name: string) => Promise<void>;
  onChange: (value: string) => void;
}) {
  const t = useTranslations("sparepartRequests");
  const [search, setSearch] = useState("");
  const visibleItems = items.filter((item) => {
    const q = search.trim().toLowerCase();
    if (!q) return true;
    return [item.code, item.name].some((v) => v?.toLowerCase().includes(q));
  });
  const canCreate = Boolean(creatable && onCreate && search.trim());

  return (
    <div className="space-y-1">
      <Label>{label}</Label>
      <Select value={value || undefined} onValueChange={onChange} disabled={!creatable && items.length === 0}>
        <SelectTrigger className="w-full">
          <SelectValue placeholder={t("taxonomy.select", { word })} />
        </SelectTrigger>
        <SelectContent position="popper" side="top" align="start" className="max-h-72">
          <div className="p-2">
            <Input
              value={search}
              placeholder={t("taxonomy.search", { word })}
              onChange={(e) => setSearch(e.target.value)}
              onKeyDown={(e) => e.stopPropagation()}
            />
          </div>
          {visibleItems.length === 0 ? (
            <div className="px-2 py-1.5 text-muted-foreground text-sm">{t("taxonomy.none", { word })}</div>
          ) : null}
          {visibleItems.map((item) => (
            <SelectItem key={item.id} value={item.id ?? ""}>
              {item.name} ({item.code})
            </SelectItem>
          ))}
          {canCreate ? (
            <div className="border-t p-2">
              <Button
                type="button"
                variant="outline"
                className="w-full"
                size="sm"
                onClick={() => {
                  void onCreate?.(search);
                  setSearch("");
                }}
              >
                {t("taxonomy.create", { word, name: search.trim() })}
              </Button>
            </div>
          ) : null}
        </SelectContent>
      </Select>
    </div>
  );
}

/** Request-type badge tint: sparepart = info, consumable = neutral, external service = warning. */
function requestTypeClass(type: SparepartRequestType): string {
  switch (type) {
    case "SPAREPART":
      return "status-badge-info";
    case "CONSUMABLE":
      return "status-badge-neutral";
    case "SERVICE_EXTERNAL":
      return "status-badge-warning";
    default:
      return "status-badge-neutral";
  }
}
