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
  | "DOCUMENT_NOT_FOUND"
  | "INVALID_UPLOAD"
  | "UPLOAD_TOO_LARGE"
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

/** Calls the backend. Resolves with the parsed JSON (undefined for 204), rejects with an ApiError. */
export async function apiRequest<T>(method: "GET" | "POST" | "DELETE", path: string, body?: Body): Promise<T> {
  const init: RequestInit = { method };
  if (body instanceof FormData) {
    init.body = body;
  } else if (body !== undefined) {
    init.headers = { "Content-Type": "application/json" };
    init.body = JSON.stringify(body);
  }

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
  throw new ApiError(problem?.code ?? "INTERNAL_ERROR", problem?.detail ?? `HTTP ${response.status}`);
}
