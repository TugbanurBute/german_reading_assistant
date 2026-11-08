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

/** Colours of the der/die/das badge (translation card and My words). */
export const ARTICLE_STYLES = {
  der: "bg-der-bg text-der",
  die: "bg-die-bg text-die",
  das: "bg-das-bg text-das",
} as const;

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

/** @param retryAfterSeconds for TRANSLATION_RATE_LIMITED: how long until translating works again */
export function errorCopy(code: ErrorCode, retryAfterSeconds?: number): ErrorCopy {
  switch (code) {
    case "TRANSLATION_RATE_LIMITED":
      return {
        title: "Translation limit reached",
        body: retryAfterSeconds
          ? `You've translated a lot in a short time. You can continue in ${retryAfterSeconds} ${retryAfterSeconds === 1 ? "second" : "seconds"}.`
          : "You've translated a lot in a short time. Please wait a moment.",
        canRetry: true,
      };
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
    // Settings problems: "Try again" would only fail the same way, so there is no retry button.
    case "PROVIDER_MODEL_UNAVAILABLE":
      return {
        title: "Translation model not available",
        body: "The configured Gemini model may have been retired. Set GEMINI_MODEL to a current model and restart the backend.",
        canRetry: false,
      };
    case "PROVIDER_REJECTED":
      return {
        title: "Translation service refused the request",
        body: "Check GEMINI_API_KEY and the other Gemini settings, then restart the backend.",
        canRetry: false,
      };
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
    case "DOCUMENT_NOT_FOUND":
    case "INVALID_UPLOAD":
    case "UPLOAD_TOO_LARGE": // library and vocabulary codes; the translation endpoint never returns them
    case "VOCABULARY_NOT_FOUND":
    case "UNAUTHENTICATED": // the app returns to the login screen when this happens
    case "INVALID_CREDENTIALS":
    case "EMAIL_TAKEN":
    case "FORBIDDEN":
    case "INTERNAL_ERROR":
      return { title: "Something went wrong", body: "Try again in a moment.", canRetry: true };
  }
}

/** One-line messages for the reading library (saving, uploading, opening, deleting). */
export function libraryErrorMessage(code: ErrorCode): string {
  switch (code) {
    case "NETWORK_ERROR":
      return "Can't reach the server. Check that the backend is running and that CORS_ALLOWED_ORIGINS includes this page's address.";
    case "DOCUMENT_NOT_FOUND":
      return "This text no longer exists. It may have been deleted.";
    case "INVALID_UPLOAD":
      return "Only non-empty .txt files saved as UTF-8 can be uploaded.";
    case "UPLOAD_TOO_LARGE":
      return "The file is too large. The limit is 512 KB and 100,000 characters.";
    case "VALIDATION_ERROR":
    case "MALFORMED_REQUEST":
    case "REQUEST_ERROR":
      return "The title and text can't be empty, and the text can have at most 100,000 characters.";
    default:
      return "Something went wrong. Try again in a moment.";
  }
}

/** One-line messages for My words (loading the list, deleting). */
export function vocabularyErrorMessage(code: ErrorCode): string {
  return code === "NETWORK_ERROR" ? libraryErrorMessage(code) : "Something went wrong. Try again in a moment.";
}
