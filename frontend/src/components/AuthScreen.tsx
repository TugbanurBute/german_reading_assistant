import { type FormEvent, useId, useState } from "react";
import { type User, login, passwordProblem, register } from "../api/auth.ts";
import { ApiError, type ErrorCode } from "../api/client.ts";

interface Props {
  /** Why the login screen is shown, e.g. after a session ended. */
  notice: string | null;
  onAuthenticated: (user: User) => void;
}

function authErrorMessage(code: ErrorCode): string {
  switch (code) {
    case "INVALID_CREDENTIALS":
      return "The email or password is incorrect.";
    case "EMAIL_TAKEN":
      return "An account with this email already exists. Log in instead.";
    case "VALIDATION_ERROR":
      return "Enter a valid email address and a password of 8 to 72 characters.";
    case "NETWORK_ERROR":
      return "Can't reach the server. Check that the backend is running.";
    case "FORBIDDEN":
      return "The request was refused. Reload the page and try again.";
    default:
      return "Something went wrong. Try again in a moment.";
  }
}

const input =
  "mt-1 block w-full rounded-lg border border-line bg-page/40 px-3 py-2 text-[15px] focus:border-accent aria-invalid:border-die";

/** Login and registration in one form; the token never reaches JavaScript (the server sets an HttpOnly cookie). */
export function AuthScreen({ notice, onAuthenticated }: Props) {
  const [mode, setMode] = useState<"login" | "register">("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const emailId = useId();
  const passwordId = useId();
  const errorId = useId();
  const registering = mode === "register";

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (!email.trim() || !password) {
      setError("Enter your email and password.");
      return;
    }
    const problem = registering ? passwordProblem(password) : null;
    if (problem) {
      setError(problem);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      onAuthenticated(await (registering ? register(email.trim(), password) : login(email.trim(), password)));
    } catch (failure) {
      setError(authErrorMessage(failure instanceof ApiError ? failure.code : "INTERNAL_ERROR"));
      setBusy(false);
    }
  }

  function switchMode() {
    setMode(registering ? "login" : "register");
    setError(null);
  }

  return (
    <form onSubmit={handleSubmit} noValidate aria-labelledby={`${emailId}-heading`}>
      <h1 id={`${emailId}-heading`} className="font-serif text-xl font-medium">
        {registering ? "Create an account" : "Log in"}
      </h1>
      <p className="mt-1 text-[13px] text-muted">
        {registering ? "Your texts and words are only visible to you." : "Welcome back to Lesehilfe."}
      </p>
      {notice && (
        <p role="status" className="mt-3 rounded-lg bg-control px-3 py-2 text-[13px]">
          {notice}
        </p>
      )}

      <label htmlFor={emailId} className="mt-5 block text-[13px] text-muted">
        Email
      </label>
      <input
        id={emailId}
        type="email"
        autoComplete="email"
        value={email}
        onChange={(event) => setEmail(event.target.value)}
        aria-invalid={error !== null}
        aria-describedby={error ? errorId : undefined}
        className={input}
      />

      <label htmlFor={passwordId} className="mt-4 block text-[13px] text-muted">
        Password {registering && <span className="text-muted/80">(at least 8 characters)</span>}
      </label>
      <input
        id={passwordId}
        type="password"
        autoComplete={registering ? "new-password" : "current-password"}
        value={password}
        onChange={(event) => setPassword(event.target.value)}
        aria-invalid={error !== null}
        aria-describedby={error ? errorId : undefined}
        className={input}
      />

      {error && (
        <p id={errorId} role="alert" className="mt-3 text-[13px] text-die">
          {error}
        </p>
      )}

      <button
        type="submit"
        disabled={busy}
        className="mt-5 w-full rounded-lg bg-accent px-4 py-2 text-sm font-medium text-sheet hover:opacity-90 disabled:opacity-60"
      >
        {busy ? "Please wait…" : registering ? "Create account" : "Log in"}
      </button>

      <p className="mt-4 text-center text-[13px] text-muted">
        {registering ? "Already have an account?" : "New here?"}{" "}
        <button type="button" onClick={switchMode} className="text-accent underline-offset-2 hover:underline">
          {registering ? "Log in" : "Create an account"}
        </button>
      </p>
    </form>
  );
}
