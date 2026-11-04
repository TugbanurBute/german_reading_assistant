import { type KeyboardEvent, type PointerEvent, useEffect, useMemo, useRef, useState } from "react";
import type { TargetLanguage, TranslationRequest, TranslationType } from "../api/types.ts";
import { useMediaQuery } from "../hooks/useMediaQuery.ts";
import { type ReadingDocument, type Sentence, type Token, contextFor } from "../text/segment.ts";
import { TranslationPopup } from "./TranslationPopup.tsx";

export const HOVER_DELAY_MS = 500;
const CLOSE_GRACE_MS = 150;

interface Props {
  document: ReadingDocument;
  mode: TranslationType;
  language: TargetLanguage;
}

/** What the popup is showing: a word (WORD mode) or a whole sentence (SENTENCE mode). */
interface Selection {
  key: string;
  request: Omit<TranslationRequest, "targetLanguage">;
  anchor: HTMLElement;
  /** Opened by click, tap or keyboard: stays open until closed explicitly. Hover popups close on leave. */
  pinned: boolean;
}

interface WordRef {
  sentence: Sentence;
  token: Token;
}

const HIGHLIGHT = "bg-highlight shadow-[inset_0_-2px_0_var(--highlight-edge)]";

export function ReadingView({ document, mode, language }: Props) {
  const words = useMemo(() => indexWords(document), [document]);
  const [selection, setSelection] = useState<Selection | null>(null);
  const [focusIndex, setFocusIndex] = useState(0);
  const asSheet = useMediaQuery("(max-width: 639px)");

  const containerRef = useRef<HTMLDivElement>(null);
  const popupRef = useRef<HTMLDivElement | null>(null);
  const openTimer = useRef<ReturnType<typeof setTimeout>>(undefined);
  const closeTimer = useRef<ReturnType<typeof setTimeout>>(undefined);

  const close = () => setSelection(null);
  const cancelTimers = () => {
    clearTimeout(openTimer.current);
    clearTimeout(closeTimer.current);
  };
  useEffect(() => cancelTimers, []);

  function selectionFor(wordIndex: number, anchor: HTMLElement, pinned: boolean): Selection | null {
    const word = words.get(wordIndex);
    if (!word) return null;
    const { sentence, token } = word;
    return mode === "WORD"
      ? {
          key: `w${wordIndex}`,
          request: { selectedText: token.text, context: contextFor(sentence, token), sourceLanguage: "DE", translationType: "WORD" },
          anchor,
          pinned,
        }
      : {
          key: `s${sentence.id}`,
          request: { selectedText: sentence.text, context: null, sourceLanguage: "DE", translationType: "SENTENCE" },
          anchor: anchor.closest<HTMLElement>("[data-sentence]") ?? anchor,
          pinned,
        };
  }

  // --- Mouse: open after resting on a word (or sentence) for HOVER_DELAY_MS ---

  function handleHoverStart(event: PointerEvent<HTMLElement>, wordIndex: number) {
    if (event.pointerType !== "mouse") return;
    clearTimeout(closeTimer.current);
    clearTimeout(openTimer.current);
    if (selection?.pinned) return;
    const next = selectionFor(wordIndex, event.currentTarget, false);
    if (!next || next.key === selection?.key) return;
    openTimer.current = setTimeout(() => setSelection(next), HOVER_DELAY_MS);
  }

  function handleHoverEnd(event: PointerEvent) {
    if (event.pointerType !== "mouse") return;
    clearTimeout(openTimer.current);
    // A short grace period lets the pointer travel from the word into the popup.
    closeTimer.current = setTimeout(() => setSelection((current) => (current?.pinned ? current : null)), CLOSE_GRACE_MS);
  }

  // --- Click, tap and Enter/Space: open immediately and keep open; the same target again closes ---

  function handleActivate(element: HTMLElement, wordIndex: number) {
    cancelTimers();
    setFocusIndex(wordIndex);
    const next = selectionFor(wordIndex, element, true);
    setSelection((current) => (current?.key === next?.key && current?.pinned ? null : next));
  }

  // --- Keyboard: one tab stop for the whole text, arrow keys move between words ---

  function handleKeyDown(event: KeyboardEvent) {
    const moves: Record<string, number> = {
      ArrowRight: focusIndex + 1,
      ArrowDown: focusIndex + 1,
      ArrowLeft: focusIndex - 1,
      ArrowUp: focusIndex - 1,
      Home: 0,
      End: words.size - 1,
    };
    const target = moves[event.key];
    if (target === undefined) return;
    event.preventDefault();
    const index = Math.min(Math.max(target, 0), words.size - 1);
    setFocusIndex(index);
    containerRef.current?.querySelector<HTMLElement>(`[data-word="${index}"]`)?.focus();
  }

  // Escape and clicks outside close the popup.
  useEffect(() => {
    if (!selection) return;
    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key !== "Escape") return;
      const focusWasInPopup = popupRef.current?.contains(window.document.activeElement) ?? false;
      close();
      if (focusWasInPopup) {
        containerRef.current?.querySelector<HTMLElement>(`[data-word="${focusIndex}"]`)?.focus();
      }
    };
    const onPointerDown = (event: globalThis.PointerEvent) => {
      // Words (and sentences in sentence mode) handle their own clicks, including closing on a second click.
      // Controls marked data-keep-popup (the language switch) leave the popup open to compare translations.
      const target = event.target as Element;
      const ownTarget = target.closest?.(mode === "SENTENCE" ? "[data-sentence]" : "[data-word]");
      if (popupRef.current?.contains(target) || ownTarget || target.closest?.("[data-keep-popup]")) return;
      close();
    };
    window.document.addEventListener("keydown", onKeyDown);
    window.document.addEventListener("pointerdown", onPointerDown);
    return () => {
      window.document.removeEventListener("keydown", onKeyDown);
      window.document.removeEventListener("pointerdown", onPointerDown);
    };
  }, [selection, focusIndex, mode]);

  const sentenceMode = mode === "SENTENCE";

  return (
    <>
      <div
        ref={containerRef}
        lang="de"
        aria-label="German text. Use the arrow keys to move between words and Enter to translate."
        className="font-serif text-[19px] leading-[1.75] [font-optical-sizing:auto] sm:text-xl"
        onKeyDown={handleKeyDown}
      >
        {document.paragraphs.map((paragraph) => (
          <p key={paragraph.id} className="mb-[1.1em] last:mb-0">
            {paragraph.sentences.map((sentence) => {
              const firstWord = sentence.tokens.find((token) => token.wordIndex !== null)?.wordIndex ?? null;
              // In sentence mode the whole sentence, including spaces and punctuation, reacts.
              const sentenceTarget = sentenceMode && firstWord !== null;
              return (
                <span
                  key={sentence.id}
                  data-sentence={sentence.id}
                  className={`rounded-[3px] [box-decoration-break:clone] ${sentenceTarget ? "cursor-pointer" : ""} ${
                    sentenceMode && selection?.key === `s${sentence.id}` ? HIGHLIGHT : ""
                  }`}
                  onPointerEnter={sentenceTarget ? (event) => handleHoverStart(event, firstWord) : undefined}
                  onPointerLeave={sentenceTarget ? handleHoverEnd : undefined}
                  onClick={
                    sentenceTarget
                      ? (event) => {
                          const word = (event.target as Element).closest<HTMLElement>("[data-word]");
                          handleActivate(event.currentTarget, word ? Number(word.dataset.word) : firstWord);
                        }
                      : undefined
                  }
                >
                  {sentence.tokens.map((token) =>
                    token.wordIndex === null ? (
                      token.text
                    ) : (
                      <WordButton
                        key={token.start}
                        token={token}
                        wordIndex={token.wordIndex}
                        active={!sentenceMode && selection?.key === `w${token.wordIndex}`}
                        tabbable={token.wordIndex === focusIndex}
                        interactive={!sentenceMode}
                        onHoverStart={handleHoverStart}
                        onHoverEnd={handleHoverEnd}
                        onActivate={handleActivate}
                        onFocus={setFocusIndex}
                      />
                    ),
                  )}
                </span>
              );
            })}
          </p>
        ))}
      </div>

      {selection && (
        <TranslationPopup
          anchor={selection.anchor}
          request={{ ...selection.request, targetLanguage: language }}
          asSheet={asSheet}
          popupRef={popupRef}
          onClose={close}
          onPointerEnter={(event) => {
            if (event.pointerType === "mouse") clearTimeout(closeTimer.current);
          }}
          onPointerLeave={handleHoverEnd}
        />
      )}
    </>
  );
}

interface WordButtonProps {
  token: Token;
  wordIndex: number;
  active: boolean;
  tabbable: boolean;
  /** False in sentence mode: the surrounding sentence handles hover and clicks (Enter still bubbles to it). */
  interactive: boolean;
  onHoverStart: (event: PointerEvent<HTMLElement>, wordIndex: number) => void;
  onHoverEnd: (event: PointerEvent) => void;
  onActivate: (element: HTMLElement, wordIndex: number) => void;
  onFocus: (wordIndex: number) => void;
}

function WordButton(props: WordButtonProps) {
  const { token, wordIndex, active, tabbable, interactive, onHoverStart, onHoverEnd, onActivate, onFocus } = props;
  return (
    <button
      type="button"
      data-word={wordIndex}
      tabIndex={tabbable ? 0 : -1}
      className={`inline cursor-pointer rounded-[3px] px-px transition-colors ${interactive ? "hover:bg-control" : ""} ${
        active ? HIGHLIGHT : ""
      }`}
      onPointerEnter={interactive ? (event) => onHoverStart(event, wordIndex) : undefined}
      onPointerLeave={interactive ? onHoverEnd : undefined}
      onClick={interactive ? (event) => onActivate(event.currentTarget, wordIndex) : undefined}
      onFocus={() => onFocus(wordIndex)}
    >
      {token.text}
    </button>
  );
}

function indexWords(document: ReadingDocument): Map<number, WordRef> {
  const words = new Map<number, WordRef>();
  for (const paragraph of document.paragraphs) {
    for (const sentence of paragraph.sentences) {
      for (const token of sentence.tokens) {
        if (token.wordIndex !== null) {
          words.set(token.wordIndex, { sentence, token });
        }
      }
    }
  }
  return words;
}
