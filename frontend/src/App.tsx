import { useEffect, useMemo, useState } from "react";
import type { TargetLanguage, TranslationType } from "./api/types.ts";
import { Header, type Theme } from "./components/Header.tsx";
import { ReadingView } from "./components/ReadingView.tsx";
import { WriteScreen } from "./components/WriteScreen.tsx";
import { usePreference } from "./hooks/usePreference.ts";
import { segmentText } from "./text/segment.ts";

const THEMES = ["light", "dark"] as const;
const LANGUAGES = ["TR", "EN"] as const;
const MODES = ["WORD", "SENTENCE"] as const;

function systemTheme(): Theme {
  return window.matchMedia?.("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

export default function App() {
  const [theme, setTheme] = usePreference<Theme>("theme", THEMES, systemTheme());
  const [language, setLanguage] = usePreference<TargetLanguage>("language", LANGUAGES, "TR");
  const [mode, setMode] = usePreference<TranslationType>("mode", MODES, "WORD");
  const [text, setText] = useState("");
  const [reading, setReading] = useState(false);
  const document = useMemo(() => (reading ? segmentText(text) : null), [reading, text]);

  useEffect(() => {
    window.document.documentElement.dataset.theme = theme;
  }, [theme]);

  return (
    <div className="min-h-dvh">
      <Header
        theme={theme}
        onThemeChange={setTheme}
        language={language}
        onLanguageChange={setLanguage}
        reading={document ? { mode, onModeChange: setMode, onEdit: () => setReading(false) } : null}
      />
      <main className="px-3 py-6 sm:px-6 sm:py-10">
        <div className="mx-auto max-w-[44rem] rounded-xl border border-line bg-sheet px-5 py-6 sm:px-10 sm:py-9">
          {document ? (
            // A new key resets the popup and keyboard position when the mode changes.
            <ReadingView key={mode} document={document} mode={mode} language={language} />
          ) : (
            <WriteScreen text={text} onTextChange={setText} onRead={() => setReading(true)} />
          )}
        </div>
      </main>
    </div>
  );
}
