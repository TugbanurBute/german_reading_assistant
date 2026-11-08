import { config } from "../config.ts";

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
  | "PROVIDER_MODEL_UNAVAILABLE"
  | "PROVIDER_REJECTED"
  | "DOCUMENT_NOT_FOUND"
  | "INVALID_UPLOAD"
  | "UPLOAD_TOO_LARGE"
  | "VOCABULARY_NOT_FOUND"
  | "UNAUTHENTICATED"
  | "INVALID_CREDENTIALS"
  | "EMAIL_TAKEN"
  | "FORBIDDEN"
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

/** JSON request body, or FormData for uploads (the browser then sets the multipart Content-Type). */
type Body = object | FormData;

type Method = "GET" | "POST" | "PATCH" | "DELETE";

let sessionExpiredListener: (() => void) | null = null;

/** Called when a request is refused because the login ended (expired or invalid token). */
export function onSessionExpired(listener: (() => void) | null): void {
  sessionExpiredListener = listener;
}

/**
 * The CSRF token Spring Security puts in the (deliberately readable) XSRF-TOKEN cookie. Echoing it in a header
 * proves the request comes from this page: other sites can't read our cookies.
 */
function csrfToken(): string | null {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
  return match ? decodeURIComponent(match[1]!) : null;
}

/**
 * Calls the backend. Resolves with the parsed JSON (undefined for 204), rejects with an ApiError.
 * The login travels in an HttpOnly cookie that this code never sees; `credentials: "include"` sends it along.
 */
export async function apiRequest<T>(method: Method, path: string, body?: Body): Promise<T> {
  const headers: Record<string, string> = {};
  const init: RequestInit = { method, credentials: "include", headers };
  if (body instanceof FormData) {
    init.body = body;
  } else if (body !== undefined) {
    headers["Content-Type"] = "application/json";
    init.body = JSON.stringify(body);
  }
  const token = method === "GET" ? null : csrfToken();
  if (token) headers["X-XSRF-TOKEN"] = token;

  let response: Response;
  try {
    response = await fetch(`${config.apiBaseUrl}${path}`, init);
  } catch {
    throw new ApiError("NETWORK_ERROR", "The server could not be reached.");
  }

  if (response.ok) {
    return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
  }

  // Errors are RFC 9457 problem details with a "code" property; anything else is unexpected.
  const problem = (await response.json().catch(() => null)) as { code?: ErrorCode; detail?: string } | null;
  const error = new ApiError(problem?.code ?? "INTERNAL_ERROR", problem?.detail ?? `HTTP ${response.status}`);
  if (error.code === "UNAUTHENTICATED" && !path.startsWith("/api/v1/auth/")) {
    sessionExpiredListener?.();
  }
  throw error;
}
