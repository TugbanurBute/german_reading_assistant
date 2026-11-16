import { apiRequest } from "./client.ts";

export const MAX_TITLE_LENGTH = 200;
export const MAX_CONTENT_LENGTH = 100_000;
export const MAX_TXT_UPLOAD_BYTES = 512 * 1024;
export const MAX_PDF_UPLOAD_BYTES = 5 * 1024 * 1024;

export type ProcessingStatus = "PENDING" | "PROCESSING" | "COMPLETED" | "FAILED";

export interface DocumentSummary {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
  processingStatus: ProcessingStatus;
  wordCount: number | null;
  characterCount: number | null;
  paragraphCount: number | null;
}

export interface SavedDocument extends DocumentSummary {
  content: string;
}

const BASE = "/api/v1/documents";

export function listDocuments(): Promise<DocumentSummary[]> {
  return apiRequest("GET", BASE);
}

export function getDocument(id: string): Promise<SavedDocument> {
  return apiRequest("GET", `${BASE}/${encodeURIComponent(id)}`);
}

export function createDocument(title: string, content: string): Promise<SavedDocument> {
  return apiRequest("POST", BASE, { title, content });
}

export function uploadDocument(file: File): Promise<SavedDocument> {
  const form = new FormData();
  form.append("file", file);
  return apiRequest("POST", `${BASE}/upload`, form);
}

export function deleteDocument(id: string): Promise<void> {
  return apiRequest("DELETE", `${BASE}/${encodeURIComponent(id)}`);
}

/** A title for pasted text without one: its first line, shortened at a word boundary. */
export function titleFromText(text: string, maxLength = 60): string {
  const firstLine = text.trim().split("\n")[0]?.trim() ?? "";
  if (firstLine.length <= maxLength) return firstLine;
  const cut = firstLine.slice(0, maxLength);
  const lastSpace = cut.lastIndexOf(" ");
  return `${(lastSpace > maxLength / 2 ? cut.slice(0, lastSpace) : cut).trimEnd()}…`;
}

export function renameDocument(id: string, title: string): Promise<DocumentSummary> {
  return apiRequest("PATCH", `${BASE}/${encodeURIComponent(id)}`, { title });
}
