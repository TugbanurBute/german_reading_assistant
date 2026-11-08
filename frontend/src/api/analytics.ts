import { apiRequest } from "./client.ts";

export interface VocabularyStats {
  timeZone: string;
  today: number;
  lastSevenDays: number;
  days: Array<{ date: string; count: number }>;
}

export function vocabularyStats(): Promise<VocabularyStats> {
  return apiRequest("GET", "/api/v1/analytics/vocabulary");
}
