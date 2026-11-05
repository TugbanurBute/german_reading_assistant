import { useEffect, useState } from "react";
import type { TranslationResponse } from "../api/types.ts";
import { meaningKey, toSaveInput } from "../api/vocabulary.ts";
import { useVocabulary } from "../hooks/useVocabulary.ts";

interface Props {
  /** A successful WORD translation. */
  data: TranslationResponse;
  sentence: string | null;
  documentId: string | null;
}

/** "Save to My words" under a word translation; "Saved" once this meaning is in the list. */
export function SaveWordButton({ data, sentence, documentId }: Props) {
  const vocabulary = useVocabulary();
  const input = toSaveInput(data, sentence, documentId);
  const key = meaningKey(input);
  // Remembers a save even while the shared list is not loaded (e.g. after a failed list request).
  const [savedKey, setSavedKey] = useState<string | null>(null);
  const [state, setState] = useState<"idle" | "saving" | "error">("idle");

  const load = vocabulary?.load;
  useEffect(() => load?.(), [load]);
  // Switching TR ⇄ EN shows another meaning: start over.
  useEffect(() => setState("idle"), [key]);

  if (!vocabulary) return null;
  const saved = savedKey === key || vocabulary.find(input) !== undefined;

  async function handleSave() {
    if (!vocabulary) return;
    setState("saving");
    try {
      await vocabulary.save(input);
      setSavedKey(key);
      setState("idle");
    } catch {
      setState("error");
    }
  }

  return (
    <div className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-1 border-t border-line pt-2.5 text-[13px]">
      {saved ? (
        <p className="flex items-center gap-1.5 text-accent">
          <CheckIcon />
          Saved to My words
        </p>
      ) : (
        <button
          type="button"
          onClick={handleSave}
          disabled={state === "saving"}
          className="flex items-center gap-1.5 rounded-lg border border-line px-3 py-1.5 hover:bg-control disabled:opacity-60"
        >
          <StarIcon />
          {state === "saving" ? "Saving…" : "Save to My words"}
        </button>
      )}
      <span role="status" className="text-die empty:hidden">
        {state === "error" && !saved ? "Couldn't save. Try again." : ""}
      </span>
    </div>
  );
}

const iconProps = {
  width: 15,
  height: 15,
  viewBox: "0 0 24 24",
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 2,
  strokeLinecap: "round",
  strokeLinejoin: "round",
  "aria-hidden": true,
} as const;

function StarIcon() {
  return (
    <svg {...iconProps}>
      <path d="m12 3 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2L12 17.3 6.4 20.2l1.1-6.2L3 9.6l6.2-.9Z" />
    </svg>
  );
}

function CheckIcon() {
  return (
    <svg {...iconProps}>
      <path d="M5 12.5 10 17l9-10" />
    </svg>
  );
}
