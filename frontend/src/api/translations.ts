import { apiRequest } from "./client.ts";
import type { TranslationRequest, TranslationResponse } from "./types.ts";

export { ApiError, type ErrorCode } from "./client.ts";

// Successful answers for this browser session. The backend caches too; this avoids even the round trip
// when the reader hovers the same word again. Failed requests are removed so they can be retried.
const sessionCache = new Map<string, Promise<TranslationResponse>>();

export function translate(request: TranslationRequest): Promise<TranslationResponse> {
  const key = JSON.stringify(request);
  let pending = sessionCache.get(key);
  if (!pending) {
    pending = apiRequest<TranslationResponse>("POST", "/api/v1/translations", request);
    pending.catch(() => sessionCache.delete(key));
    sessionCache.set(key, pending);
  }
  return pending;
}

export function clearTranslationCache(): void {
  sessionCache.clear();
}
