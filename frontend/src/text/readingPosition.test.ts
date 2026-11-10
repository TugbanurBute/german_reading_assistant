import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { findSentence, forgetAllPositions, forgetPosition, loadPosition, savePosition } from "./readingPosition.ts";
import { SAMPLE_TEXT } from "./sampleText.ts";
import { segmentText } from "./segment.ts";

const KEY = "lesehilfe.position.anna.doc-1";
const document = segmentText(SAMPLE_TEXT);

beforeEach(() => localStorage.clear());
afterEach(() => vi.restoreAllMocks());

describe("reading position", () => {
  it("saves a sentence anchor and loads it again", () => {
    savePosition("anna", "doc-1", findSentence(document, 4)!);

    expect(JSON.parse(localStorage.getItem(KEY)!)).toEqual({
      v: 1,
      sentence: 4,
      snippet: "Leider ist das Schloss an meiner",
    });
    expect(loadPosition("anna", "doc-1", document)).toBe(4);
  });

  it("returns null when nothing is saved", () => {
    expect(loadPosition("anna", "doc-1", document)).toBeNull();
  });

  it.each([
    ["not JSON", "{oops"],
    ["another shape", JSON.stringify({ paragraph: 2, offset: 40 })],
    ["a sentence that doesn't exist", JSON.stringify({ v: 1, sentence: 999, snippet: "x" })],
    ["a sentence whose text changed", JSON.stringify({ v: 1, sentence: 4, snippet: "Ganz anderer Text" })],
    ["a newer format", JSON.stringify({ v: 2, sentence: 4, snippet: "Leider ist das Schloss an meiner" })],
  ])("ignores and removes %s", (_, stored) => {
    localStorage.setItem(KEY, stored);

    expect(loadPosition("anna", "doc-1", document)).toBeNull();
    expect(localStorage.getItem(KEY)).toBeNull();
  });

  it("works without storage", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });

    expect(() => savePosition("anna", "doc-1", findSentence(document, 0)!)).not.toThrow();
    expect(loadPosition("anna", "doc-1", document)).toBeNull();
  });

  it("forgets a document's position", () => {
    savePosition("anna", "doc-1", findSentence(document, 2)!);
    forgetPosition("anna", "doc-1");

    expect(localStorage.getItem(KEY)).toBeNull();
  });

  it("keeps each user's position separate, also for the same shared text", () => {
    savePosition("anna", "builtin-1", findSentence(document, 4)!);
    savePosition("ben", "builtin-1", findSentence(document, 1)!);

    expect(loadPosition("anna", "builtin-1", document)).toBe(4);
    expect(loadPosition("ben", "builtin-1", document)).toBe(1);
    expect(loadPosition("carla", "builtin-1", document)).toBeNull();
  });

  it("forgets all positions of one user only", () => {
    savePosition("anna", "doc-1", findSentence(document, 2)!);
    savePosition("anna", "builtin-1", findSentence(document, 3)!);
    savePosition("ben", "builtin-1", findSentence(document, 1)!);

    forgetAllPositions("anna");

    expect(Object.keys(localStorage)).toEqual(["lesehilfe.position.ben.builtin-1"]);
  });
});
