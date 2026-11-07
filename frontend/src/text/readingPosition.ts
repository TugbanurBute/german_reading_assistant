import type { ReadingDocument, Sentence } from "./segment.ts";

/**
 * Where the reader stopped in a saved text, kept in localStorage under "lesehilfe.position.<documentId>".
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
const keyFor = (documentId: string) => `lesehilfe.position.${documentId}`;
const snippetOf = (sentence: Sentence) => sentence.text.slice(0, SNIPPET_LENGTH);

export function savePosition(documentId: string, sentence: Sentence): void {
  const position: StoredPosition = { v: 1, sentence: sentence.id, snippet: snippetOf(sentence) };
  try {
    localStorage.setItem(keyFor(documentId), JSON.stringify(position));
  } catch {
    // Storage full or blocked: the reader just starts at the top next time.
  }
}

/** The sentence to resume at, or null. A missing, broken or outdated entry is removed and yields null. */
export function loadPosition(documentId: string, document: ReadingDocument): number | null {
  let stored: unknown;
  try {
    const raw = localStorage.getItem(keyFor(documentId));
    if (raw === null) return null;
    stored = JSON.parse(raw);
  } catch {
    forgetPosition(documentId);
    return null;
  }
  const position = stored as Partial<StoredPosition> | null;
  const sentence =
    position?.v === 1 && Number.isInteger(position.sentence) ? findSentence(document, position.sentence!) : undefined;
  if (!sentence || snippetOf(sentence) !== position?.snippet) {
    forgetPosition(documentId);
    return null;
  }
  return sentence.id;
}

export function forgetPosition(documentId: string): void {
  try {
    localStorage.removeItem(keyFor(documentId));
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
