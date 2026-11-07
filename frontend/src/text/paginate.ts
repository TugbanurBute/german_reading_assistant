import type { Paragraph, ReadingDocument, Sentence } from "./segment.ts";

/**
 * About 600 words: a few screens of reading, and at most a few hundred word buttons on screen at once
 * (a 100,000-character text would otherwise render roughly 15,000).
 */
export const PAGE_CHARACTERS = 4000;

/** A page of the document. It keeps the document's sentence ids and word indexes, which run across pages. */
export interface Page extends ReadingDocument {
  /** The sentence ids on this page, first and last (inclusive). */
  firstSentence: number;
  lastSentence: number;
}

const sentenceLength = (sentence: Sentence) => sentence.raw.length;

/**
 * Splits a segmented document into pages of about `budget` characters. Pages break between paragraphs;
 * a paragraph longer than a page is split between sentences. Words and sentences are never split, so a
 * single sentence longer than the budget gets a page of its own. Texts up to `budget` stay one page.
 */
export function paginate(document: ReadingDocument, budget = PAGE_CHARACTERS): Page[] {
  const pages: Page[] = [];
  let paragraphs: Paragraph[] = [];
  let size = 0;

  const closePage = () => {
    if (paragraphs.length === 0) return;
    pages.push(toPage(paragraphs));
    paragraphs = [];
    size = 0;
  };

  for (const paragraph of document.paragraphs) {
    const length = paragraph.sentences.reduce((sum, sentence) => sum + sentenceLength(sentence), 0);
    if (size > 0 && size + length > budget) closePage();
    if (length <= budget) {
      paragraphs.push(paragraph);
      size += length;
      continue;
    }
    // Longer than a page: continue it across pages, sentence by sentence (same paragraph id on each).
    let part: Sentence[] = [];
    for (const sentence of paragraph.sentences) {
      if (size + sentenceLength(sentence) > budget && size > 0) {
        if (part.length > 0) paragraphs.push({ id: paragraph.id, sentences: part });
        closePage();
        part = [];
      }
      part.push(sentence);
      size += sentenceLength(sentence);
    }
    if (part.length > 0) paragraphs.push({ id: paragraph.id, sentences: part });
  }
  closePage();

  return pages.length > 0 ? pages : [toPage([])];
}

function toPage(paragraphs: Paragraph[]): Page {
  const sentences = paragraphs.flatMap((paragraph) => paragraph.sentences);
  const wordCount = sentences.reduce((sum, s) => sum + s.tokens.filter((token) => token.wordIndex !== null).length, 0);
  return {
    paragraphs,
    wordCount,
    firstSentence: sentences[0]?.id ?? 0,
    lastSentence: sentences.at(-1)?.id ?? 0,
  };
}

/** The index of the page that contains this sentence, or -1. */
export function pageOfSentence(pages: Page[], sentenceId: number): number {
  return pages.findIndex((page) => sentenceId >= page.firstSentence && sentenceId <= page.lastSentence);
}
