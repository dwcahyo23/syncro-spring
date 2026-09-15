"use client";

import type { ReactNode } from "react";

import { TableKit } from "@tiptap/extension-table";
import { type Editor, EditorContent, useEditor } from "@tiptap/react";
import StarterKit from "@tiptap/starter-kit";
import {
  Bold,
  Braces,
  ChevronDown,
  Grid3x3,
  Heading2,
  Heading3,
  Italic,
  List,
  ListOrdered,
  Trash2,
  Underline as UnderlineIcon,
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
import { REPORT_TEMPLATE_PLACEHOLDERS, type ReportPrintTemplateType } from "@/lib/print-template";

/**
 * TipTap WYSIWYG editor for the global print templates (spec "Template Laporan Cetak").
 * Toolbar: bold/italic/underline, bullet/ordered list, headings, table editing
 * (insert + row/column/header ops) and a placeholder inserter — the user explicitly
 * asked for ruled tables, and tables come from the first-party table extensions.
 * No `style` attribute is produced; the print stylesheet gives tables their borders.
 *
 * Controlled from the outside: the parent seeds `initialContent` and remounts this
 * component (React `key`) when the edited report type changes, so a half-edited tab
 * can never be submitted under the wrong type. `onChange` reports `editor.getHTML()`.
 * TipTap v3 defers editor creation to the client by default (immediatelyRender: false),
 * so this stays hydration-safe in App Router.
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

  return (
    <div className="flex flex-wrap items-center gap-0.5 rounded-t-lg border border-b-0 bg-muted/40 p-1">
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
      <ToolbarSeparator />
      <ToolbarButton
        label={tt("heading2")}
        onClick={() => editor?.chain().focus().toggleHeading({ level: 2 }).run()}
        active={editor?.isActive("heading", { level: 2 })}
        disabled={disabled}
      >
        <Heading2 className="size-4" />
      </ToolbarButton>
      <ToolbarButton
        label={tt("heading3")}
        onClick={() => editor?.chain().focus().toggleHeading({ level: 3 }).run()}
        active={editor?.isActive("heading", { level: 3 })}
        disabled={disabled}
      >
        <Heading3 className="size-4" />
      </ToolbarButton>
      <ToolbarButton
        label={tt("paragraph")}
        onClick={() => editor?.chain().focus().setParagraph().run()}
        active={editor?.isActive("paragraph")}
        disabled={disabled}
      >
        <span className="font-medium text-xs">¶</span>
      </ToolbarButton>
      <ToolbarSeparator />
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
      <ToolbarSeparator />
      <ToolbarButton
        label={tt("insertTable")}
        onClick={() => editor?.chain().focus().insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run()}
        disabled={disabled || inTable}
      >
        <Grid3x3 className="size-4" />
      </ToolbarButton>
      <ToolbarButton
        label={tt("addRow")}
        onClick={() => editor?.chain().focus().addRowAfter().run()}
        disabled={disabled || !inTable}
      >
        <span className="font-semibold text-[10px]">+&#8615;</span>
      </ToolbarButton>
      <ToolbarButton
        label={tt("deleteRow")}
        onClick={() => editor?.chain().focus().deleteRow().run()}
        disabled={disabled || !inTable}
      >
        <span className="font-semibold text-[10px]">&minus;&#8615;</span>
      </ToolbarButton>
      <ToolbarButton
        label={tt("addColumn")}
        onClick={() => editor?.chain().focus().addColumnAfter().run()}
        disabled={disabled || !inTable}
      >
        <span className="font-semibold text-[10px]">+&#8739;</span>
      </ToolbarButton>
      <ToolbarButton
        label={tt("deleteColumn")}
        onClick={() => editor?.chain().focus().deleteColumn().run()}
        disabled={disabled || !inTable}
      >
        <span className="font-semibold text-[10px]">&minus;&#8739;</span>
      </ToolbarButton>
      <ToolbarButton
        label={tt("toggleHeaderRow")}
        onClick={() => editor?.chain().focus().toggleHeaderRow().run()}
        disabled={disabled || !inTable}
      >
        <span className="font-semibold text-[10px]">H</span>
      </ToolbarButton>
      <ToolbarButton
        label={tt("deleteTable")}
        onClick={() => editor?.chain().focus().deleteTable().run()}
        disabled={disabled || !inTable}
      >
        <Trash2 className="size-4" />
      </ToolbarButton>
      <ToolbarSeparator />
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
    extensions: [StarterKit, TableKit.configure({ table: { resizable: false } })],
    content: initialContent,
    editable: !readOnly,
    editorProps: {
      attributes: {
        class: "tiptap print-template-editor min-h-64 max-w-none p-3 focus:outline-none",
      },
    },
    onUpdate: ({ editor: ed }) => onChange(ed.getHTML()),
  });

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
      <p className="text-muted-foreground text-xs">{t("placeholderHelp")}</p>
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
