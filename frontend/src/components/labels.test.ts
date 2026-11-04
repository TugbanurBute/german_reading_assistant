import { describe, expect, it } from "vitest";
import { errorCopy, grammarRows, partOfSpeechLabel } from "./labels.ts";

describe("grammarRows", () => {
  it("describes a noun", () => {
    expect(
      grammarRows({ kind: "NOUN", article: "die", plural: "Bänke", grammaticalCase: "DATIVE", number: "SINGULAR" }),
    ).toEqual([
      ["Case", "Dative · singular"],
      ["Plural", "Bänke"],
    ]);
  });

  it("describes a separable verb with its perfect form", () => {
    expect(
      grammarRows({
        kind: "VERB",
        tense: "PRESENT",
        person: "1st person singular",
        pastParticiple: "angerufen",
        auxiliary: "haben",
        separablePrefix: "an",
      }),
    ).toEqual([
      ["Form", "Present · 1st person singular"],
      ["Perfect", "hat angerufen"],
      ["Separable prefix", "an"],
    ]);
  });

  it("uses ist for verbs with sein", () => {
    const rows = grammarRows({
      kind: "VERB",
      tense: "PERFECT",
      person: null,
      pastParticiple: "gefahren",
      auxiliary: "sein",
      separablePrefix: null,
    });
    expect(rows).toEqual([
      ["Form", "Perfect"],
      ["Perfect", "ist gefahren"],
    ]);
  });

  it("leaves out unknown values instead of showing them", () => {
    expect(grammarRows({ kind: "NOUN", article: null, plural: null, grammaticalCase: null, number: null })).toEqual([]);
    expect(grammarRows({ kind: "OTHER", details: null })).toEqual([]);
    expect(grammarRows(null)).toEqual([]);
  });
});

describe("labels", () => {
  it("formats parts of speech", () => {
    expect(partOfSpeechLabel("NOUN")).toBe("Noun");
  });

  it("offers a retry only for temporary problems", () => {
    expect(errorCopy("PROVIDER_TIMEOUT").canRetry).toBe(true);
    expect(errorCopy("NETWORK_ERROR").canRetry).toBe(true);
    expect(errorCopy("MOCK_TRANSLATION_NOT_FOUND").canRetry).toBe(false);
    expect(errorCopy("VALIDATION_ERROR").canRetry).toBe(false);
  });
});
