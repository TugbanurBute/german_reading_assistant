import { describe, expect, it } from "vitest";
import { PAGE_CHARACTERS, pageOfSentence, paginate } from "./paginate.ts";
import { SAMPLE_TEXT } from "./sampleText.ts";
import { segmentText } from "./segment.ts";

const sentencesOf = (pages: ReturnType<typeof paginate>) =>
  pages.flatMap((page) => page.paragraphs.flatMap((paragraph) => paragraph.sentences));

const pageLength = (page: ReturnType<typeof paginate>[number]) =>
  page.paragraphs.flatMap((p) => p.sentences).reduce((sum, s) => sum + s.raw.length, 0);

describe("paginate", () => {
  it("keeps a short text on one page, unchanged", () => {
    const document = segmentText(SAMPLE_TEXT);
    const pages = paginate(document);

    expect(pages).toHaveLength(1);
    expect(pages[0]!.paragraphs).toEqual(document.paragraphs);
    expect(pages[0]!.wordCount).toBe(document.wordCount);
  });

  it("splits a long text between paragraphs, keeping every sentence once and in order", () => {
    const document = segmentText(Array.from({ length: 60 }, () => SAMPLE_TEXT).join("\n\n"));
    const pages = paginate(document);

    expect(pages.length).toBeGreaterThan(5);
    expect(sentencesOf(pages)).toEqual(document.paragraphs.flatMap((p) => p.sentences));
    for (const page of pages) {
      expect(pageLength(page)).toBeLessThanOrEqual(PAGE_CHARACTERS);
      // Whole paragraphs only: each page's paragraphs are exactly the document's.
      for (const paragraph of page.paragraphs) {
        expect(paragraph).toBe(document.paragraphs[paragraph.id]);
      }
    }
    expect(pages.reduce((sum, page) => sum + page.wordCount, 0)).toBe(document.wordCount);
  });

  it("splits a paragraph longer than a page between sentences, never inside one", () => {
    const longParagraph = Array.from({ length: 200 }, (_, i) => `Satz ${i + 1} steht hier im Text.`).join(" ");
    const document = segmentText(longParagraph);
    const pages = paginate(document, 1000);

    expect(pages.length).toBeGreaterThan(5);
    expect(sentencesOf(pages).map((s) => s.text)).toEqual(document.paragraphs[0]!.sentences.map((s) => s.text));
    for (const page of pages) {
      expect(page.paragraphs).toHaveLength(1);
      expect(page.paragraphs[0]!.id).toBe(0);
      expect(pageLength(page)).toBeLessThanOrEqual(1000);
    }
  });

  it("gives a single sentence longer than a page a page of its own", () => {
    const huge = `${"Wort ".repeat(400).trim()}.`;
    const document = segmentText(`Kurz davor.\n\n${huge}\n\nKurz danach.`);
    const pages = paginate(document, 1000);

    expect(pages.map((page) => page.paragraphs.flatMap((p) => p.sentences).map((s) => s.text.slice(0, 10)))).toEqual([
      ["Kurz davor"],
      ["Wort Wort "],
      ["Kurz danac"],
    ]);
  });

  it("finds the page of a sentence", () => {
    const document = segmentText(Array.from({ length: 60 }, () => SAMPLE_TEXT).join("\n\n"));
    const pages = paginate(document);
    const last = document.paragraphs.at(-1)!.sentences.at(-1)!.id;

    expect(pageOfSentence(pages, 0)).toBe(0);
    expect(pageOfSentence(pages, last)).toBe(pages.length - 1);
    expect(pageOfSentence(pages, last + 1)).toBe(-1);
  });

  it("returns one empty page for an empty document", () => {
    expect(paginate(segmentText(""))).toEqual([{ paragraphs: [], wordCount: 0, firstSentence: 0, lastSentence: 0 }]);
  });
});
