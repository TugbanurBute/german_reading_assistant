import type { ErrorCode } from "../api/translations.ts";
import type { Grammar, GrammaticalCase, GrammaticalNumber, PartOfSpeech, Tense } from "../api/types.ts";

const CASES: Record<GrammaticalCase, string> = {
  NOMINATIVE: "Nominative",
  ACCUSATIVE: "Accusative",
  DATIVE: "Dative",
  GENITIVE: "Genitive",
};

const NUMBERS: Record<GrammaticalNumber, string> = { SINGULAR: "singular", PLURAL: "plural" };

const TENSES: Record<Tense, string> = {
  PRESENT: "Present",
  PRETERITE: "Simple past",
  PERFECT: "Perfect",
  PLUPERFECT: "Past perfect",
  FUTURE_I: "Future I",
  FUTURE_II: "Future II",
};

export function partOfSpeechLabel(partOfSpeech: PartOfSpeech): string {
  return partOfSpeech.charAt(0) + partOfSpeech.slice(1).toLowerCase();
}

/** Label/value rows for the grammar table. Unknown (null) values are left out, never shown as "unknown". */
export function grammarRows(grammar: Grammar | null): Array<[string, string]> {
  const rows: Array<[string, string | null]> = [];
  switch (grammar?.kind) {
    case "NOUN":
      rows.push(["Case", joinKnown(grammar.grammaticalCase && CASES[grammar.grammaticalCase],
          grammar.number && NUMBERS[grammar.number])]);
      rows.push(["Plural", grammar.plural]);
      break;
    case "VERB": {
      rows.push(["Form", joinKnown(grammar.tense && TENSES[grammar.tense], grammar.person)]);
      // Dictionaries cite the perfect in the 3rd person singular: "hat gespielt", "ist gefahren".
      const auxiliary = grammar.auxiliary && (grammar.auxiliary === "sein" ? "ist" : "hat");
      rows.push(["Perfect", grammar.pastParticiple && joinKnown(auxiliary, grammar.pastParticiple, " ")]);
      rows.push(["Separable prefix", grammar.separablePrefix]);
      break;
    }
    case "OTHER":
      rows.push(["Note", grammar.details]);
      break;
    default:
      break;
  }
  return rows.filter((row): row is [string, string] => Boolean(row[1]));
}

function joinKnown(first: string | null, second: string | null, separator = " · "): string | null {
  return [first, second].filter(Boolean).join(separator) || null;
}

export interface ErrorCopy {
  title: string;
  body: string;
  canRetry: boolean;
}

export function errorCopy(code: ErrorCode): ErrorCopy {
  switch (code) {
    case "MOCK_TRANSLATION_NOT_FOUND":
      return {
        title: "No sample translation for this",
        body: "Mock mode only knows the sample text. Switch the backend to Gemini to translate anything.",
        canRetry: false,
      };
    case "PROVIDER_TIMEOUT":
      return { title: "Translation took too long", body: "The service didn't answer in time.", canRetry: true };
    case "PROVIDER_RATE_LIMITED":
      return { title: "Too many requests right now", body: "Wait a moment, then try again.", canRetry: true };
    case "PROVIDER_UNAVAILABLE":
      return { title: "Translation service unavailable", body: "It may be a short outage. Try again soon.", canRetry: true };
    case "PROVIDER_INVALID_RESPONSE":
      return { title: "Couldn't read the translation", body: "The service sent an unusable answer.", canRetry: true };
    case "VALIDATION_ERROR":
    case "MALFORMED_REQUEST":
    case "REQUEST_ERROR":
      return {
        title: "This can't be translated",
        body: "The selection is too long or contains no German text.",
        canRetry: false,
      };
    case "NETWORK_ERROR":
      // The browser reports a refused CORS request exactly like a server that is down.
      return {
        title: "Can't reach the server",
        body: "Check that the backend is running and that CORS_ALLOWED_ORIGINS includes this page's address.",
        canRetry: true,
      };
    case "INTERNAL_ERROR":
      return { title: "Something went wrong", body: "Try again in a moment.", canRetry: true };
  }
}
