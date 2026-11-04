import { useEffect, useState } from "react";

/**
 * State remembered in localStorage under "lesehilfe.<key>". Storage can be unavailable (private mode,
 * blocked cookies), so every access is guarded and the default is used instead.
 */
export function usePreference<T extends string>(key: string, allowed: readonly T[], fallback: T) {
  const storageKey = `lesehilfe.${key}`;
  const [value, setValue] = useState<T>(() => {
    try {
      const stored = JSON.parse(localStorage.getItem(storageKey) ?? "null") as unknown;
      return allowed.includes(stored as T) ? (stored as T) : fallback;
    } catch {
      return fallback;
    }
  });

  useEffect(() => {
    try {
      localStorage.setItem(storageKey, JSON.stringify(value));
    } catch {
      // Not remembering a preference is acceptable.
    }
  }, [storageKey, value]);

  return [value, setValue] as const;
}
