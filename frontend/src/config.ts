/**
 * Build-time configuration. Vite replaces import.meta.env.VITE_* when bundling,
 * so these values are public — never put secrets in frontend environment variables.
 */
export const config = {
  apiBaseUrl: import.meta.env.VITE_API_BASE_URL ?? (import.meta.env.DEV ? "http://localhost:8080" : ""),
} as const;
