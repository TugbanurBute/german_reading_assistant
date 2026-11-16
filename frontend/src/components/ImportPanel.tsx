import { type ChangeEvent, type FormEvent, useEffect, useId, useRef, useState } from "react";
import { ApiError, type ErrorCode } from "../api/client.ts";
import { MAX_PDF_UPLOAD_BYTES, MAX_TXT_UPLOAD_BYTES, type SavedDocument, uploadDocument } from "../api/documents.ts";
import { libraryErrorMessage } from "./labels.ts";

interface Props {
  onImported: (document: SavedDocument) => void;
  onCancel: () => void;
}

export function importProblem(file: File): ErrorCode | null {
  const name = file.name.toLowerCase();
  const limit = name.endsWith(".txt") ? MAX_TXT_UPLOAD_BYTES : name.endsWith(".pdf") ? MAX_PDF_UPLOAD_BYTES : null;
  if (limit === null || file.size === 0) return "INVALID_UPLOAD";
  return file.size > limit ? "UPLOAD_TOO_LARGE" : null;
}

function fileSize(bytes: number): string {
  return bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

const secondaryButton = "rounded-lg border border-line px-3.5 py-2 text-sm hover:bg-control";

export function ImportPanel({ onImported, onCancel }: Props) {
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [importing, setImporting] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const headingId = useId();
  const hintId = useId();
  const errorId = useId();

  // Keyboard users land on the file chooser.
  useEffect(() => input.current?.focus(), []);

  function handleChoose(event: ChangeEvent<HTMLInputElement>) {
    const chosen = event.target.files?.[0];
    event.target.value = ""; // the same file can be chosen again
    if (!chosen) return; // dialog closed: keep the current choice
    setFile(chosen);
    const problem = importProblem(chosen);
    setError(problem && libraryErrorMessage(problem));
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (!file || importProblem(file)) return;
    setImporting(true);
    setError(null);
    try {
      onImported(await uploadDocument(file));
    } catch (failure) {
      setError(libraryErrorMessage(failure instanceof ApiError ? failure.code : "INTERNAL_ERROR"));
      setImporting(false);
    }
  }

  return (
    <form
      onSubmit={handleSubmit}
      onKeyDown={(event) => event.key === "Escape" && !importing && onCancel()}
      aria-labelledby={headingId}
      aria-busy={importing}
      className="mt-4 rounded-lg border border-line px-4 py-4 sm:px-5"
    >
      <h2 id={headingId} className="font-serif text-lg font-medium">
        Import a PDF or TXT file
      </h2>
      <ul id={hintId} className="mt-1 list-inside list-disc text-[13px] text-muted">
        <li>.txt saved as UTF-8, up to 512 KB</li>
        <li>.pdf with selectable text, up to 5 MB and 300 pages (scanned PDFs can't be read)</li>
        <li>At most 100,000 characters of text. Only the text is kept, not the file.</li>
      </ul>

      <div className="mt-4 flex flex-wrap items-center gap-x-3 gap-y-2">
        <label
          className={`${secondaryButton} cursor-pointer has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-accent ${
            importing ? "pointer-events-none opacity-60" : ""
          }`}
        >
          <input
            ref={input}
            type="file"
            accept=".txt,.pdf,text/plain,application/pdf"
            className="sr-only"
            disabled={importing}
            aria-describedby={error ? `${hintId} ${errorId}` : hintId}
            onChange={handleChoose}
          />
          {file ? "Choose another file" : "Choose file"}
        </label>
        {file ? (
          <p className="flex min-w-0 items-center gap-2 text-sm">
            <span className="break-all font-medium">{file.name}</span>
            <span className="shrink-0 text-muted">{fileSize(file.size)}</span>
            {!importing && (
              <button
                type="button"
                onClick={() => {
                  setFile(null);
                  setError(null);
                }}
                aria-label={`Remove ${file.name}`}
                className="shrink-0 rounded-lg px-1.5 py-0.5 text-[13px] text-muted hover:bg-control hover:text-ink"
              >
                Remove
              </button>
            )}
          </p>
        ) : (
          <p className="text-[13px] text-muted">No file selected</p>
        )}
      </div>

      {error && (
        <p id={errorId} role="alert" className="mt-3 text-[13px] text-die">
          {error}
        </p>
      )}

      <div className="mt-4 flex flex-wrap items-center justify-end gap-3">
        {importing && (
          <p role="status" className="mr-auto text-[13px] text-muted">
            Reading the file…
          </p>
        )}
        <button
          type="button"
          onClick={onCancel}
          disabled={importing}
          className="rounded-lg px-3 py-2 text-sm text-muted hover:bg-control hover:text-ink disabled:opacity-60"
        >
          Cancel
        </button>
        <button
          type="submit"
          disabled={!file || importing || importProblem(file) !== null}
          className="rounded-lg bg-accent px-4 py-2 text-sm font-medium text-sheet hover:opacity-90 disabled:opacity-60"
        >
          {importing ? "Importing…" : "Import"}
        </button>
      </div>
    </form>
  );
}
