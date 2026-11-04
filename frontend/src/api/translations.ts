import { config } from "../config.ts";
import type { TranslationRequest, TranslationResponse } from "./types.ts";

/** Backend error codes (ErrorCode.java), plus NETWORK_ERROR when the server can't be reached at all. */
export type ErrorCode =
  | "VALIDATION_ERROR"
  | "MALFORMED_REQUEST"
  | "REQUEST_ERROR"
  | "MOCK_TRANSLATION_NOT_FOUND"
  | "PROVIDER_INVALID_RESPONSE"
  | "PROVIDER_RATE_LIMITED"
  | "PROVIDER_UNAVAILABLE"
  | "PROVIDER_TIMEOUT"
  | "INTERNAL_ERROR"
  | "NETWORK_ERROR";

export class ApiError extends Error {
  readonly code: ErrorCode;

  constructor(code: ErrorCode, message: string) {
    super(message);
    this.name = "ApiError";
    this.code = code;
  }
}

// Successful answers for this browser session. The backend caches too; this avoids even the round trip
// when the reader hovers the same word again. Failed requests are removed so they can be retried.
const sessionCache = new Map<string, Promise<TranslationResponse>>();

export function translate(request: TranslationRequest): Promise<TranslationResponse> {
  const key = JSON.stringify(request);
  let pending = sessionCache.get(key);
  if (!pending) {
    pending = post(request);
    pending.catch(() => sessionCache.delete(key));
    sessionCache.set(key, pending);
  }
  return pending;
}

export function clearTranslationCache(): void {
  sessionCache.clear();
}

async function post(request: TranslationRequest): Promise<TranslationResponse> {
  let response: Response;
  try {
    response = await fetch(`${config.apiBaseUrl}/api/v1/translations`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(request),
    });
  } catch {
    throw new ApiError("NETWORK_ERROR", "The server could not be reached.");
  }

  if (response.ok) {
    return (await response.json()) as TranslationResponse;
  }

  // Errors are RFC 9457 problem details with a "code" property; anything else is unexpected.
  const problem = (await response.json().catch(() => null)) as { code?: ErrorCode; detail?: string } | null;
  throw new ApiError(problem?.code ?? "INTERNAL_ERROR", problem?.detail ?? `HTTP ${response.status}`);
}
