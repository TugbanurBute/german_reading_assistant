import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { findSentence, forgetPosition, loadPosition, savePosition } from "./readingPosition.ts";
import { SAMPLE_TEXT } from "./sampleText.ts";
import { segmentText } from "./segment.ts";

const KEY = "lesehilfe.position.doc-1";
const document = segmentText(SAMPLE_TEXT);

beforeEach(() => localStorage.clear());
afterEach(() => vi.restoreAllMocks());

describe("reading position", () => {
  it("saves a sentence anchor and loads it again", () => {
    savePosition("doc-1", findSentence(document, 4)!);

    expect(JSON.parse(localStorage.getItem(KEY)!)).toEqual({
      v: 1,
      sentence: 4,
      snippet: "Leider ist das Schloss an meiner",
    });
    expect(loadPosition("doc-1", document)).toBe(4);
  });

  it("returns null when nothing is saved", () => {
    expect(loadPosition("doc-1", document)).toBeNull();
  });

  it.each([
    ["not JSON", "{oops"],
    ["another shape", JSON.stringify({ paragraph: 2, offset: 40 })],
    ["a sentence that doesn't exist", JSON.stringify({ v: 1, sentence: 999, snippet: "x" })],
    ["a sentence whose text changed", JSON.stringify({ v: 1, sentence: 4, snippet: "Ganz anderer Text" })],
    ["a newer format", JSON.stringify({ v: 2, sentence: 4, snippet: "Leider ist das Schloss an meiner" })],
  ])("ignores and removes %s", (_, stored) => {
    localStorage.setItem(KEY, stored);

    expect(loadPosition("doc-1", document)).toBeNull();
    expect(localStorage.getItem(KEY)).toBeNull();
  });

  it("works without storage", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });

    expect(() => savePosition("doc-1", findSentence(document, 0)!)).not.toThrow();
    expect(loadPosition("doc-1", document)).toBeNull();
  });

  it("forgets a document's position", () => {
    savePosition("doc-1", findSentence(document, 2)!);
    forgetPosition("doc-1");

    expect(localStorage.getItem(KEY)).toBeNull();
  });
});
