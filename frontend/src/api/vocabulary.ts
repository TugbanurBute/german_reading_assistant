import { apiRequest } from "./client.ts";
import type { Article, PartOfSpeech, TargetLanguage, TranslationResponse } from "./types.ts";

/** A saved word (VocabularyResponse.java). The source fields are null for unsaved or deleted texts. */
export interface VocabularyEntry {
  id: string;
  originalText: string;
  lemma: string;
  article: Article | null;
  partOfSpeech: PartOfSpeech | null;
  translation: string;
  targetLanguage: TargetLanguage;
  sourceSentence: string | null;
  sourceDocumentId: string | null;
  sourceDocumentTitle: string | null;
  createdAt: string;
}

/** SaveVocabularyRequest.java: a translation the reader already has, so saving never translates again. */
export interface SaveVocabularyInput {
  originalText: string;
  lemma: string | null;
  article: Article | null;
  partOfSpeech: PartOfSpeech | null;
  translation: string;
  targetLanguage: TargetLanguage;
  sourceSentence: string | null;
  sourceDocumentId: string | null;
}

const BASE = "/api/v1/vocabulary";

export function listVocabulary(): Promise<VocabularyEntry[]> {
  return apiRequest("GET", BASE);
}

/** Resolves with the new entry, or with the existing one when this meaning is already saved. */
export function saveVocabulary(input: SaveVocabularyInput): Promise<VocabularyEntry> {
  return apiRequest("POST", BASE, input);
}

export function deleteVocabulary(id: string): Promise<void> {
  return apiRequest("DELETE", `${BASE}/${encodeURIComponent(id)}`);
}

/** The input for saving a word translation shown in the reader. */
export function toSaveInput(
  data: TranslationResponse,
  sourceSentence: string | null,
  sourceDocumentId: string | null,
): SaveVocabularyInput {
  return {
    originalText: data.originalText,
    lemma: data.lemma,
    article: data.grammar?.kind === "NOUN" ? data.grammar.article : null,
    partOfSpeech: data.partOfSpeech,
    translation: data.translation,
    targetLanguage: data.targetLanguage,
    sourceSentence,
    sourceDocumentId,
  };
}

const normalize = (text: string) => text.normalize("NFC").replace(/\s+/g, " ").trim().toLowerCase();

/**
 * Identifies one meaning the way the backend does (ux_vocabulary_entry_meaning): lemma, or the word when
 * there is none, plus translation and language, ignoring case and extra whitespace.
 */
export function meaningKey(entry: Pick<SaveVocabularyInput, "originalText" | "lemma" | "translation" | "targetLanguage">) {
  const lemma = entry.lemma?.trim() ? entry.lemma : entry.originalText;
  return `${normalize(lemma)}|${entry.targetLanguage}|${normalize(entry.translation)}`;
}
