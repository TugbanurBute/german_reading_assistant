// Splits German text into paragraphs, sentences and words with Intl.Segmenter,
// which knows German sentence and word boundaries (umlauts, ß, punctuation).

export const MAX_WORD_LENGTH = 100; // TranslationRequest.MAX_WORD_LENGTH on the backend
export const MAX_SENTENCE_LENGTH = 1000; // TranslationRequest.MAX_SENTENCE_LENGTH

export interface Token {
  text: string;
  /** Index among all words of the document, or null for spaces and punctuation. */
  wordIndex: number | null;
  /** Offset of the token in its sentence's raw text. */
  start: number;
}

export interface Sentence {
  id: number;
  /** The sentence as written, including trailing spaces. */
  raw: string;
  /** Trimmed sentence: what is sent to the backend. */
  text: string;
  tokens: Token[];
}

export interface Paragraph {
  id: number;
  sentences: Sentence[];
}

export interface ReadingDocument {
  paragraphs: Paragraph[];
  wordCount: number;
}

const sentenceSegmenter = new Intl.Segmenter("de", { granularity: "sentence" });
const wordSegmenter = new Intl.Segmenter("de", { granularity: "word" });

export function segmentText(input: string): ReadingDocument {
  const paragraphs: Paragraph[] = [];
  let sentenceId = 0;
  let wordIndex = 0;

  // A blank line starts a new paragraph; single line breaks are treated as spaces.
  for (const block of input.split(/\n\s*\n/)) {
    const paragraphText = block.replace(/\s+/g, " ").trim();
    if (!paragraphText) {
      continue;
    }

    const sentences: Sentence[] = [];
    for (const raw of splitSentences(paragraphText)) {
      const tokens: Token[] = [];
      for (const { text, start, isWord } of splitWords(raw)) {
        const interactive = isWord && text.length <= MAX_WORD_LENGTH;
        tokens.push({ text, wordIndex: interactive ? wordIndex++ : null, start });
      }
      sentences.push({ id: sentenceId++, raw, text: raw.trim(), tokens });
    }
    paragraphs.push({ id: paragraphs.length, sentences });
  }

  return { paragraphs, wordCount: wordIndex };
}

// Segmenter treats the dot in "z. B." or "am 3. Mai" as a sentence end; these endings continue the sentence.
const ABBREVIATION_END = /(?:^|[\s(])(?:[a-zA-Z]|\d{1,2}|bzw|ca|Dr|etc|evtl|ggf|inkl|Nr|Prof|usw|vgl)\.\s*$/;

function splitSentences(paragraph: string): string[] {
  const sentences: string[] = [];
  for (const { segment } of sentenceSegmenter.segment(paragraph)) {
    const previous = sentences.length - 1;
    if (previous >= 0 && ABBREVIATION_END.test(sentences[previous]!)) {
      sentences[previous] += segment;
    } else {
      sentences.push(segment);
    }
  }
  return sentences;
}

interface Piece {
  text: string;
  start: number;
  isWord: boolean;
}

/** Word segments, with hyphenated compounds such as "E-Mail" kept as one word. */
function splitWords(sentence: string): Piece[] {
  const pieces: Piece[] = [];
  for (const { segment, index, isWordLike } of wordSegmenter.segment(sentence)) {
    const hyphen = pieces.at(-1);
    const word = pieces.at(-2);
    if (isWordLike && hyphen?.text === "-" && word?.isWord) {
      pieces.pop();
      word.text += "-" + segment;
    } else {
      pieces.push({ text: segment, start: index, isWord: isWordLike === true });
    }
  }
  return pieces;
}

/**
 * The sentence to send as context for a word. Sentences longer than the backend allows are cut
 * to a window around the word, at spaces, so the word is always inside the context.
 */
export function contextFor(sentence: Sentence, token: Token): string {
  if (sentence.text.length <= MAX_SENTENCE_LENGTH) {
    return sentence.text;
  }
  const half = Math.floor((MAX_SENTENCE_LENGTH - token.text.length) / 2);
  let from = Math.max(0, token.start - half);
  let to = Math.min(sentence.raw.length, from + MAX_SENTENCE_LENGTH);
  from = Math.max(0, to - MAX_SENTENCE_LENGTH);

  // Drop the partial words at both edges.
  const firstSpace = sentence.raw.indexOf(" ", from);
  if (from > 0 && firstSpace !== -1 && firstSpace < token.start) {
    from = firstSpace + 1;
  }
  const lastSpace = sentence.raw.lastIndexOf(" ", to);
  if (to < sentence.raw.length && lastSpace >= token.start + token.text.length) {
    to = lastSpace;
  }
  return sentence.raw.slice(from, to).trim();
}
