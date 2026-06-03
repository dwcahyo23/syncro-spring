"use client";

import Creatable, { type CreatableProps } from "react-select/creatable";
import type { GroupBase, StylesConfig } from "react-select";

import { cn } from "@/lib/utils";

export type CreatableSelectOption = {
  value: string;
  label: string;
};

type CreatableSelectProps<Option extends CreatableSelectOption, IsMulti extends boolean = false> = CreatableProps<
  Option,
  IsMulti,
  GroupBase<Option>
> & {
  invalid?: boolean;
};

const styles = {
  control: () => ({}),
  valueContainer: () => ({}),
  input: () => ({}),
  placeholder: () => ({}),
  singleValue: () => ({}),
  indicatorsContainer: () => ({}),
  dropdownIndicator: () => ({}),
  clearIndicator: () => ({}),
  indicatorSeparator: () => ({}),
  menu: () => ({}),
  menuList: () => ({}),
  option: () => ({}),
  noOptionsMessage: () => ({}),
  loadingMessage: () => ({}),
} satisfies StylesConfig<CreatableSelectOption, boolean, GroupBase<CreatableSelectOption>>;

export function CreatableSelect<Option extends CreatableSelectOption, IsMulti extends boolean = false>({
  className,
  classNames,
  invalid,
  unstyled = true,
  styles: customStyles,
  ...props
}: CreatableSelectProps<Option, IsMulti>) {
  return (
    <Creatable<Option, IsMulti, GroupBase<Option>>
      unstyled={unstyled}
      styles={customStyles ?? (styles as StylesConfig<Option, IsMulti, GroupBase<Option>>)}
      className={cn("text-sm", className)}
      classNames={{
        control: ({ isDisabled, isFocused }) =>
          cn(
            "flex min-h-8 w-full min-w-0 items-center rounded-lg border border-input bg-transparent shadow-xs transition-colors outline-none",
            "focus-within:border-ring focus-within:ring-3 focus-within:ring-ring/50",
            isFocused && "border-ring ring-3 ring-ring/50",
            invalid && "border-destructive ring-3 ring-destructive/20",
            isDisabled && "cursor-not-allowed opacity-50",
            classNames?.control?.({ isDisabled, isFocused } as Parameters<NonNullable<typeof classNames.control>>[0]),
          ),
        valueContainer: (state) =>
          cn("flex flex-1 flex-wrap items-center gap-1 overflow-hidden px-2.5 py-1", classNames?.valueContainer?.(state)),
        input: (state) => cn("min-w-0 text-foreground", classNames?.input?.(state)),
        placeholder: (state) => cn("truncate text-muted-foreground", classNames?.placeholder?.(state)),
        singleValue: (state) => cn("truncate text-foreground", classNames?.singleValue?.(state)),
        indicatorsContainer: (state) => cn("flex items-center self-stretch", classNames?.indicatorsContainer?.(state)),
        dropdownIndicator: (state) => cn("px-2 text-muted-foreground", classNames?.dropdownIndicator?.(state)),
        clearIndicator: (state) => cn("px-2 text-muted-foreground hover:text-foreground", classNames?.clearIndicator?.(state)),
        indicatorSeparator: (state) => cn("my-1 w-px bg-border", classNames?.indicatorSeparator?.(state)),
        menu: (state) =>
          cn(
            "z-50 mt-1 overflow-hidden rounded-lg bg-popover text-popover-foreground shadow-md ring-1 ring-foreground/10",
            classNames?.menu?.(state),
          ),
        menuList: (state) => cn("max-h-72 overflow-y-auto p-1", classNames?.menuList?.(state)),
        option: ({ isDisabled, isFocused, isSelected }) =>
          cn(
            "relative flex w-full cursor-default select-none items-center rounded-md px-2 py-1.5 text-sm outline-none",
            isFocused && "bg-accent text-accent-foreground",
            isSelected && "bg-accent text-accent-foreground",
            isDisabled && "pointer-events-none opacity-50",
          ),
        noOptionsMessage: (state) => cn("px-2 py-1.5 text-muted-foreground text-sm", classNames?.noOptionsMessage?.(state)),
        loadingMessage: (state) => cn("px-2 py-1.5 text-muted-foreground text-sm", classNames?.loadingMessage?.(state)),
        ...classNames,
      }}
      {...props}
    />
  );
}
