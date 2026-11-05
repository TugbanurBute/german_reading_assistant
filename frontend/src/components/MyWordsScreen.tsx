import { useEffect, useState } from "react";
import { ApiError } from "../api/client.ts";
import type { VocabularyEntry } from "../api/vocabulary.ts";
import type { VocabularyStore } from "../hooks/useVocabulary.ts";
import { ARTICLE_STYLES, partOfSpeechLabel, vocabularyErrorMessage } from "./labels.ts";

interface Props {
  vocabulary: VocabularyStore;
  onBack: () => void;
}

interface Notice {
  kind: "success" | "error";
  text: string;
}

const secondaryButton = "rounded-lg border border-line px-3.5 py-2 text-sm hover:bg-control";

export function MyWordsScreen({ vocabulary, onBack }: Props) {
  const { list, load, reload, remove } = vocabulary;
  const [notice, setNotice] = useState<Notice | null>(null);
  const [confirmingId, setConfirmingId] = useState<string | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);

  useEffect(load, [load]);

  async function handleDelete(entry: VocabularyEntry) {
    setConfirmingId(null);
    setDeletingId(entry.id);
    setNotice(null);
    try {
      await remove(entry.id);
      setNotice({ kind: "success", text: `Deleted “${entry.lemma}”.` });
    } catch (error) {
      setNotice({ kind: "error", text: vocabularyErrorMessage(error instanceof ApiError ? error.code : "INTERNAL_ERROR") });
    } finally {
      setDeletingId(null);
    }
  }

  return (
    <div>
      <button type="button" onClick={onBack} className="-ml-2 mb-3 rounded-lg px-2 py-1 text-[13px] text-muted hover:bg-control hover:text-ink">
        ← Library
      </button>
      <h1 className="font-serif text-xl font-medium">My words</h1>
      <p className="mt-1 text-[13px] text-muted">Words you saved while reading, newest first.</p>

      {/* Always rendered, so screen readers announce messages that appear later. */}
      <div role="status" className="empty:hidden mt-4 text-[13px]">
        {notice && <p className={notice.kind === "error" ? "text-die" : "text-accent"}>{notice.text}</p>}
      </div>

      <div className="mt-5">
        {(list.status === "idle" || list.status === "loading") && (
          <p className="py-8 text-center text-sm text-muted">Loading your words…</p>
        )}

        {list.status === "error" && (
          <div className="rounded-lg border border-line px-5 py-8 text-center">
            <p className="font-serif text-lg">Couldn't load your words</p>
            <p className="mt-1 text-[13px] text-muted">{vocabularyErrorMessage(list.code)}</p>
            <button type="button" onClick={reload} className={`${secondaryButton} mt-4`}>
              Try again
            </button>
          </div>
        )}

        {list.status === "ready" && list.entries.length === 0 && (
          <div className="rounded-lg border border-dashed border-line px-5 py-10 text-center">
            <p className="font-serif text-lg">No saved words yet</p>
            <p className="mt-1 text-[13px] text-muted">Open a text, tap a word and choose “Save to My words”.</p>
          </div>
        )}

        {list.status === "ready" && list.entries.length > 0 && (
          <ul aria-label="Saved words" className="border-t border-line">
            {list.entries.map((entry) => (
              <WordRow
                key={entry.id}
                entry={entry}
                deleting={deletingId === entry.id}
                disabled={deletingId !== null}
                confirming={confirmingId === entry.id}
                onAskDelete={() => setConfirmingId(entry.id)}
                onCancelDelete={() => setConfirmingId(null)}
                onDelete={() => handleDelete(entry)}
              />
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

interface RowProps {
  entry: VocabularyEntry;
  deleting: boolean;
  disabled: boolean;
  confirming: boolean;
  onAskDelete: () => void;
  onCancelDelete: () => void;
  onDelete: () => void;
}

function WordRow({ entry, deleting, disabled, confirming, onAskDelete, onCancelDelete, onDelete }: RowProps) {
  return (
    <li className="flex items-start gap-2 border-b border-line py-3">
      <div className={`min-w-0 flex-1 px-2 ${deleting ? "opacity-50" : ""}`}>
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
          {entry.article && (
            <span className={`rounded-full px-2 py-0.5 text-[11px] font-medium ${ARTICLE_STYLES[entry.article]}`}>
              {entry.article}
            </span>
          )}
          <span lang="de" className="font-serif text-lg font-medium">
            {entry.lemma}
          </span>
          {entry.partOfSpeech && <span className="text-[11px] tracking-wide text-muted">{partOfSpeechLabel(entry.partOfSpeech)}</span>}
          <span className="rounded border border-line px-1 text-[10px] font-medium tracking-wide text-muted" title="Translated into">
            {entry.targetLanguage}
          </span>
        </div>
        <p className="mt-0.5 font-serif text-[17px] text-accent">{entry.translation}</p>
        {entry.sourceSentence && (
          <p lang="de" className="mt-1.5 font-serif text-sm leading-normal text-muted italic">
            {entry.sourceSentence}
          </p>
        )}
        {entry.sourceDocumentTitle && <p className="mt-1 text-xs text-muted">from “{entry.sourceDocumentTitle}”</p>}
      </div>
      {confirming ? (
        <div role="group" aria-label={`Delete “${entry.lemma}” (${entry.translation})?`} className="flex shrink-0 items-center gap-1.5 text-sm">
          <span className="hidden text-muted sm:inline">Delete?</span>
          {/* Focus moves to the safe choice, so Enter right after the first click doesn't delete. */}
          <button type="button" autoFocus onClick={onCancelDelete} className="rounded-lg px-2.5 py-1.5 hover:bg-control">
            Cancel
          </button>
          <button type="button" onClick={onDelete} className="rounded-lg bg-die px-2.5 py-1.5 font-medium text-sheet hover:opacity-90">
            Delete
          </button>
        </div>
      ) : (
        <button
          type="button"
          onClick={onAskDelete}
          disabled={disabled}
          aria-label={`Delete “${entry.lemma}” (${entry.translation})`}
          className="shrink-0 rounded-lg p-2 text-muted hover:bg-control hover:text-die disabled:opacity-50"
        >
          <TrashIcon />
        </button>
      )}
    </li>
  );
}

function TrashIcon() {
  return (
    <svg width={18} height={18} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M4 7h16M10 11v6M14 11v6M5 7l1 12a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2l1-12M9 7V4h6v3" />
    </svg>
  );
}
