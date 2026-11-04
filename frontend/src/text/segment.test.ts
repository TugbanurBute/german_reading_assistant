import { describe, expect, it } from "vitest";
import { MAX_SENTENCE_LENGTH, contextFor, segmentText } from "./segment.ts";

const words = (text: string) =>
  segmentText(text).paragraphs.flatMap((p) => p.sentences.flatMap((s) => s.tokens.filter((t) => t.wordIndex !== null)));

const sentences = (text: string) => segmentText(text).paragraphs.flatMap((p) => p.sentences.map((s) => s.text));

describe("segmentText", () => {
  it("splits paragraphs at blank lines and treats single line breaks as spaces", () => {
    const document = segmentText("Erster Satz.\nNoch im Absatz.\n\n  \n\nZweiter Absatz.");

    expect(document.paragraphs).toHaveLength(2);
    expect(document.paragraphs[0]!.sentences.map((s) => s.text)).toEqual(["Erster Satz.", "Noch im Absatz."]);
  });

  it("numbers words across the whole document and skips spaces and punctuation", () => {
    const tokens = words("Meine Großmutter, die Ärztin. Sie lacht!");

    expect(tokens.map((t) => t.text)).toEqual(["Meine", "Großmutter", "die", "Ärztin", "Sie", "lacht"]);
    expect(tokens.map((t) => t.wordIndex)).toEqual([0, 1, 2, 3, 4, 5]);
  });

  it("keeps the sentence trimmed for the backend", () => {
    expect(sentences("Danach bringt sie ihr Geld zur Bank. Die Kinder spielen.")).toEqual([
      "Danach bringt sie ihr Geld zur Bank.",
      "Die Kinder spielen.",
    ]);
  });

  it("does not end a sentence after common abbreviations and ordinals", () => {
    expect(sentences("Das ist z. B. gut. Wir kommen am 3. Mai. Dr. Weber ist da.")).toEqual([
      "Das ist z. B. gut.",
      "Wir kommen am 3. Mai.",
      "Dr. Weber ist da.",
    ]);
  });

  it("keeps hyphenated compounds together", () => {
    expect(words("Ich schreibe eine E-Mail an die Baden-Württemberger.").map((t) => t.text)).toContain("E-Mail");
    expect(words("Berlin - Hamburg").map((t) => t.text)).toEqual(["Berlin", "Hamburg"]);
  });
});

describe("contextFor", () => {
  it("returns the whole sentence when it fits", () => {
    const sentence = segmentText("Ich rufe dich morgen an.").paragraphs[0]!.sentences[0]!;
    const token = sentence.tokens.find((t) => t.text === "rufe")!;

    expect(contextFor(sentence, token)).toBe("Ich rufe dich morgen an.");
  });

  it("cuts long sentences to a window that still contains the word", () => {
    const filler = Array.from({ length: 300 }, (_, i) => `wort${i}`).join(" ");
    const sentence = segmentText(`${filler} Schloss ${filler}.`).paragraphs[0]!.sentences[0]!;
    const token = sentence.tokens.find((t) => t.text === "Schloss")!;

    const context = contextFor(sentence, token);

    expect(context.length).toBeLessThanOrEqual(MAX_SENTENCE_LENGTH);
    expect(context).toContain(" Schloss ");
    expect(context).toMatch(/^wort\d+ .* wort\d+$/); // no cut-off words at the edges
  });
});
