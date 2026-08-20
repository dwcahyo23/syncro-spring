/**
 * Mask phone keeping first 3 and last 3 chars, middle replaced with ***.
 * Example: +628123456789 -> +62***789
 * Null/undefined stays null; strings <=6 returned as-is.
 * This mirrors backend NotificationHistoryDtos.maskPhone() — keep both in sync.
 */
export function maskPhone(phone?: string | null): string | null {
  if (phone == null) return null;
  const trimmed = phone.trim();
  if (trimmed.length <= 6) return trimmed;
  return `${trimmed.slice(0, 3)}***${trimmed.slice(-3)}`;
}
