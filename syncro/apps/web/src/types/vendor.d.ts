declare module "simple-icons" {
  export type SimpleIcon = { title: string; path: string };
}

// react-hook-form previously had a hand-written module shim here; removed for
// story 20-2 because the real package (7.84.0) ships its own types and the shim
// shadowed them (same fix pattern as the lucide-react shim in 3-8).
