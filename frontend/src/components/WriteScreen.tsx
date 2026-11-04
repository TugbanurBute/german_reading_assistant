import { type FormEvent, useId, useState } from "react";
import { SAMPLE_TEXT } from "../text/sampleText.ts";

export const MAX_TEXT_LENGTH = 20_000;

interface Props {
  text: string;
  onTextChange: (text: string) => void;
  onRead: () => void;
}

export function WriteScreen({ text, onTextChange, onRead }: Props) {
  const [error, setError] = useState<string | null>(null);
  const fieldId = useId();
  const errorId = useId();

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (!text.trim()) {
      setError("Paste some German text first.");
      return;
    }
    onRead();
  }

  return (
    <form onSubmit={handleSubmit} noValidate>
      <label htmlFor={fieldId} className="block font-serif text-xl font-medium">
        What would you like to read?
      </label>
      <p className="mt-1 text-[13px] text-muted">Paste German text. You can then hover or tap any word.</p>
      <textarea
        id={fieldId}
        lang="de"
        value={text}
        maxLength={MAX_TEXT_LENGTH}
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
        <p id={errorId} className="mt-2 text-[13px] text-die">
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
        <button type="submit" className="ml-auto rounded-lg bg-accent px-4 py-2 text-sm font-medium text-sheet hover:opacity-90">
          Start reading
        </button>
      </div>
    </form>
  );
}
