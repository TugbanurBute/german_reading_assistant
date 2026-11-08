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

  it("explains the translation limit with the wait, and allows a retry", () => {
    expect(errorCopy("TRANSLATION_RATE_LIMITED", 42)).toEqual({
      title: "Translation limit reached",
      body: "You've translated a lot in a short time. You can continue in 42 seconds.",
      canRetry: true,
    });
    expect(errorCopy("TRANSLATION_RATE_LIMITED", 1).body).toContain("in 1 second.");
    expect(errorCopy("TRANSLATION_RATE_LIMITED").body).toBe("You've translated a lot in a short time. Please wait a moment.");
  });

  it("tells a retired model and a rejected key apart from an outage, without a retry", () => {
    expect(errorCopy("PROVIDER_UNAVAILABLE")).toMatchObject({ title: "Translation service unavailable", canRetry: true });
    expect(errorCopy("PROVIDER_MODEL_UNAVAILABLE")).toMatchObject({ title: "Translation model not available", canRetry: false });
    expect(errorCopy("PROVIDER_MODEL_UNAVAILABLE").body).toContain("GEMINI_MODEL");
    expect(errorCopy("PROVIDER_REJECTED")).toMatchObject({ canRetry: false });
    expect(errorCopy("PROVIDER_REJECTED").body).toContain("GEMINI_API_KEY");
  });
});
