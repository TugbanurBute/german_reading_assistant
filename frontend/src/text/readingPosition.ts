import type { ReadingDocument, Sentence } from "./segment.ts";

/**
 * Where a user stopped in a saved or built-in text, kept in localStorage under
 * "lesehilfe.position.<userId>.<documentId>": several accounts on one browser, and several users reading the same
 * built-in text, each keep their own position.
 *
 * The anchor is the first visible sentence: its id (its number in the document) plus the start of its
 * text. That is independent of screen size, font size and pagination, unlike a scroll offset in pixels.
 * The text check catches outdated positions, e.g. if the segmentation of the text ever changes.
 */
interface StoredPosition {
  v: 1;
  sentence: number;
  snippet: string;
}

const SNIPPET_LENGTH = 32;
const keyFor = (userId: string, documentId: string) => `lesehilfe.position.${userId}.${documentId}`;
const snippetOf = (sentence: Sentence) => sentence.text.slice(0, SNIPPET_LENGTH);

export function savePosition(userId: string, documentId: string, sentence: Sentence): void {
  const position: StoredPosition = { v: 1, sentence: sentence.id, snippet: snippetOf(sentence) };
  try {
    localStorage.setItem(keyFor(userId, documentId), JSON.stringify(position));
  } catch {
    // Storage full or blocked: the reader just starts at the top next time.
  }
}

/** The sentence to resume at, or null. A missing, broken or outdated entry is removed and yields null. */
export function loadPosition(userId: string, documentId: string, document: ReadingDocument): number | null {
  let stored: unknown;
  try {
    const raw = localStorage.getItem(keyFor(userId, documentId));
    if (raw === null) return null;
    stored = JSON.parse(raw);
  } catch {
    forgetPosition(userId, documentId);
    return null;
  }
  const position = stored as Partial<StoredPosition> | null;
  const sentence =
    position?.v === 1 && Number.isInteger(position.sentence) ? findSentence(document, position.sentence!) : undefined;
  if (!sentence || snippetOf(sentence) !== position?.snippet) {
    forgetPosition(userId, documentId);
    return null;
  }
  return sentence.id;
}

export function forgetPosition(userId: string, documentId: string): void {
  try {
    localStorage.removeItem(keyFor(userId, documentId));
  } catch {
  }
}

/** Removes every stored position of one user, e.g. after their account was deleted. */
export function forgetAllPositions(userId: string): void {
  try {
    const keys = Object.keys(localStorage).filter((key) => key.startsWith(`lesehilfe.position.${userId}.`));
    keys.forEach((key) => localStorage.removeItem(key));
  } catch {
    // Nothing to do.
  }
}

export function findSentence(document: ReadingDocument, sentenceId: number): Sentence | undefined {
  for (const paragraph of document.paragraphs) {
    for (const sentence of paragraph.sentences) {
      if (sentence.id === sentenceId) return sentence;
    }
  }
  return undefined;
}
