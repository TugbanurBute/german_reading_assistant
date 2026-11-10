import { useCallback, useEffect, useState } from "react";
import { type User, currentUser, logout } from "./api/auth.ts";
import { ApiError, type ErrorCode, onSessionExpired } from "./api/client.ts";
import { clearTranslationCache } from "./api/translations.ts";
import type { TargetLanguage } from "./api/types.ts";
import { AuthScreen } from "./components/AuthScreen.tsx";
import { Header, type Theme } from "./components/Header.tsx";
import { libraryErrorMessage } from "./components/labels.ts";
import { ReadingApp } from "./ReadingApp.tsx";
import { usePreference } from "./hooks/usePreference.ts";
import { forgetAllPositions } from "./text/readingPosition.ts";

const THEMES = ["light", "dark"] as const;
const LANGUAGES = ["TR", "EN"] as const;

/** Whether someone is logged in. The token itself lives in an HttpOnly cookie that JavaScript never sees. */
type Session =
  | { status: "checking" }
  | { status: "offline"; code: ErrorCode }
  | { status: "anonymous"; notice: string | null }
  | { status: "authenticated"; user: User };

function systemTheme(): Theme {
  return window.matchMedia?.("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

export default function App() {
  const [theme, setTheme] = usePreference<Theme>("theme", THEMES, systemTheme());
  const [language, setLanguage] = usePreference<TargetLanguage>("language", LANGUAGES, "TR");
  const [session, setSession] = useState<Session>({ status: "checking" });

  useEffect(() => {
    window.document.documentElement.dataset.theme = theme;
  }, [theme]);

  const checkSession = useCallback(() => {
    setSession({ status: "checking" });
    currentUser().then(
      (user) => setSession({ status: "authenticated", user }),
      (error: unknown) => {
        const code = error instanceof ApiError ? error.code : "INTERNAL_ERROR";
        setSession(code === "UNAUTHENTICATED" ? { status: "anonymous", notice: null } : { status: "offline", code });
      },
    );
  }, []);

  useEffect(checkSession, [checkSession]);

  // Any request refused because the login ended (e.g. the token expired) leads back to the login screen.
  useEffect(() => {
    onSessionExpired(() => {
      clearTranslationCache();
      setSession({ status: "anonymous", notice: "Your session has ended. Please log in again." });
    });
    return () => onSessionExpired(null);
  }, []);

  async function handleLogout() {
    try {
      await logout();
    } catch {
      // The cookie expires on its own; the screen logs out either way.
    }
    clearTranslationCache();
    setSession({ status: "anonymous", notice: "You have been logged out." });
  }

  /** The server already removed the account and the login cookie; forget what this browser kept for it. */
  function handleAccountDeleted() {
    clearTranslationCache();
    if (session.status === "authenticated") forgetAllPositions(session.user.id);
    setSession({ status: "anonymous", notice: "Your account and all its data have been deleted." });
  }

  if (session.status === "authenticated") {
    return (
      <ReadingApp
        key={session.user.id}
        user={session.user}
        onLogout={handleLogout}
        onAccountDeleted={handleAccountDeleted}
        theme={theme}
        onThemeChange={setTheme}
        language={language}
        onLanguageChange={setLanguage}
      />
    );
  }

  return (
    <div className="min-h-dvh">
      <Header theme={theme} onThemeChange={setTheme} language={language} onLanguageChange={setLanguage} reading={null} />
      <main className="px-3 py-6 sm:px-6 sm:py-10">
        <div className="mx-auto max-w-[26rem] rounded-xl border border-line bg-sheet px-5 py-6 sm:px-8 sm:py-8">
          {session.status === "checking" && <p className="py-6 text-center text-sm text-muted">Loading…</p>}
          {session.status === "offline" && (
            <div className="py-4 text-center">
              <p className="font-serif text-lg">Couldn't reach the server</p>
              <p className="mt-1 text-[13px] text-muted">{libraryErrorMessage(session.code)}</p>
              <button type="button" onClick={checkSession} className="mt-4 rounded-lg border border-line px-3.5 py-2 text-sm hover:bg-control">
                Try again
              </button>
            </div>
          )}
          {session.status === "anonymous" && (
            <AuthScreen notice={session.notice} onAuthenticated={(user) => setSession({ status: "authenticated", user })} />
          )}
        </div>
      </main>
    </div>
  );
}
