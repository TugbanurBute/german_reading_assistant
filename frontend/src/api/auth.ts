import { apiRequest } from "./client.ts";

/** UserResponse.java. The token itself is never visible to JavaScript (HttpOnly cookie). */
export interface User {
  id: string;
  email: string;
}

/** Same rules as RegisterRequest.java. */
export const MIN_PASSWORD_LENGTH = 8;
export const MAX_PASSWORD_BYTES = 72;

const BASE = "/api/v1/auth";

/** The logged-in user; rejects with UNAUTHENTICATED when there is no valid login. */
export function currentUser(): Promise<User> {
  return apiRequest("GET", `${BASE}/me`);
}

export function login(email: string, password: string): Promise<User> {
  return apiRequest("POST", `${BASE}/login`, { email, password });
}

export function register(email: string, password: string): Promise<User> {
  return apiRequest("POST", `${BASE}/register`, { email, password });
}

export function logout(): Promise<void> {
  return apiRequest("POST", `${BASE}/logout`);
}

/** The password problem to show before sending, or null. */
export function passwordProblem(password: string): string | null {
  if (password.length < MIN_PASSWORD_LENGTH) return `Use at least ${MIN_PASSWORD_LENGTH} characters.`;
  if (new TextEncoder().encode(password).length > MAX_PASSWORD_BYTES) return "This password is too long.";
  return null;
}
