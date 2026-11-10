import { apiRequest } from "./client.ts";

export type CefrLevel = "A1" | "A2" | "B1";

export const CEFR_LEVELS: readonly CefrLevel[] = ["A1", "A2", "B1"];

export interface BuiltinTextSummary {
  id: string;
  title: string;
  level: CefrLevel;
  description: string;
  wordCount: number;
  readingMinutes: number;
}

export interface BuiltinText extends BuiltinTextSummary {
  content: string;
}

const BASE = "/api/v1/library";

export function listBuiltinTexts(): Promise<BuiltinTextSummary[]> {
  return apiRequest("GET", BASE);
}

export function getBuiltinText(id: string): Promise<BuiltinText> {
  return apiRequest("GET", `${BASE}/${encodeURIComponent(id)}`);
}
