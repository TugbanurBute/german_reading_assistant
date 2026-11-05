import { type FormEvent, useId, useState } from "react";
import { ApiError } from "../api/client.ts";
import { MAX_CONTENT_LENGTH, MAX_TITLE_LENGTH, type SavedDocument, createDocument, titleFromText } from "../api/documents.ts";
import { SAMPLE_TEXT } from "../text/sampleText.ts";
import { libraryErrorMessage } from "./labels.ts";

interface Props {
  title: string;
  text: string;
  onTitleChange: (title: string) => void;
  onTextChange: (text: string) => void;
  onSaved: (document: SavedDocument) => void;
  onReadUnsaved: () => void;
  onBack: () => void;
}

export function WriteScreen({ title, text, onTitleChange, onTextChange, onSaved, onReadUnsaved, onBack }: Props) {
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const titleId = useId();
  const fieldId = useId();
  const errorId = useId();

  function hasText() {
    if (text.trim()) return true;
    setError("Paste some German text first.");
    return false;
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (!hasText()) return;
    setSaving(true);
    setError(null);
    try {
      onSaved(await createDocument(title.trim() || titleFromText(text), text));
    } catch (failure) {
      setError(libraryErrorMessage(failure instanceof ApiError ? failure.code : "INTERNAL_ERROR"));
      setSaving(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} noValidate>
      <button type="button" onClick={onBack} className="-ml-2 mb-3 rounded-lg px-2 py-1 text-[13px] text-muted hover:bg-control hover:text-ink">
        ← Library
      </button>
      <label htmlFor={fieldId} className="block font-serif text-xl font-medium">
        What would you like to read?
      </label>
      <p className="mt-1 text-[13px] text-muted">Paste German text. You can then hover or tap any word.</p>

      <label htmlFor={titleId} className="mt-4 block text-[13px] text-muted">
        Title <span className="text-muted/80">(optional, otherwise the first line)</span>
      </label>
      <input
        id={titleId}
        value={title}
        maxLength={MAX_TITLE_LENGTH}
        onChange={(event) => onTitleChange(event.target.value)}
        className="mt-1 block w-full rounded-lg border border-line bg-page/40 px-4 py-2 font-serif text-lg focus:border-accent"
      />

      <textarea
        id={fieldId}
        lang="de"
        value={text}
        maxLength={MAX_CONTENT_LENGTH}
        rows={12}
        placeholder="Am Morgen sitzt meine Großmutter auf der Bank im Park."
        aria-invalid={error !== null}
        aria-describedby={error ? errorId : undefined}
        onChange={(event) => {
          onTextChange(event.target.value);
          setError(null);
        }}
        className="mt-4 block w-full resize-y rounded-lg border border-line bg-page/40 px-4 py-3 font-serif text-lg leading-relaxed placeholder:text-muted/70 focus:border-accent"
      />
      {error && (
        <p id={errorId} role="alert" className="mt-2 text-[13px] text-die">
          {error}
        </p>
      )}
      <div className="mt-4 flex flex-wrap items-center gap-3">
        <button
          type="button"
          onClick={() => {
            onTextChange(SAMPLE_TEXT);
            setError(null);
          }}
          className="rounded-lg border border-line px-3.5 py-2 text-sm hover:bg-control"
        >
          Load sample text
        </button>
        <button
          type="button"
          onClick={() => hasText() && onReadUnsaved()}
          className="ml-auto rounded-lg px-3 py-2 text-sm text-muted hover:bg-control hover:text-ink"
        >
          Read without saving
        </button>
        <button
          type="submit"
          disabled={saving}
          className="rounded-lg bg-accent px-4 py-2 text-sm font-medium text-sheet hover:opacity-90 disabled:opacity-60"
        >
          {saving ? "Saving…" : "Save and read"}
        </button>
      </div>
    </form>
  );
}
