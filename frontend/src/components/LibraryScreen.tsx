import { type ChangeEvent, type FormEvent, useEffect, useId, useState } from "react";
import { ApiError, type ErrorCode } from "../api/client.ts";
import {
  type DocumentSummary,
  MAX_TITLE_LENGTH,
  MAX_UPLOAD_BYTES,
  type SavedDocument,
  deleteDocument,
  getDocument,
  listDocuments,
  renameDocument,
  uploadDocument,
} from "../api/documents.ts";
import { forgetPosition } from "../text/readingPosition.ts";
import { libraryErrorMessage } from "./labels.ts";

interface Props {
  /** The logged-in account, shown with a Log out button. */
  account: { email: string; onLogout: () => void };
  onNew: () => void;
  onWords: () => void;
  onOpen: (document: SavedDocument) => void;
}

type ListState =
  | { status: "loading" }
  | { status: "error"; code: ErrorCode }
  | { status: "ready"; documents: DocumentSummary[] };

interface Busy {
  id: string;
  action: "opening" | "deleting" | "renaming";
}

interface Notice {
  kind: "success" | "error";
  text: string;
}

const codeOf = (error: unknown): ErrorCode => (error instanceof ApiError ? error.code : "INTERNAL_ERROR");

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" });

/** How often, and how many times, the list is refreshed while texts are being analysed. */
export const PROCESSING_POLL_MS = 3000;
const MAX_PROCESSING_POLLS = 20;

const numberFormat = new Intl.NumberFormat("en");

const isAnalysing = (summary: DocumentSummary) =>
  summary.processingStatus === "PENDING" || summary.processingStatus === "PROCESSING";

/** " · 1,234 words · 5 paragraphs" once analysed, " · Analysing…" before; nothing if the analysis failed. */
function processingText(summary: DocumentSummary): string {
  if (isAnalysing(summary)) return " · Analysing…";
  if (summary.processingStatus !== "COMPLETED" || summary.wordCount === null) return "";
  const words = `${numberFormat.format(summary.wordCount)} ${summary.wordCount === 1 ? "word" : "words"}`;
  const paragraphs = `${summary.paragraphCount} ${summary.paragraphCount === 1 ? "paragraph" : "paragraphs"}`;
  return ` · ${words} · ${paragraphs}`;
}

const secondaryButton ="rounded-lg border border-line px-3.5 py-2 text-sm hover:bg-control";

export function LibraryScreen({ account, onNew, onWords, onOpen }: Props) {
  const [list, setList] = useState<ListState>({ status: "loading" });
  const [attempt, setAttempt] = useState(0);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [uploading, setUploading] = useState(false);
  // Only one row is busy, asking for confirmation or being renamed at a time.
  const [busy, setBusy] = useState<Busy | null>(null);
  const [confirmingId, setConfirmingId] = useState<string | null>(null);
  const [renamingId, setRenamingId] = useState<string | null>(null);

  useEffect(() => {
    let current = true;
    setList({ status: "loading" });
    listDocuments().then(
      (documents) => current && setList({ status: "ready", documents }),
      (error: unknown) => current && setList({ status: "error", code: codeOf(error) }),
    );
    return () => {
      current = false;
    };
  }, [attempt]);

  // While texts are still being analysed, refresh the list quietly now and then to show their counts.
  const analysing = list.status === "ready" && list.documents.some((d) => isAnalysing(d));
  const [polls, setPolls] = useState(0);
  useEffect(() => {
    if (!analysing || polls >= MAX_PROCESSING_POLLS) return;
    const timer = setTimeout(() => {
      listDocuments().then(
        (documents) => setList((state) => (state.status === "ready" ? { status: "ready", documents } : state)),
        () => undefined, // a failed refresh just keeps the current list
      );
      setPolls((n) => n + 1);
    }, PROCESSING_POLL_MS);
    return () => clearTimeout(timer);
  }, [analysing, polls]);

  const removeFromList = (id: string) =>
    setList((state) => (state.status === "ready" ? { ...state, documents: state.documents.filter((d) => d.id !== id) } : state));

  async function handleUpload(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = ""; // choosing the same file again should upload again
    if (!file) return;

    // Quick checks for a friendly message without a round trip; the server checks the content itself.
    if (!file.name.toLowerCase().endsWith(".txt") || file.size === 0) {
      setNotice({ kind: "error", text: libraryErrorMessage("INVALID_UPLOAD") });
      return;
    }
    if (file.size > MAX_UPLOAD_BYTES) {
      setNotice({ kind: "error", text: libraryErrorMessage("UPLOAD_TOO_LARGE") });
      return;
    }

    setUploading(true);
    setNotice(null);
    try {
      const saved = await uploadDocument(file);
      setList((state) => (state.status === "ready" ? { ...state, documents: [saved, ...state.documents] } : state));
      setNotice({ kind: "success", text: `Added “${saved.title}” to your library.` });
    } catch (error) {
      setNotice({ kind: "error", text: libraryErrorMessage(codeOf(error)) });
    } finally {
      setUploading(false);
    }
  }

  async function handleOpen(summary: DocumentSummary) {
    setBusy({ id: summary.id, action: "opening" });
    setNotice(null);
    try {
      onOpen(await getDocument(summary.id));
    } catch (error) {
      const code = codeOf(error);
      if (code === "DOCUMENT_NOT_FOUND") removeFromList(summary.id);
      setNotice({ kind: "error", text: libraryErrorMessage(code) });
      setBusy(null);
    }
  }

  async function handleRename(summary: DocumentSummary, title: string) {
    setBusy({ id: summary.id, action: "renaming" });
    setNotice(null);
    try {
      const renamed = await renameDocument(summary.id, title);
      setList((state) =>
        state.status === "ready"
          ? { ...state, documents: state.documents.map((d) => (d.id === renamed.id ? renamed : d)) }
          : state,
      );
      setRenamingId(null);
      setNotice({ kind: "success", text: `Renamed to “${renamed.title}”.` });
    } catch (error) {
      const code = codeOf(error);
      if (code === "DOCUMENT_NOT_FOUND") {
        removeFromList(summary.id);
        setRenamingId(null);
      }
      // Other errors keep the form open with the typed title, so it can be saved again.
      setNotice({ kind: "error", text: libraryErrorMessage(code) });
    } finally {
      setBusy(null);
    }
  }

  async function handleDelete(summary: DocumentSummary) {
    setConfirmingId(null);
    setBusy({ id: summary.id, action: "deleting" });
    setNotice(null);
    try {
      await deleteDocument(summary.id);
      forgetPosition(summary.id);
      removeFromList(summary.id);
      setNotice({ kind: "success", text: `Deleted “${summary.title}”.` });
    } catch (error) {
      const code = codeOf(error);
      if (code === "DOCUMENT_NOT_FOUND") {
        // Already gone: the goal is reached.
        forgetPosition(summary.id);
        removeFromList(summary.id);
        setNotice({ kind: "success", text: `Deleted “${summary.title}”.` });
      } else {
        setNotice({ kind: "error", text: libraryErrorMessage(code) });
      }
    } finally {
      setBusy(null);
    }
  }

  return (
    <div>
      <div className="flex flex-wrap items-end gap-3">
        <div className="mr-auto">
          <h1 className="font-serif text-xl font-medium">Your library</h1>
          <p className="mt-1 text-[13px] text-muted">Saved texts, newest first.</p>
          <p className="mt-1 text-[13px] text-muted">
            <span className="break-all">{account.email}</span> ·{" "}
            <button type="button" onClick={account.onLogout} className="text-accent underline-offset-2 hover:underline">
              Log out
            </button>
          </p>
        </div>
        <button type="button" onClick={onWords} className={secondaryButton}>
          My words
        </button>
        <label
          className={`${secondaryButton} cursor-pointer has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-accent ${
            uploading ? "pointer-events-none opacity-60" : ""
          }`}
        >
          <input type="file" accept=".txt,text/plain" className="sr-only" disabled={uploading} onChange={handleUpload} />
          {uploading ? "Uploading…" : "Upload .txt"}
        </label>
        <button type="button" onClick={onNew} className="rounded-lg bg-accent px-4 py-2 text-sm font-medium text-sheet hover:opacity-90">
          New text
        </button>
      </div>

      {/* Always rendered, so screen readers announce messages that appear later. */}
      <div role="status" className="empty:hidden mt-4 text-[13px]">
        {notice && <p className={notice.kind === "error" ? "text-die" : "text-accent"}>{notice.text}</p>}
      </div>

      <div className="mt-5">
        {list.status === "loading" && <p className="py-8 text-center text-sm text-muted">Loading your library…</p>}

        {list.status === "error" && (
          <div className="rounded-lg border border-line px-5 py-8 text-center">
            <p className="font-serif text-lg">Couldn't load your library</p>
            <p className="mt-1 text-[13px] text-muted">{libraryErrorMessage(list.code)}</p>
            <button type="button" onClick={() => setAttempt((n) => n + 1)} className={`${secondaryButton} mt-4`}>
              Try again
            </button>
          </div>
        )}

        {list.status === "ready" && list.documents.length === 0 && (
          <div className="rounded-lg border border-dashed border-line px-5 py-10 text-center">
            <p className="font-serif text-lg">Your library is empty</p>
            <p className="mt-1 text-[13px] text-muted">Paste a German text with “New text” or upload a .txt file.</p>
          </div>
        )}

        {list.status === "ready" && list.documents.length > 0 && (
          <ul aria-label="Saved texts" className="border-t border-line">
            {list.documents.map((summary) => (
              <DocumentRow
                key={summary.id}
                summary={summary}
                busy={busy?.id === summary.id ? busy.action : null}
                disabled={busy !== null}
                confirming={confirmingId === summary.id}
                renaming={renamingId === summary.id}
                onOpen={() => handleOpen(summary)}
                onStartRename={() => {
                  setConfirmingId(null);
                  setRenamingId(summary.id);
                }}
                onCancelRename={() => setRenamingId(null)}
                onRename={(title) => handleRename(summary, title)}
                onAskDelete={() => {
                  setRenamingId(null);
                  setConfirmingId(summary.id);
                }}
                onCancelDelete={() => setConfirmingId(null)}
                onDelete={() => handleDelete(summary)}
              />
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

interface RowProps {
  summary: DocumentSummary;
  busy: Busy["action"] | null;
  disabled: boolean;
  confirming: boolean;
  renaming: boolean;
  onOpen: () => void;
  onAskDelete: () => void;
  onCancelDelete: () => void;
  onDelete: () => void;
  onStartRename: () => void;
  onCancelRename: () => void;
  onRename: (title: string) => void;
}

const STATUS: Record<Busy["action"], string> = { opening: " · Opening…", deleting: " · Deleting…", renaming: " · Saving…" };

function DocumentRow(props: RowProps) {
  const { summary, busy, disabled, confirming, renaming, onOpen, onAskDelete, onCancelDelete, onDelete } = props;
  if (renaming) {
    return <RenameForm summary={summary} saving={busy === "renaming"} onCancel={props.onCancelRename} onSave={props.onRename} />;
  }
  const status = busy ? STATUS[busy] : "";
  return (
    <li className="flex items-center gap-2 border-b border-line py-2">
      <button
        type="button"
        onClick={onOpen}
        disabled={disabled}
        className="min-w-0 flex-1 rounded-lg px-2 py-1.5 text-left hover:bg-control disabled:hover:bg-transparent"
      >
        <span className="block truncate font-serif text-lg">{summary.title}</span>
        <span className="block text-[13px] text-muted">
          {dateFormat.format(new Date(summary.createdAt))}
          {processingText(summary)}
          {status}
        </span>
      </button>
      {confirming ? (
        <div role="group" aria-label={`Delete “${summary.title}”?`} className="flex shrink-0 items-center gap-1.5 text-sm">
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
        <>
          <button
            type="button"
            onClick={props.onStartRename}
            disabled={disabled}
            aria-label={`Rename “${summary.title}”`}
            className="shrink-0 rounded-lg p-2 text-muted hover:bg-control hover:text-ink disabled:opacity-50"
          >
            <PencilIcon />
          </button>
          <button
            type="button"
            onClick={onAskDelete}
            disabled={disabled}
            aria-label={`Delete “${summary.title}”`}
            className="shrink-0 rounded-lg p-2 text-muted hover:bg-control hover:text-die disabled:opacity-50"
          >
            <TrashIcon />
          </button>
        </>
      )}
    </li>
  );
}

interface RenameFormProps {
  summary: DocumentSummary;
  saving: boolean;
  onCancel: () => void;
  onSave: (title: string) => void;
}

function RenameForm({ summary, saving, onCancel, onSave }: RenameFormProps) {
  const [title, setTitle] = useState(summary.title);
  const [error, setError] = useState<string | null>(null);
  const inputId = useId();
  const errorId = useId();

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    const trimmed = title.trim();
    if (!trimmed) {
      setError("The title can't be empty.");
      return;
    }
    if (trimmed === summary.title) {
      onCancel(); // nothing changed: no request
      return;
    }
    onSave(trimmed);
  }

  return (
    <li className="border-b border-line py-2">
      <form
        aria-label={`Rename “${summary.title}”`}
        onSubmit={handleSubmit}
        onKeyDown={(event) => {
          if (event.key === "Escape") onCancel();
        }}
        className="flex flex-wrap items-center gap-2 px-2 py-1"
        noValidate
      >
        <label htmlFor={inputId} className="sr-only">
          New title
        </label>
        <input
          id={inputId}
          value={title}
          autoFocus
          onFocus={(event) => event.currentTarget.select()}
          maxLength={MAX_TITLE_LENGTH}
          disabled={saving}
          aria-invalid={error !== null}
          aria-describedby={error ? errorId : undefined}
          onChange={(event) => {
            setTitle(event.target.value);
            setError(null);
          }}
          className="min-w-0 flex-1 basis-48 rounded-lg border border-line bg-page/40 px-3 py-1.5 font-serif text-lg focus:border-accent"
        />
        <div className="flex items-center gap-1.5 text-sm">
          <button type="button" onClick={onCancel} disabled={saving} className="rounded-lg px-2.5 py-1.5 hover:bg-control">
            Cancel
          </button>
          <button type="submit" disabled={saving} className="rounded-lg bg-accent px-2.5 py-1.5 font-medium text-sheet hover:opacity-90 disabled:opacity-60">
            {saving ? "Saving…" : "Save"}
          </button>
        </div>
        {error && (
          <p id={errorId} role="alert" className="w-full text-[13px] text-die">
            {error}
          </p>
        )}
      </form>
    </li>
  );
}

function PencilIcon() {
  return (
    <svg width={18} height={18} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L8 18l-4 1 1-4Z" />
    </svg>
  );
}

function TrashIcon() {
  return (
    <svg width={18} height={18} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M4 7h16M10 11v6M14 11v6M5 7l1 12a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2l1-12M9 7V4h6v3" />
    </svg>
  );
}
