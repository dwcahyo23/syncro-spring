"use client";

import { useRef } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Textarea } from "@/components/ui/textarea";

interface WahaTemplateEditorProps {
  value: string;
  onChange: (value: string) => void;
  availableVariables: string[];
  preview?: string;
  onSave?: () => void;
  readOnly?: boolean;
  isSaving?: boolean;
}

const KNOWN_VARIABLE_PATTERN = /\{[^}]+\}/g;

function getUnknownVariables(text: string, known: string[]): string[] {
  const matches = text.match(KNOWN_VARIABLE_PATTERN) ?? [];
  return matches.filter((token) => !known.includes(token));
}

export function WahaTemplateEditor({
  value,
  onChange,
  availableVariables,
  preview,
  onSave,
  readOnly = false,
  isSaving = false,
}: WahaTemplateEditorProps) {
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const unknownVariables = getUnknownVariables(value, availableVariables);
  const hasErrors = unknownVariables.length > 0;
  const canSave = !readOnly && !hasErrors && !isSaving && !!onSave;

  const insertVariable = (variable: string) => {
    const el = textareaRef.current;
    if (!el) return;
    const start = el.selectionStart ?? value.length;
    const end = el.selectionEnd ?? value.length;
    const next = value.slice(0, start) + variable + value.slice(end);
    onChange(next);
    requestAnimationFrame(() => {
      el.setSelectionRange(start + variable.length, start + variable.length);
      el.focus();
    });
  };

  const handleSave = () => {
    if (!canSave) return;
    onSave();
  };

  return (
    <div className="space-y-4">
      {/* Editor */}
      <div className="space-y-2">
        <div className="flex items-center justify-between">
          <label className="text-sm font-medium">
            Template Text
            {!readOnly && <span className="text-destructive ml-1" aria-hidden="true">*</span>}
          </label>
          {!readOnly && (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="outline" size="sm" type="button">
                  Insert Variable
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                {availableVariables.map((variable) => (
                  <DropdownMenuItem
                    key={variable}
                    onSelect={() => insertVariable(variable)}
                  >
                    <code className="text-xs">{variable}</code>
                  </DropdownMenuItem>
                ))}
              </DropdownMenuContent>
            </DropdownMenu>
          )}
        </div>

        <Textarea
          ref={textareaRef}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          readOnly={readOnly}
          rows={8}
          placeholder={
            readOnly
              ? "No template text."
              : "Type your alert message here. Use Insert Variable to add dynamic fields."
          }
          className={hasErrors ? "border-destructive focus-visible:ring-destructive" : ""}
          aria-invalid={hasErrors}
          aria-describedby={hasErrors ? "template-errors" : undefined}
        />

        {hasErrors && (
          <div id="template-errors" className="space-y-1" role="alert">
            {unknownVariables.map((token) => (
              <p key={token} className="text-sm text-destructive">
                Unknown variable: <code>{token}</code>
              </p>
            ))}
          </div>
        )}
      </div>

      {/* Preview */}
      {preview !== undefined && (
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm font-medium text-muted-foreground">
              Preview (sample data)
            </CardTitle>
          </CardHeader>
          <CardContent>
            <pre className="whitespace-pre-wrap text-sm font-mono break-words">
              {preview || <span className="text-muted-foreground italic">Preview will appear here.</span>}
            </pre>
          </CardContent>
        </Card>
      )}

      {/* Save action */}
      {!readOnly && onSave && (
        <div className="flex justify-end">
          <Button
            type="button"
            onClick={handleSave}
            disabled={!canSave}
            aria-disabled={!canSave}
          >
            {isSaving ? "Saving..." : "Save Template"}
          </Button>
        </div>
      )}

      {readOnly && (
        <p className="text-sm text-muted-foreground">
          You have read-only access to this template.
        </p>
      )}
    </div>
  );
}
