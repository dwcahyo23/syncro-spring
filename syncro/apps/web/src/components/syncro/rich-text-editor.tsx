"use client";

import { type ReactNode, useEffect, useState } from "react";

import FindAndReplace from "@tiptap/extension-find-and-replace";
import FontFamily from "@tiptap/extension-font-family";
import Highlight from "@tiptap/extension-highlight";
import Image from "@tiptap/extension-image";
import { TaskItem, TaskList } from "@tiptap/extension-list";
import { Table, TableCell, TableHeader, TableRow } from "@tiptap/extension-table";
import TextAlign from "@tiptap/extension-text-align";
import { Color, TextStyle } from "@tiptap/extension-text-style";
import Typography from "@tiptap/extension-typography";
import { CharacterCount, Placeholder } from "@tiptap/extensions";
import { type Editor, EditorContent, useEditor } from "@tiptap/react";
import StarterKit from "@tiptap/starter-kit";
import {
  AlignCenter,
  AlignJustify,
  AlignLeft,
  AlignRight,
  Bold,
  Braces,
  Check,
  ChevronDown,
  Columns3,
  Eraser,
  Grid3x3,
  Highlighter,
  Image as ImageIcon,
  Italic,
  Link2,
  Link2Off,
  List,
  ListChecks,
  ListOrdered,
  Minus,
  Palette,
  Redo2,
  Rows3,
  Scissors,
  Search,
  SplitSquareVertical,
  Strikethrough,
  Trash2,
  Type,
  Underline as UnderlineIcon,
  Undo2,
} from "lucide-react";
import { useTranslations } from "next-intl";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { REPORT_TEMPLATE_PLACEHOLDERS, type ReportPrintTemplateType } from "@/lib/print-template";

/**
 * TipTap WYSIWYG editor for the global print templates (spec "Template Laporan Cetak").
 * Three toolbar rows: (1) history, inline marks, block/format/list + text-align, color,
 * highlight, font family; (2) insert (table/image/link/hr/placeholder) and utilities
 * (clear formatting, find & replace); (3) the full table toolkit (row/column ops in
 * every direction, merge/split, header row/column/cell, colspan/rowspan, repair).
 *
 * Formatting renders as CLASSES (cell-align-*, text-align-*, tx-color-*, hl-*, ff-*,
 * task-list/task-item/task-checked), never inline `style`/`data-*`: the sanitizer
 * (src/lib/html.ts) strips both, so the saved template must round-trip through the
 * class palette below. styles/print.css maps every class for screen AND paper.
 *
 * Controlled from the outside: the parent seeds `initialContent` and remounts this
 * component (React `key`) when the edited report type changes, so a half-edited tab
 * can never be submitted under the wrong type. `onChange` reports `editor.getHTML()`.
 * TipTap v3 defers editor creation to the client (immediatelyRender: false), keeping
 * this hydration-safe in App Router.
 */
interface RichTextEditorProps {
  initialContent: string;
  onChange: (html: string) => void;
  reportType: ReportPrintTemplateType;
  readOnly?: boolean;
  isSaving?: boolean;
  canSave?: boolean;
  onSave?: () => void;
}

type CellAlign = "left" | "center" | "right";
type Align = "left" | "center" | "right" | "justify";

/** Render align as a stable class instead of an inline style (style is sanitized away). */
const alignedCell = <T extends typeof TableCell | typeof TableHeader>(base: T) =>
  base.extend({
    addAttributes() {
      return {
        ...this.parent?.(),
        align: {
          default: null,
          parseHTML: (element) => {
            const cls = Array.from(element.classList).find((c) => c.startsWith("cell-align-"));
            if (cls) return cls.replace("cell-align-", "");
            const style = element.getAttribute("style") || "";
            const m = style.match(/text-align:\s*(left|center|right)/);
            return m ? m[1] : null;
          },
          renderHTML: (attributes) => (attributes.align ? { class: `cell-align-${attributes.align}` } : {}),
        },
      };
    },
  });

const Cell = alignedCell(TableCell);
const HeaderCell = alignedCell(TableHeader);

/** Curated palettes: values are hex colors / family slugs that map to classes in print.css. */
const TEXT_COLORS = ["#dc2626", "#d97706", "#16a34a", "#2563eb", "#7c3aed", "#6b7280"];
const HIGHLIGHT_COLORS = ["#fde047", "#86efac", "#bfdbfe", "#f9a8d4", "#fed7aa", "#e5e7eb"];
const FONT_FAMILIES = [
  { slug: "arial", value: "Arial, Helvetica, sans-serif" },
  { slug: "georgia", value: 'Georgia, "Times New Roman", serif' },
  { slug: "courier", value: '"Courier New", monospace' },
  { slug: "tahoma", value: "Tahoma, Verdana, sans-serif" },
];

// The `style`-rendering text-format extensions are re-served as classes; parent() is
// untyped for override reads, so the attribute bags are cast once per extension.
type AttrBag = Record<
  string,
  { parseHTML?: (el: HTMLElement) => unknown; renderHTML?: (a: Record<string, unknown>) => Record<string, string> }
>;
type GlobalAttr = { types: string[]; attributes: AttrBag };
const parentAttrs = (fn: (() => unknown) | undefined): GlobalAttr[] => (fn?.() ?? []) as GlobalAttr[];

const ClassTextAlign = TextAlign.extend({
  addGlobalAttributes() {
    return parentAttrs(this.parent).map(({ types, attributes }) => ({
      types,
      attributes: {
        ...attributes,
        textAlign: {
          parseHTML: (el: HTMLElement) => {
            const m = /\btext-align-(left|center|right|justify)\b/.exec(el.getAttribute("class") ?? "");
            if (m) return m[1];
            const style = (el.getAttribute("style") ?? "").match(/text-align:\s*(left|center|right|justify)/);
            return style ? style[1] : null;
          },
          renderHTML: (a: Record<string, unknown>) =>
            a.textAlign ? { class: `text-align-${a.textAlign as string}` } : {},
        },
      },
    }));
  },
});

const ClassColor = Color.extend({
  addGlobalAttributes() {
    return parentAttrs(this.parent).map(({ types, attributes }) => ({
      types,
      attributes: {
        ...attributes,
        color: {
          default: null,
          parseHTML: (el: HTMLElement) => {
            const m = /\btx-color-([0-9a-f]{6})\b/.exec(el.getAttribute("class") ?? "");
            return m ? `#${m[1]}` : null;
          },
          renderHTML: (a: Record<string, unknown>) =>
            a.color ? { class: `tx-color-${String(a.color).replace("#", "").toLowerCase()}` } : {},
        },
      },
    }));
  },
});

/**
 * The base TextStyle mark parses `span[style]` only; class-based rendering would
 * silently lose color/font on reopen, so parse any styled-class span instead.
 */
const ClassTextStyle = TextStyle.extend({
  parseHTML() {
    return [
      {
        tag: "span",
        consuming: false,
        // Match ({}), not null (null = "rule did not apply").
        getAttrs: (el) => ((el as HTMLElement).classList.length > 0 ? {} : false),
      },
    ];
  },
});

const ClassFontFamily = FontFamily.extend({
  addGlobalAttributes() {
    return parentAttrs(this.parent).map(({ types, attributes }) => ({
      types,
      attributes: {
        ...attributes,
        fontFamily: {
          default: null,
          parseHTML: (el: HTMLElement) => {
            const m = /\bff-([a-z0-9-]+)\b/.exec(el.getAttribute("class") ?? "");
            return FONT_FAMILIES.find((f) => f.slug === m?.[1])?.value ?? null;
          },
          renderHTML: (a: Record<string, unknown>) => {
            const fam = FONT_FAMILIES.find((f) => f.value === a.fontFamily);
            return fam ? { class: `ff-${fam.slug}` } : {};
          },
        },
      },
    }));
  },
});

const ClassHighlight = Highlight.configure({ multicolor: true }).extend({
  addAttributes() {
    const attrs = this.parent?.() ?? {};
    return {
      ...attrs,
      color: {
        default: null,
        parseHTML: (el: HTMLElement) => {
          const m = /\bhl-([0-9a-f]{6})\b/.exec(el.getAttribute("class") ?? "");
          return m ? `#${m[1]}` : null;
        },
        renderHTML: (a: Record<string, unknown>) =>
          a.color ? { class: `hl-${String(a.color).replace("#", "").toLowerCase()}` } : {},
      },
    };
  },
});

/**
 * Task lists round-trip as classes (`data-type`/`data-checked` are stripped by the
 * sanitizer); the checkbox renders as a plain `input type=checkbox` so it prints
 * non-interactively — print.css disables pointer events on the rendered copy.
 */
const ClassTaskList = TaskList.extend({
  parseHTML() {
    // Priority above BulletList's bare `ul` rule (50), else saved task lists
    // re-parse as bullet lists.
    return [{ tag: "ul.task-list", priority: 60 }];
  },
  renderHTML() {
    return ["ul", { class: "task-list" }, 0];
  },
});

const ClassTaskItem = TaskItem.extend({
  addAttributes() {
    return {
      checked: {
        default: false,
        keepOnSplit: false,
        parseHTML: (el: HTMLElement) =>
          el.classList.contains("task-checked") ||
          !!el.querySelector('input[type="checkbox"]')?.hasAttribute("checked"),
        renderHTML: (a: Record<string, unknown>) => (a.checked ? { class: "task-checked" } : {}),
      },
    };
  },
  parseHTML() {
    return [
      {
        tag: "li.task-item",
        priority: 60,
        // node text lives in the content div; without this the checkbox's hidden
        // label span bleeds into the item content on load.
        contentElement: (el) => (el as HTMLElement).querySelector("div") ?? el,
      },
    ];
  },
  renderHTML({ node }) {
    // Mirrors upstream (label + span, content div as sibling) so the node view
    // and print rendering agree; classes instead of data-*.
    return [
      "li",
      { class: `task-item${node.attrs.checked ? " task-checked" : ""}` },
      ["label", ["input", { type: "checkbox", checked: node.attrs.checked ? "" : null }], ["span"]],
      ["div", 0],
    ];
  },
});

function ToolbarSeparator() {
  return <span className="mx-1 h-5 w-px bg-border" aria-hidden="true" />;
}

function ToolbarButton({
  onClick,
  active,
  disabled,
  label,
  children,
}: {
  onClick: () => void;
  active?: boolean;
  disabled?: boolean;
  label: string;
  children: ReactNode;
}) {
  return (
    <Button
      type="button"
      variant={active ? "secondary" : "ghost"}
      size="icon-sm"
      onClick={onClick}
      disabled={disabled}
      aria-label={label}
      title={label}
    >
      {children}
    </Button>
  );
}

const ToolbarMenuButton = ({
  label,
  disabled,
  children,
}: {
  label: string;
  disabled: boolean;
  children: ReactNode;
}) => (
  <Button
    type="button"
    variant="ghost"
    size="sm"
    disabled={disabled}
    className="h-7 gap-1 px-2 text-xs"
    aria-label={label}
    title={label}
  >
    {children}
    <ChevronDown className="size-3" />
  </Button>
);

/** Small color-swatch grid popover (text color / highlight). `active` shows the current value. */
function SwatchPopover({
  label,
  icon,
  colors,
  activeColor,
  disabled,
  onPick,
  onClear,
}: {
  label: string;
  icon: ReactNode;
  colors: string[];
  activeColor: string | null;
  disabled: boolean;
  onPick: (color: string) => void;
  onClear: () => void;
}) {
  const tt = useTranslations("reportTemplates.editor.toolbar");
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          disabled={disabled}
          className="h-7 gap-1 px-2 text-xs"
          aria-label={label}
          title={label}
        >
          {icon}
          <span className="block size-3 rounded-sm border" style={{ background: activeColor ?? "transparent" }} />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-auto p-2">
        <div className="grid grid-cols-6 gap-1">
          {colors.map((c) => (
            <button
              key={c}
              type="button"
              title={c}
              onClick={() => onPick(c)}
              className="size-6 rounded-sm border"
              style={{ background: c }}
            />
          ))}
        </div>
        <Button type="button" variant="ghost" size="sm" className="mt-1 h-6 w-full text-xs" onClick={onClear}>
          {tt("clearColor")}
        </Button>
      </PopoverContent>
    </Popover>
  );
}

/** URL input popover for link + image insert; `onSubmit` runs only for http(s) URLs. */
function UrlPopover({
  open,
  onOpenChange,
  trigger,
  initialUrl,
  label,
  onSubmit,
  onDelete,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  trigger: ReactNode;
  initialUrl: string;
  label: string;
  disabled: boolean;
  onSubmit: (url: string) => void;
  onDelete?: () => void;
}) {
  const tt = useTranslations("reportTemplates.editor.toolbar");
  const [url, setUrl] = useState(initialUrl);
  useEffect(() => {
    if (open) setUrl(initialUrl);
  }, [open, initialUrl]);
  const valid = /^https?:\/\/.+/i.test(url.trim());
  return (
    <Popover open={open} onOpenChange={onOpenChange}>
      <PopoverTrigger asChild>{trigger}</PopoverTrigger>
      <PopoverContent align="start" className="w-72 p-3">
        <p className="text-xs font-medium">{label}</p>
        <Input
          autoFocus
          value={url}
          onChange={(e) => setUrl(e.target.value)}
          placeholder={tt("urlPlaceholder")}
          className="mt-1 h-7 text-xs"
          onKeyDown={(e) => {
            if (e.key === "Enter" && valid) {
              e.preventDefault();
              onSubmit(url.trim());
              onOpenChange(false);
            }
          }}
        />
        <div className="mt-2 flex justify-end gap-1">
          {onDelete && (
            <Button
              type="button"
              variant="ghost"
              size="sm"
              className="h-6 text-xs"
              onClick={() => {
                onDelete();
                onOpenChange(false);
              }}
            >
              {tt("removeLink")}
            </Button>
          )}
          <Button
            type="button"
            size="sm"
            variant="secondary"
            className="h-6 text-xs"
            disabled={!valid}
            onClick={() => {
              onSubmit(url.trim());
              onOpenChange(false);
            }}
          >
            {tt("apply")}
          </Button>
        </div>
      </PopoverContent>
    </Popover>
  );
}

/** +/− stepper for colspan/rowspan; clamped to >=1. `cur` may be undefined pre-attr. */
function StepButton({
  editor,
  disabled,
  label,
  attr,
  dir,
}: {
  editor: Editor | null;
  disabled: boolean;
  label: string;
  attr: "colspan" | "rowspan";
  dir: 1 | -1;
}) {
  const current = (cellAttr(editor, attr) as number | null) ?? 1;
  const canApply = !disabled && dir === 1 ? Number(current) < 12 : Number(current) > 1;
  return (
    <ToolbarButton
      label={label}
      disabled={!canApply}
      onClick={() =>
        editor
          ?.chain()
          .focus()
          .setCellAttribute(attr, Math.max(1, Number(current) + dir))
          .run()
      }
    >
      <span className="text-[10px] font-semibold">
        {attr === "colspan" ? "↔" : "↕"}
        {dir === 1 ? "+" : "−"}
      </span>
    </ToolbarButton>
  );
}

function cellNodeName(editor: Editor | null): "tableCell" | "tableHeader" {
  return editor?.isActive("tableHeader") ? "tableHeader" : "tableCell";
}

function cellAttr(editor: Editor | null, name: "colspan" | "rowspan" | "align"): unknown {
  return editor?.getAttributes(cellNodeName(editor))[name];
}

function EditorToolbar({
  editor,
  reportType,
  disabled,
}: {
  editor: Editor | null;
  reportType: ReportPrintTemplateType;
  disabled: boolean;
}) {
  const tt = useTranslations("reportTemplates.editor.toolbar");
  const inTable = editor?.isActive("table") ?? false;
  const can = (fn: (e: Editor) => boolean) => (editor ? fn(editor) : false);
  const cellAlign = (cellAttr(editor, "align") as CellAlign | null) ?? null;
  // Only enable merge/split when the command actually applies to the current selection.
  const canMerge = can((e) => e.can().mergeCells());
  const canSplit = can((e) => e.can().splitCell());
  const align = (editor?.getAttributes("paragraph").textAlign ?? editor?.getAttributes("heading").textAlign) as
    | Align
    | null
    | undefined;
  const textColor = (editor?.getAttributes("textStyle").color as string | undefined) ?? null;
  const hlColor = (editor?.getAttributes("highlight").color as string | undefined) ?? null;
  const fontFamily = (editor?.getAttributes("textStyle").fontFamily as string | undefined) ?? null;

  const [linkOpen, setLinkOpen] = useState(false);
  const [imageOpen, setImageOpen] = useState(false);
  const [findOpen, setFindOpen] = useState(false);
  const linkHref = (editor?.getAttributes("link").href as string | undefined) ?? "";
  const hasLink = editor?.isActive("link") ?? false;

  const findStorage = editor?.storage?.findAndReplace as
    | {
        searchTerm: string;
        replaceTerm: string;
        results: unknown[];
        currentIndex: number | null;
      }
    | undefined;

  const setAlign = (a: CellAlign) =>
    editor
      ?.chain()
      .focus()
      .setCellAttribute("align", cellAlign === a ? null : a)
      .run();

  const getBlockLabel = (): string => {
    const level = ([1, 2, 3] as const).find((l) => editor?.isActive("heading", { level: l }));
    if (level != null) return tt(`heading${level}`);
    if (editor?.isActive("blockquote")) return tt("blockquote");
    if (editor?.isActive("codeBlock")) return tt("codeBlock");
    return tt("paragraph");
  };

  return (
    <div className="flex flex-col rounded-t-lg border border-b-0 bg-muted/40">
      {/* Row 1 — history, inline marks, block, list, align, color, highlight, font */}
      <div className="flex flex-wrap items-center gap-0.5 p-1">
        <ToolbarButton
          label={tt("undo")}
          onClick={() => editor?.chain().focus().undo().run()}
          disabled={disabled || !can((e) => e.can().undo())}
        >
          <Undo2 className="size-4" />
        </ToolbarButton>
        <ToolbarButton
          label={tt("redo")}
          onClick={() => editor?.chain().focus().redo().run()}
          disabled={disabled || !can((e) => e.can().redo())}
        >
          <Redo2 className="size-4" />
        </ToolbarButton>
        <ToolbarSeparator />
        <ToolbarButton
          label={tt("bold")}
          onClick={() => editor?.chain().focus().toggleBold().run()}
          active={editor?.isActive("bold")}
          disabled={disabled}
        >
          <Bold className="size-4" />
        </ToolbarButton>
        <ToolbarButton
          label={tt("italic")}
          onClick={() => editor?.chain().focus().toggleItalic().run()}
          active={editor?.isActive("italic")}
          disabled={disabled}
        >
          <Italic className="size-4" />
        </ToolbarButton>
        <ToolbarButton
          label={tt("underline")}
          onClick={() => editor?.chain().focus().toggleUnderline().run()}
          active={editor?.isActive("underline")}
          disabled={disabled}
        >
          <UnderlineIcon className="size-4" />
        </ToolbarButton>
        <ToolbarButton
          label={tt("strike")}
          onClick={() => editor?.chain().focus().toggleStrike().run()}
          active={editor?.isActive("strike")}
          disabled={disabled}
        >
          <Strikethrough className="size-4" />
        </ToolbarButton>
        <ToolbarButton
          label={tt("code")}
          onClick={() => editor?.chain().focus().toggleCode().run()}
          active={editor?.isActive("code")}
          disabled={disabled}
        >
          <span className="text-[10px] font-mono">{"</>"}</span>
        </ToolbarButton>
        <ToolbarSeparator />
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <ToolbarMenuButton label={tt("block")} disabled={disabled}>
              {getBlockLabel()}
            </ToolbarMenuButton>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start">
            <DropdownMenuItem
              onSelect={() => editor?.chain().focus().setParagraph().run()}
              className={editor?.isActive("paragraph") ? "font-semibold" : undefined}
            >
              {tt("paragraph")}
              {editor?.isActive("paragraph") && <Check className="ml-auto size-3.5" />}
            </DropdownMenuItem>
            {([1, 2, 3] as const).map((level) => (
              <DropdownMenuItem key={level} onSelect={() => editor?.chain().focus().toggleHeading({ level }).run()}>
                {tt(`heading${level}`)}
                {editor?.isActive("heading", { level }) && <Check className="ml-auto size-3.5" />}
              </DropdownMenuItem>
            ))}
            <DropdownMenuItem onSelect={() => editor?.chain().focus().toggleBlockquote().run()}>
              {tt("blockquote")}
              {editor?.isActive("blockquote") && <Check className="ml-auto size-3.5" />}
            </DropdownMenuItem>
            <DropdownMenuItem onSelect={() => editor?.chain().focus().toggleCodeBlock().run()}>
              {tt("codeBlock")}
              {editor?.isActive("codeBlock") && <Check className="ml-auto size-3.5" />}
            </DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem onSelect={() => editor?.chain().focus().setHorizontalRule().run()}>
              {tt("horizontalRule")}
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
        <ToolbarButton
          label={tt("bulletList")}
          onClick={() => editor?.chain().focus().toggleBulletList().run()}
          active={editor?.isActive("bulletList")}
          disabled={disabled}
        >
          <List className="size-4" />
        </ToolbarButton>
        <ToolbarButton
          label={tt("orderedList")}
          onClick={() => editor?.chain().focus().toggleOrderedList().run()}
          active={editor?.isActive("orderedList")}
          disabled={disabled}
        >
          <ListOrdered className="size-4" />
        </ToolbarButton>
        <ToolbarButton
          label={tt("taskList")}
          onClick={() => editor?.chain().focus().toggleTaskList().run()}
          active={editor?.isActive("taskList")}
          disabled={disabled}
        >
          <ListChecks className="size-4" />
        </ToolbarButton>
        <ToolbarSeparator />
        <ToolbarButton
          label={tt("alignLeft")}
          onClick={() => editor?.chain().focus().toggleTextAlign("left").run()}
          active={align === "left"}
          disabled={disabled}
        >
          <AlignLeft className="size-4" />
        </ToolbarButton>
        <ToolbarButton
          label={tt("alignCenter")}
          onClick={() => editor?.chain().focus().toggleTextAlign("center").run()}
          active={align === "center"}
          disabled={disabled}
        >
          <AlignCenter className="size-4" />
        </ToolbarButton>
        <ToolbarButton
          label={tt("alignRight")}
          onClick={() => editor?.chain().focus().toggleTextAlign("right").run()}
          active={align === "right"}
          disabled={disabled}
        >
          <AlignRight className="size-4" />
        </ToolbarButton>
        <ToolbarButton
          label={tt("alignJustify")}
          onClick={() => editor?.chain().focus().toggleTextAlign("justify").run()}
          active={align === "justify"}
          disabled={disabled}
        >
          <AlignJustify className="size-4" />
        </ToolbarButton>
        <ToolbarSeparator />
        <SwatchPopover
          label={tt("textColor")}
          icon={<Palette className="size-3.5" />}
          colors={TEXT_COLORS}
          activeColor={textColor}
          disabled={disabled}
          onPick={(c) => editor?.chain().focus().setColor(c).run()}
          onClear={() => editor?.chain().focus().unsetColor().run()}
        />
        <SwatchPopover
          label={tt("highlightColor")}
          icon={<Highlighter className="size-3.5" />}
          colors={HIGHLIGHT_COLORS}
          activeColor={hlColor}
          disabled={disabled}
          onPick={(c) => editor?.chain().focus().toggleHighlight({ color: c }).run()}
          onClear={() => editor?.chain().focus().unsetHighlight().run()}
        />
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <ToolbarMenuButton label={tt("fontFamily")} disabled={disabled}>
              <Type className="size-3.5" />
            </ToolbarMenuButton>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start">
            {FONT_FAMILIES.map((f) => (
              <DropdownMenuItem key={f.slug} onSelect={() => editor?.chain().focus().setFontFamily(f.value).run()}>
                <span style={{ fontFamily: f.value }} className="text-xs">
                  {f.slug}
                </span>
                {fontFamily === f.value && <Check className="ml-auto size-3.5" />}
              </DropdownMenuItem>
            ))}
            <DropdownMenuSeparator />
            <DropdownMenuItem onSelect={() => editor?.chain().focus().unsetFontFamily().run()}>
              {tt("clearColor")}
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>

      {/* Row 2 — insert + utility */}
      <div className="flex flex-wrap items-center gap-0.5 border-t p-1">
        <ToolbarButton
          label={tt("insertTable")}
          onClick={() => editor?.chain().focus().insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run()}
          disabled={disabled || inTable}
        >
          <Grid3x3 className="size-4" />
        </ToolbarButton>
        <UrlPopover
          open={imageOpen}
          onOpenChange={setImageOpen}
          trigger={
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              disabled={disabled}
              aria-label={tt("insertImage")}
              title={tt("insertImage")}
            >
              <ImageIcon className="size-4" />
            </Button>
          }
          initialUrl=""
          label={tt("imageSrcLabel")}
          disabled={disabled}
          onSubmit={(src) => editor?.chain().focus().setImage({ src, alt: "" }).run()}
        />
        <UrlPopover
          open={linkOpen}
          onOpenChange={setLinkOpen}
          trigger={
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              disabled={disabled}
              aria-label={hasLink ? tt("editLink") : tt("insertLink")}
              title={hasLink ? tt("editLink") : tt("insertLink")}
            >
              {hasLink ? <Link2Off className="size-4" /> : <Link2 className="size-4" />}
            </Button>
          }
          initialUrl={linkHref}
          label={tt("linkLabel")}
          disabled={disabled}
          onSubmit={(href) => editor?.chain().focus().extendMarkRange("link").setLink({ href }).run()}
          onDelete={() => editor?.chain().focus().extendMarkRange("link").unsetLink().run()}
        />
        <ToolbarButton
          label={tt("horizontalRule")}
          onClick={() => editor?.chain().focus().setHorizontalRule().run()}
          disabled={disabled}
        >
          <Minus className="size-4" />
        </ToolbarButton>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button type="button" variant="ghost" size="sm" disabled={disabled} className="h-7 gap-1 px-2 text-xs">
              <Braces className="size-3.5" />
              {tt("insertPlaceholder")}
              <ChevronDown className="size-3" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" className="max-h-72 overflow-y-auto">
            <DropdownMenuLabel>{tt("placeholdersLabel")}</DropdownMenuLabel>
            <DropdownMenuSeparator />
            {REPORT_TEMPLATE_PLACEHOLDERS[reportType].map((key) => (
              <DropdownMenuItem key={key} onSelect={() => editor?.chain().focus().insertContent(`{{${key}}}`).run()}>
                <code className="text-xs">{`{{${key}}}`}</code>
              </DropdownMenuItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>
        <ToolbarSeparator />
        <ToolbarButton
          label={tt("clearFormatting")}
          onClick={() => editor?.chain().focus().clearNodes().unsetAllMarks().run()}
          disabled={disabled}
        >
          <Eraser className="size-4" />
        </ToolbarButton>
        <Popover open={findOpen} onOpenChange={setFindOpen}>
          <PopoverTrigger asChild>
            <Button type="button" variant="ghost" size="sm" disabled={disabled} className="h-7 gap-1 px-2 text-xs">
              <Search className="size-3.5" />
              {tt("findReplace")}
            </Button>
          </PopoverTrigger>
          <PopoverContent align="start" className="w-80 p-3">
            <div className="flex gap-1">
              <Input
                autoFocus
                value={findStorage?.searchTerm ?? ""}
                onChange={(e) => editor?.commands.setSearchTerm(e.target.value)}
                placeholder={tt("findPlaceholder")}
                className="h-7 text-xs"
              />
              <span className="text-muted-foreground flex h-7 items-center px-1 text-[11px] whitespace-nowrap">
                {findStorage && findStorage.results.length > 0
                  ? tt("matchCount", { count: findStorage.results.length })
                  : tt("noMatches")}
              </span>
            </div>
            <div className="mt-1 flex items-center gap-1">
              <Input
                value={findStorage?.replaceTerm ?? ""}
                onChange={(e) => editor?.commands.setReplaceTerm(e.target.value)}
                placeholder={tt("replacePlaceholder")}
                className="h-7 text-xs"
              />
            </div>
            <div className="mt-2 flex justify-end gap-1">
              <ToolbarButton
                label={tt("findPrev")}
                onClick={() => editor?.commands.goToPreviousResult()}
                disabled={disabled || !findStorage || findStorage.results.length === 0}
              >
                <span className="text-[10px] font-semibold">↑</span>
              </ToolbarButton>
              <ToolbarButton
                label={tt("findNext")}
                onClick={() => editor?.commands.goToNextResult()}
                disabled={disabled || !findStorage || findStorage.results.length === 0}
              >
                <span className="text-[10px] font-semibold">↓</span>
              </ToolbarButton>
              <Button
                type="button"
                size="sm"
                variant="secondary"
                className="h-6 text-xs"
                onClick={() => editor?.commands.replace()}
              >
                {tt("replace")}
              </Button>
              <Button
                type="button"
                size="sm"
                variant="secondary"
                className="h-6 text-xs"
                onClick={() => editor?.commands.replaceAll()}
              >
                {tt("replaceAll")}
              </Button>
              <Button
                type="button"
                size="sm"
                variant="ghost"
                className="h-6 text-xs"
                onClick={() => {
                  editor?.commands.clearSearch();
                  setFindOpen(false);
                }}
              >
                {tt("closeSearch")}
              </Button>
            </div>
          </PopoverContent>
        </Popover>
      </div>

      {/* Row 3 — table toolkit; disabled entirely outside a table */}
      <div className="flex flex-wrap items-center gap-0.5 border-t p-1">
        <ToolbarButton
          label={tt("addRowAbove")}
          onClick={() => editor?.chain().focus().addRowBefore().run()}
          disabled={disabled || !inTable}
        >
          <span className="text-[10px] font-semibold">↑+</span>
        </ToolbarButton>
        <ToolbarButton
          label={tt("addRow")}
          onClick={() => editor?.chain().focus().addRowAfter().run()}
          disabled={disabled || !inTable}
        >
          <span className="text-[10px] font-semibold">↓+</span>
        </ToolbarButton>
        <ToolbarButton
          label={tt("addColumn")}
          onClick={() => editor?.chain().focus().addColumnAfter().run()}
          disabled={disabled || !inTable}
        >
          <span className="text-[10px] font-semibold">→+</span>
        </ToolbarButton>
        <ToolbarButton
          label={tt("addColumnLeft")}
          onClick={() => editor?.chain().focus().addColumnBefore().run()}
          disabled={disabled || !inTable}
        >
          <span className="text-[10px] font-semibold">←+</span>
        </ToolbarButton>
        <ToolbarSeparator />
        <ToolbarButton
          label={tt("deleteRow")}
          onClick={() => editor?.chain().focus().deleteRow().run()}
          disabled={disabled || !inTable}
        >
          <Rows3 className="size-4" />
        </ToolbarButton>
        <ToolbarButton
          label={tt("deleteColumn")}
          onClick={() => editor?.chain().focus().deleteColumn().run()}
          disabled={disabled || !inTable}
        >
          <Columns3 className="size-4" />
        </ToolbarButton>
        <ToolbarSeparator />
        <ToolbarButton
          label={tt("mergeCells")}
          onClick={() => editor?.chain().focus().mergeCells().run()}
          disabled={disabled || !canMerge}
        >
          <SplitSquareVertical className="size-4" />
        </ToolbarButton>
        <ToolbarButton
          label={tt("splitCell")}
          onClick={() => editor?.chain().focus().splitCell().run()}
          disabled={disabled || !canSplit}
        >
          <Scissors className="size-4" />
        </ToolbarButton>
        <StepButton editor={editor} disabled={disabled || !inTable} label={tt("colspan")} attr="colspan" dir={1} />
        <StepButton
          editor={editor}
          disabled={disabled || !inTable}
          label={tt("colspanShrink")}
          attr="colspan"
          dir={-1}
        />
        <StepButton editor={editor} disabled={disabled || !inTable} label={tt("rowspan")} attr="rowspan" dir={1} />
        <StepButton
          editor={editor}
          disabled={disabled || !inTable}
          label={tt("rowspanShrink")}
          attr="rowspan"
          dir={-1}
        />
        <ToolbarSeparator />
        <ToolbarButton
          label={tt("toggleHeaderRow")}
          onClick={() => editor?.chain().focus().toggleHeaderRow().run()}
          active={editor?.isActive("tableHeader")}
          disabled={disabled || !inTable}
        >
          <span className="text-[10px] font-semibold">H↕</span>
        </ToolbarButton>
        <ToolbarButton
          label={tt("toggleHeaderColumn")}
          onClick={() => editor?.chain().focus().toggleHeaderColumn().run()}
          disabled={disabled || !inTable}
        >
          <span className="text-[10px] font-semibold">H↔</span>
        </ToolbarButton>
        <ToolbarButton
          label={tt("toggleHeaderCell")}
          onClick={() => editor?.chain().focus().toggleHeaderCell().run()}
          disabled={disabled || !inTable}
        >
          <span className="text-[10px] font-semibold">H·</span>
        </ToolbarButton>
        <ToolbarSeparator />
        <ToolbarButton
          label={tt("alignCellLeft")}
          onClick={() => setAlign("left")}
          active={cellAlign === "left"}
          disabled={disabled || !inTable}
        >
          <AlignLeft className="size-4" />
        </ToolbarButton>
        <ToolbarButton
          label={tt("alignCellCenter")}
          onClick={() => setAlign("center")}
          active={cellAlign === "center"}
          disabled={disabled || !inTable}
        >
          <AlignCenter className="size-4" />
        </ToolbarButton>
        <ToolbarButton
          label={tt("alignCellRight")}
          onClick={() => setAlign("right")}
          active={cellAlign === "right"}
          disabled={disabled || !inTable}
        >
          <AlignRight className="size-4" />
        </ToolbarButton>
        <ToolbarSeparator />
        <ToolbarButton
          label={tt("fixTables")}
          onClick={() => editor?.chain().focus().fixTables().run()}
          disabled={disabled || !inTable}
        >
          <span className="text-[10px] font-semibold">⚙</span>
        </ToolbarButton>
        <ToolbarButton
          label={tt("deleteTable")}
          onClick={() => editor?.chain().focus().deleteTable().run()}
          disabled={disabled || !inTable}
        >
          <Trash2 className="size-4" />
        </ToolbarButton>
      </div>
    </div>
  );
}

export function RichTextEditor({
  initialContent,
  onChange,
  reportType,
  readOnly = false,
  isSaving = false,
  canSave = true,
  onSave,
}: RichTextEditorProps) {
  const t = useTranslations("reportTemplates.editor");
  const tc = useTranslations("common");

  const editor = useEditor({
    // TipTap v3 does not re-render on transactions by default; the toolbar's
    // active/disabled state is derived from the selection, so opt in.
    shouldRerenderOnTransaction: true,
    extensions: [
      // openOnClick would navigate away mid-edit when a link is clicked in the editor.
      StarterKit.configure({ link: { openOnClick: false } }),
      Placeholder.configure({ placeholder: t("docPlaceholder") }),
      CharacterCount.configure({ limit: 20000 }),
      Typography,
      ClassTextAlign.configure({ types: ["heading", "paragraph"] }),
      ClassTextStyle,
      ClassColor,
      ClassFontFamily,
      ClassHighlight,
      ClassTaskList,
      ClassTaskItem.configure({ nested: false }),
      Image.configure({ allowBase64: false, HTMLAttributes: { class: "report-image" } }),
      // TableKit's default cells render align via inline `style` (sanitized away),
      // so register the table family explicitly with class-based cell overrides.
      Table.configure({ resizable: false }),
      TableRow,
      HeaderCell,
      Cell,
      FindAndReplace.configure({ searchDebounceMs: 0 }),
    ],
    content: initialContent,
    editable: !readOnly,
    editorProps: {
      attributes: {
        class: "tiptap print-template-editor min-h-64 max-w-none p-3 focus:outline-none",
      },
    },
    onUpdate: ({ editor: ed }) => onChange(ed.getHTML()),
  });

  const counts = editor?.storage?.characterCount as { characters: () => number; words: () => number } | undefined;

  // biome-ignore lint/nursery/useNullishCoalescing: boolean OR of two flags; ?? would treat false as a value to skip
  const toolbarDisabled = readOnly || !editor;
  // biome-ignore lint/nursery/useNullishCoalescing: boolean OR of two flags; ?? would treat false as a value to skip
  const saveDisabled = isSaving || !canSave;

  return (
    <div className="space-y-3">
      <EditorToolbar editor={editor} reportType={reportType} disabled={toolbarDisabled} />
      <div className="rounded-b-lg border bg-background">
        <EditorContent editor={editor} />
      </div>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-muted-foreground text-xs">{t("placeholderHelp")}</p>
        {counts && (
          <p className="text-muted-foreground text-xs">
            {t("charCount", { chars: counts.characters(), words: counts.words() })}
          </p>
        )}
      </div>
      {readOnly ? (
        <p className="text-muted-foreground text-sm">{t("readOnlyNotice")}</p>
      ) : (
        <div className="flex justify-end">
          {onSave && (
            <Button type="button" size="sm" onClick={onSave} disabled={saveDisabled}>
              {isSaving ? tc("saving") : t("save")}
            </Button>
          )}
        </div>
      )}
    </div>
  );
}
