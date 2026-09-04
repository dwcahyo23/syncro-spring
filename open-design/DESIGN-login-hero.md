# Login Hero — ThreeUI Signal-Particles (design spec untuk syncro/apps/web)

Referensi visual: `open-design/login-hero-three.html` (sudah disalin ke folder ini).
Target: mengganti layout login bawaan di `syncro/apps/web` (Next.js + Tailwind + shadcn)
menjadi split **hero gelap (kiri) + form (kanan)** dengan canvas partikel animasi.

## Layout

| Breakpoint | Layout |
|---|---|
| ≥ 961px | Grid `1.2fr 1fr`, full `min-h-dvh`. Kiri = hero gelap `#0a0a0a`, kanan = form `bg-background` |
| ≤ 960px | Hero disembunyikan (`hidden lg:flex`), form full-width padding 32–40px |

Hero berisi 3 layer (z-index): aura radial-gradient biru (z0) → canvas partikel (z1, opacity .8) → brand + copy (z2).

## Token (SyncroApp locked)

```
--pri    #2563EB   Signal Blue   (highlight partikel, CTA)
--cyan   #0891B2   Telemetry     (eyebrow, highlight partikel)
--stage-dark #0a0a0a / ink #F8FAFC
--ease   cubic-bezier(.16,1,.3,1)
```

Anti-purple lock: warna partikel hanya slate `rgba(148,163,184,α)` + dua highlight di atas.

## Hero copy

- Eyebrow mono: `Syncro · Live Machine Channel`
- H1 (40px/1.12, max 16ch): `One channel for every machine that never sleeps.`
- Sub (14px, 72% ink): `Machine setup, live health, and spare-part alerts — command the factory floor from your pocket.`
- Brand: Orbit mono SVG inline (currentColor → stage ink, dot cyan) + `Syncro`.

## Canvas engine — aturan penting

Grid spacing 16, dotRadius 1.5, dual sine wave (`time += 0.02 * speed`).
**Loop `requestAnimationFrame` selalu jalan — JANGAN pasang guard
`prefers-reduced-motion` yang berhenti di frame pertama** (itu bug yang membekukan
partikel saat animasi Windows mati). Retry `resize()` maks 120 frame kalau canvas
terukur 0 saat first paint.

## Perubahan file di `syncro/apps/web`

### 1. Baru: `src/features/auth/auth-hero.tsx`

```tsx
"use client";
import { useEffect, useRef } from "react";

export function AuthHero() {
  const ref = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = ref.current!;
    const ctx = canvas.getContext("2d")!;
    let w = 0, h = 0, time = 0, raf = 0, retries = 0;

    const resize = () => {
      w = canvas.width = canvas.clientWidth;
      h = canvas.height = canvas.clientHeight;
    };
    const onResize = () => resize();
    window.addEventListener("resize", onResize);
    resize();

    const draw = () => {
      if (w < 2 || h < 2) {
        resize();
        if ((w < 2 || h < 2) && retries++ < 120) { raf = requestAnimationFrame(draw); return; }
      }
      ctx.clearRect(0, 0, w, h);
      const spacing = 16, cols = Math.floor(w / spacing), rows = Math.floor(h / spacing);
      const ox = (w - cols * spacing) / 2, oy = (h - rows * spacing) / 2;
      for (let i = 0; i <= cols; i++) for (let j = 0; j <= rows; j++) {
        const nx = i * 0.1, ny = j * 0.1;
        const value = Math.sin(nx + time * 0.5) * Math.cos(ny - time * 0.3)
                    + Math.sin(nx * 0.5 - ny * 0.5 + time * 0.8);
        if (value <= 0.1) continue;
        const hl = Math.sin(i * 12.34) * Math.cos(j * 56.78);
        ctx.beginPath();
        ctx.arc(ox + i * spacing, oy + j * spacing, 1.5, 0, Math.PI * 2);
        ctx.fillStyle = hl > 0.98 ? "#2563EB"
          : hl < -0.98 ? "#0891B2"
          : `rgba(148,163,184,${Math.min(0.6, (value - 0.1) * 0.8)})`;
        ctx.fill();
      }
      time += 0.02; // ponytail: speed 1.0 tetap — kontrol batch belum perlu
      raf = requestAnimationFrame(draw); // selalu animate, no reduced-motion freeze
    };
    draw();
    return () => { cancelAnimationFrame(raf); window.removeEventListener("resize", onResize); };
  }, []);

  return (
    <aside aria-hidden className="relative hidden overflow-hidden bg-[#0a0a0a] lg:flex lg:flex-col"
      style={{ borderRight: "1px solid rgba(248,250,252,.10)" }}>
      <div className="pointer-events-none absolute inset-0"
        style={{ background: "radial-gradient(640px 420px at 68% 30%, rgba(37,99,235,.16), transparent 70%)" }} />
      <canvas ref={ref} className="absolute inset-0 z-[1] h-full w-full opacity-80" />

      <div className="relative z-[2] flex items-center gap-3 px-11 pt-9 text-[#F8FAFC]">
        <svg width="28" height="28" viewBox="0 0 28 28" fill="none" className="shrink-0">
          <circle cx="14" cy="14" r="12.5" stroke="currentColor" strokeWidth="1.6" />
          <circle cx="14" cy="14" r="4" fill="currentColor" />
          <circle cx="24.2" cy="8.5" r="2.4" fill="#0891B2" />
        </svg>
        <span className="text-[17px] font-semibold tracking-wide">Syncro</span>
      </div>

      <div className="pointer-events-none relative z-[2] mt-auto px-11 pb-10 text-[#F8FAFC]">
        <p className="mb-3.5 font-mono text-[11px] font-semibold uppercase tracking-[.14em] text-[#0891B2]">
          Syncro · Live Machine Channel</p>
        <h2 className="max-w-[16ch] text-[40px] font-semibold leading-[1.12] tracking-[-.02em]">
          One channel for every machine that never sleeps.</h2>
        <p className="mt-3 max-w-[44ch] text-sm leading-relaxed text-[rgba(248,250,252,.72)]">
          Machine setup, live health, and spare-part alerts — command the factory floor from your pocket.</p>
      </div>
    </aside>
  );
}
```

### 2. Edit: `src/app/(main)/auth/v2/layout.tsx`

```tsx
import type { ReactNode } from "react";
import { AuthHero } from "@/features/auth/auth-hero";

export default function Layout({ children }: Readonly<{ children: ReactNode }>) {
  return (
    <div className="grid min-h-dvh lg:grid-cols-[1.2fr_1fr]">
      <AuthHero />
      <div className="flex items-center justify-center bg-background p-8 lg:p-12">{children}</div>
    </div>
  );
}
```

### 3. Edit: `src/app/(main)/auth/v2/login/page.tsx`

Hapus `text-center` + `max-w-md` wrapper lama — panel kanan sudah membatasi lebar:

```tsx
<div className="w-full max-w-[400px] space-y-6">
  <div className="space-y-2 text-left">
    <p className="font-mono text-[11px] font-semibold uppercase tracking-[.14em] text-primary">Secure access</p>
    <h1 className="text-2xl font-bold tracking-tight">Sign in to Syncro</h1>
  </div>
  <LoginForm />
  <p className="border-t pt-4 text-xs text-muted-foreground">{APP_CONFIG.copyright}</p>
</div>
```

`LoginForm` tidak berubah (API call-nya sudah benar); opsional tambah ikon mail/lock
di dalam `Input` dan tombol show/hide password seperti referensi.

## Verifikasi

1. `npm run dev` → buka `/auth/v2/login`.
2. Partikel bergerak terus walau Windows "Show animations off" — cek DevTools, loop tidak boleh return dini.
3. Resize 1920 → 360px: tidak ada horizontal scroll, hero hilang ≤960px, form tetap fokus.
4. Submit kredensial salah → alert error muncul, tombol unlock lagi.
