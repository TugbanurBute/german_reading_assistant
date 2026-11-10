import { type FormEvent, useId, useState } from "react";
import { deleteAccount } from "../api/auth.ts";
import { ApiError, type ErrorCode } from "../api/client.ts";

interface Props {
  email: string;
  onDeleted: () => void;
  onCancel: () => void;
}

function deleteErrorMessage(code: ErrorCode): string {
  switch (code) {
    case "WRONG_PASSWORD":
      return "The password is incorrect. Nothing was deleted.";
    case "AUTH_RATE_LIMITED":
      return "Too many attempts. Please wait a few minutes and try again.";
    case "NETWORK_ERROR":
      return "Can't reach the server. Nothing was deleted.";
    default:
      return "Something went wrong. Nothing was deleted; try again in a moment.";
  }
}

const input =
  "mt-1 block w-full rounded-lg border border-line bg-page/40 px-3 py-2 text-[15px] focus:border-accent aria-invalid:border-die";

export function DeleteAccountPanel({ email, onDeleted, onCancel }: Props) {
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const headingId = useId();
  const passwordId = useId();
  const errorId = useId();

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (!password) {
      setError("Enter your password to confirm.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await deleteAccount(password);
      onDeleted();
    } catch (failure) {
      setError(deleteErrorMessage(failure instanceof ApiError ? failure.code : "INTERNAL_ERROR"));
      setBusy(false);
    }
  }

  return (
    <form
      onSubmit={handleSubmit}
      noValidate
      aria-labelledby={headingId}
      className="mt-4 rounded-lg border border-die/40 bg-die-bg/40 px-4 py-4"
    >
      <h2 id={headingId} className="font-serif text-lg font-medium">
        Delete your account?
      </h2>
      <p className="mt-1 text-[13px] text-muted">
        This permanently deletes <span className="break-all">{email}</span> with all your saved texts, words and
        statistics. It can't be undone.
      </p>

      <label htmlFor={passwordId} className="mt-3 block text-[13px] text-muted">
        Your password
      </label>
      <input
        id={passwordId}
        type="password"
        autoComplete="current-password"
        value={password}
        onChange={(event) => setPassword(event.target.value)}
        aria-invalid={error !== null}
        aria-describedby={error ? errorId : undefined}
        className={input}
      />
      {error && (
        <p id={errorId} role="alert" className="mt-2 text-[13px] text-die">
          {error}
        </p>
      )}

      <div className="mt-4 flex flex-wrap gap-2">
        <button
          type="submit"
          disabled={busy}
          className="rounded-lg bg-die px-4 py-2 text-sm font-medium text-sheet hover:opacity-90 disabled:opacity-60"
        >
          {busy ? "Deleting…" : "Delete my account"}
        </button>
        <button
          type="button"
          onClick={onCancel}
          disabled={busy}
          className="rounded-lg border border-line px-4 py-2 text-sm hover:bg-control disabled:opacity-60"
        >
          Cancel
        </button>
      </div>
    </form>
  );
}
