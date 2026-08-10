declare module "simple-icons" {
  export type SimpleIcon = { title: string; path: string };
}

declare module "react-hook-form" {
  export type FieldError = { message?: string };
  export type FieldState = { invalid: boolean; error?: FieldError };
  export type ControllerRenderProps = {
    name: string;
    value: string | number | readonly string[] | undefined;
    onChange: (value: unknown) => void;
    onBlur: () => void;
    ref: (instance: unknown) => void;
  };
  export function useForm<T>(options?: unknown): {
    control: unknown;
    handleSubmit: (handler: (data: T) => void) => (event?: unknown) => void;
  };
  export function Controller(props: {
    control: unknown;
    name: string;
    render: (context: { field: ControllerRenderProps; fieldState: FieldState }) => React.ReactNode;
  }): React.ReactNode;
}
