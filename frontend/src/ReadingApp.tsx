import { useMemo, useState } from "react";
import type { User } from "./api/auth.ts";
import type { TargetLanguage, TranslationType } from "./api/types.ts";
import { Header, type Theme } from "./components/Header.tsx";
import { LibraryScreen } from "./components/LibraryScreen.tsx";
import { MyWordsScreen } from "./components/MyWordsScreen.tsx";
import { Reader } from "./components/Reader.tsx";
import { WriteScreen } from "./components/WriteScreen.tsx";
import { usePreference } from "./hooks/usePreference.ts";
import { VocabularyContext, useVocabularyStore } from "./hooks/useVocabulary.ts";
import { segmentText } from "./text/segment.ts";

const MODES = ["WORD", "SENTENCE"] as const;

/** A saved text has an id and goes back to the library; a text read without saving goes back to editing. */
type Screen =
  | { name: "library" }
  | { name: "write" }
  | { name: "words" }
  | { name: "reading"; text: string; title: string | null; documentId: string | null };

interface Props {
  user: User;
  onLogout: () => void;
  onAccountDeleted: () => void;
  theme: Theme;
  onThemeChange: (theme: Theme) => void;
  language: TargetLanguage;
  onLanguageChange: (language: TargetLanguage) => void;
}

export function ReadingApp({ user, onLogout, onAccountDeleted, theme, onThemeChange, language, onLanguageChange }: Props) {
  const [mode, setMode] = usePreference<TranslationType>("mode", MODES, "WORD");
  const [screen, setScreen] = useState<Screen>({ name: "library" });
  // The draft survives a look at the reader ("Read without saving", then "Edit text").
  const [draftTitle, setDraftTitle] = useState("");
  const [draftText, setDraftText] = useState("");
  // Shared by My words and the Save button in the reader.
  const vocabulary = useVocabularyStore();

  const readingText = screen.name === "reading" ? screen.text : null;
  const document = useMemo(() => (readingText === null ? null : segmentText(readingText)), [readingText]);

  const toLibrary = () => setScreen({ name: "library" });

  return (
    <VocabularyContext value={vocabulary}>
      <div className="min-h-dvh">
        <Header
          theme={theme}
          onThemeChange={onThemeChange}
          language={language}
          onLanguageChange={onLanguageChange}
          reading={
            screen.name === "reading"
              ? {
                  mode,
                  onModeChange: setMode,
                  back: screen.documentId
                    ? { to: "library", onClick: toLibrary }
                    : { to: "edit", onClick: () => setScreen({ name: "write" }) },
                }
              : null
          }
        />
        <main className="px-3 py-6 sm:px-6 sm:py-10">
          <div className="mx-auto max-w-[44rem] rounded-xl border border-line bg-sheet px-5 py-6 sm:px-10 sm:py-9">
            {screen.name === "library" && (
              <LibraryScreen
                account={{ email: user.email, onLogout, onDeleted: onAccountDeleted }}
                onNew={() => setScreen({ name: "write" })}
                onWords={() => setScreen({ name: "words" })}
                onOpen={(saved) => setScreen({ name: "reading", text: saved.content, title: saved.title, documentId: saved.id })}
              />
            )}
            {screen.name === "write" && (
              <WriteScreen
                title={draftTitle}
                text={draftText}
                onTitleChange={setDraftTitle}
                onTextChange={setDraftText}
                onSaved={(saved) => {
                  setDraftTitle("");
                  setDraftText("");
                  setScreen({ name: "reading", text: saved.content, title: saved.title, documentId: saved.id });
                }}
                onReadUnsaved={() => setScreen({ name: "reading", text: draftText, title: null, documentId: null })}
                onBack={toLibrary}
              />
            )}
            {screen.name === "words" && <MyWordsScreen vocabulary={vocabulary} onBack={toLibrary} />}
            {screen.name === "reading" && document && (
              <>
                {screen.title && <h1 className="mb-5 font-serif text-2xl font-medium">{screen.title}</h1>}
                {/* A new key starts a new reading (first page, fresh position) for every text opened. */}
                <Reader
                  key={screen.documentId ?? `draft:${screen.text}`}
                  document={document}
                  mode={mode}
                  language={language}
                  documentId={screen.documentId}
                />
              </>
            )}
          </div>
        </main>
      </div>
    </VocabularyContext>
  );
}
