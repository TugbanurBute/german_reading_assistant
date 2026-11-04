// Mirrors the backend DTOs in com.germanreader.dto.

export type TargetLanguage = "TR" | "EN";
export type TranslationType = "WORD" | "SENTENCE";

export type PartOfSpeech =
  | "NOUN"
  | "VERB"
  | "ADJECTIVE"
  | "ADVERB"
  | "PRONOUN"
  | "PREPOSITION"
  | "CONJUNCTION"
  | "ARTICLE"
  | "NUMERAL"
  | "PARTICLE"
  | "INTERJECTION"
  | "OTHER";

export type Article = "der" | "die" | "das";
export type GrammaticalCase = "NOMINATIVE" | "ACCUSATIVE" | "DATIVE" | "GENITIVE";
export type GrammaticalNumber = "SINGULAR" | "PLURAL";
export type Tense = "PRESENT" | "PRETERITE" | "PERFECT" | "PLUPERFECT" | "FUTURE_I" | "FUTURE_II";

export interface NounGrammar {
  kind: "NOUN";
  article: Article | null;
  plural: string | null;
  grammaticalCase: GrammaticalCase | null;
  number: GrammaticalNumber | null;
}

export interface VerbGrammar {
  kind: "VERB";
  tense: Tense | null;
  person: string | null;
  pastParticiple: string | null;
  auxiliary: "haben" | "sein" | null;
  separablePrefix: string | null;
}

export interface OtherGrammar {
  kind: "OTHER";
  details: string | null;
}

export type Grammar = NounGrammar | VerbGrammar | OtherGrammar;

export interface TranslationRequest {
  selectedText: string;
  context: string | null;
  sourceLanguage: "DE";
  targetLanguage: TargetLanguage;
  translationType: TranslationType;
}

export interface TranslationResponse {
  originalText: string;
  translation: string;
  lemma: string | null;
  partOfSpeech: PartOfSpeech | null;
  grammar: Grammar | null;
  sentenceTranslation: string | null;
  sourceLanguage: "DE";
  targetLanguage: TargetLanguage;
  translationType: TranslationType;
}
