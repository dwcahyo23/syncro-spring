"use client";

import { useEffect, useRef } from "react";

import { useTranslations } from "next-intl";

/**
 * ThreeUI signal-particles hero (dark stage) — spec: open-design/DESIGN-login-hero.md
 * Canvas engine from login-hero-three.html. The rAF loop must ALWAYS run:
 * do not add a prefers-reduced-motion early-return (it froze the particles).
 */
export function AuthHero() {
  const th = useTranslations("auth.hero");
  const ref = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = ref.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    let w = 0,
      h = 0,
      time = 0,
      raf = 0,
      retries = 0;

    const resize = () => {
      w = canvas.width = canvas.clientWidth;
      h = canvas.height = canvas.clientHeight;
    };
    window.addEventListener("resize", resize);
    resize();

    const draw = () => {
      // Canvas can measure 0 on first paint — retry instead of freezing empty.
      if (w < 2 || h < 2) {
        resize();
        if ((w < 2 || h < 2) && retries++ < 120) {
          raf = requestAnimationFrame(draw);
          return;
        }
      }
      ctx.clearRect(0, 0, w, h);
      const spacing = 16;
      const cols = Math.floor(w / spacing);
      const rows = Math.floor(h / spacing);
      const ox = (w - cols * spacing) / 2;
      const oy = (h - rows * spacing) / 2;

      for (let i = 0; i <= cols; i++) {
        for (let j = 0; j <= rows; j++) {
          const nx = i * 0.1;
          const ny = j * 0.1;
          const value =
            Math.sin(nx + time * 0.5) * Math.cos(ny - time * 0.3) + Math.sin(nx * 0.5 - ny * 0.5 + time * 0.8);
          if (value <= 0.1) continue;

          const hl = Math.sin(i * 12.34) * Math.cos(j * 56.78);
          ctx.beginPath();
          ctx.arc(ox + i * spacing, oy + j * spacing, 1.5, 0, Math.PI * 2);
          ctx.fillStyle =
            hl > 0.98
              ? "#2563EB" // Signal Blue highlight
              : hl < -0.98
                ? "#0891B2" // Telemetry Cyan highlight
                : `rgba(148,163,184,${Math.min(0.6, (value - 0.1) * 0.8)})`;
          ctx.fill();
        }
      }

      time += 0.02; // ponytail: speed 1.0 fixed — batch speed control not needed yet
      raf = requestAnimationFrame(draw);
    };
    draw();

    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener("resize", resize);
    };
  }, []);

  return (
    <aside
      aria-hidden
      className="relative hidden flex-col overflow-hidden bg-[#0a0a0a] lg:flex"
      style={{ borderRight: "1px solid rgba(248,250,252,.10)" }}
    >
      <div
        className="pointer-events-none absolute inset-0"
        style={{
          background: "radial-gradient(640px 420px at 68% 30%, rgba(37,99,235,.16), transparent 70%)",
        }}
      />
      <canvas ref={ref} className="absolute inset-0 z-[1] h-full w-full opacity-80" />

      <div className="relative z-[2] flex items-center gap-3 px-11 pt-9 text-[#F8FAFC]">
        {/* Orbit mono logo — inline SVG, currentColor → stage ink, cyan dot */}
        <svg width="28" height="28" viewBox="0 0 28 28" fill="none" className="shrink-0">
          <circle cx="14" cy="14" r="12.5" stroke="currentColor" strokeWidth="1.6" />
          <circle cx="14" cy="14" r="4" fill="currentColor" />
          <circle cx="24.2" cy="8.5" r="2.4" fill="#0891B2" />
        </svg>
        <span className="text-[17px] font-semibold tracking-wide">Syncro</span>
      </div>

      <div className="pointer-events-none relative z-[2] mt-auto px-11 pb-10 text-[#F8FAFC]">
        <p className="mb-3.5 font-mono text-[11px] font-semibold uppercase tracking-[.14em] text-[#0891B2]">
          {th("eyebrow")}
        </p>
        <h2 className="max-w-[16ch] text-[40px] font-semibold leading-[1.12] tracking-[-.02em]">{th("title")}</h2>
        <p className="mt-3 max-w-[44ch] text-sm leading-relaxed text-[rgba(248,250,252,.72)]">{th("subtitle")}</p>
      </div>
    </aside>
  );
}
