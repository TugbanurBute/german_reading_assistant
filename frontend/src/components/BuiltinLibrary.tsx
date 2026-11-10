import { useEffect, useState } from "react";
import { ApiError, type ErrorCode } from "../api/client.ts";
import { type BuiltinText, type BuiltinTextSummary, CEFR_LEVELS, type CefrLevel, getBuiltinText, listBuiltinTexts } from "../api/library.ts";
import { usePreference } from "../hooks/usePreference.ts";
import { libraryErrorMessage } from "./labels.ts";
import { SegmentedControl } from "./SegmentedControl.tsx";

interface Props {
  onOpen: (text: BuiltinText) => void;
}

type ListState =
  | { status: "loading" }
  | { status: "error"; code: ErrorCode }
  | { status: "ready"; texts: BuiltinTextSummary[] };

type Filter = "ALL" | CefrLevel;

const FILTERS = [{ value: "ALL", label: "All" }, ...CEFR_LEVELS.map((level) => ({ value: level, label: level }))] as const;
const FILTER_VALUES = FILTERS.map((option) => option.value);

const codeOf = (error: unknown): ErrorCode => (error instanceof ApiError ? error.code : "INTERNAL_ERROR");

/** The shared texts, filtered by level on the client (15 short entries). Read-only: no rename or delete. */
export function BuiltinLibrary({ onOpen }: Props) {
  const [list, setList] = useState<ListState>({ status: "loading" });
  const [attempt, setAttempt] = useState(0);
  const [filter, setFilter] = usePreference<Filter>("builtinLevel", FILTER_VALUES, "ALL");
  const [opening, setOpening] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let current = true;
    setList({ status: "loading" });
    listBuiltinTexts().then(
      (texts) => current && setList({ status: "ready", texts }),
      (failure: unknown) => current && setList({ status: "error", code: codeOf(failure) }),
    );
    return () => {
      current = false;
    };
  }, [attempt]);

  async function handleOpen(summary: BuiltinTextSummary) {
    setOpening(summary.id);
    setError(null);
    try {
      onOpen(await getBuiltinText(summary.id));
    } catch (failure) {
      setError(libraryErrorMessage(codeOf(failure)));
      setOpening(null);
    }
  }

  const shown = list.status === "ready" ? list.texts.filter((text) => filter === "ALL" || text.level === filter) : [];

  return (
    <div>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-[13px] text-muted">Short German texts for learners, from A1 to B1.</p>
        <SegmentedControl label="Level" value={filter} options={FILTERS} onChange={setFilter} />
      </div>

      <div role="status" className="empty:hidden mt-3 text-[13px] text-die">
        {error}
      </div>

      <div className="mt-4">
        {list.status === "loading" && <p className="py-8 text-center text-sm text-muted">Loading texts…</p>}

        {list.status === "error" && (
          <div className="rounded-lg border border-line px-5 py-8 text-center">
            <p className="font-serif text-lg">Couldn't load the texts</p>
            <p className="mt-1 text-[13px] text-muted">{libraryErrorMessage(list.code)}</p>
            <button
              type="button"
              onClick={() => setAttempt((n) => n + 1)}
              className="mt-4 rounded-lg border border-line px-3.5 py-2 text-sm hover:bg-control"
            >
              Try again
            </button>
          </div>
        )}

        {list.status === "ready" && shown.length === 0 && (
          <div className="rounded-lg border border-dashed border-line px-5 py-10 text-center">
            <p className="font-serif text-lg">No texts for this level yet</p>
            <p className="mt-1 text-[13px] text-muted">Choose another level above.</p>
          </div>
        )}

        {shown.length > 0 && (
          <ul aria-label="Built-in texts" className="border-t border-line">
            {shown.map((text) => (
              <li key={text.id} className="border-b border-line py-2">
                <button
                  type="button"
                  onClick={() => handleOpen(text)}
                  disabled={opening !== null}
                  className="block w-full rounded-lg px-2 py-1.5 text-left hover:bg-control disabled:hover:bg-transparent"
                >
                  <span className="block font-serif text-lg">{text.title}</span>
                  <span className="block text-[13px] text-muted">
                    <span className="font-medium text-ink">{text.level}</span> · {text.readingMinutes} min read
                    {opening === text.id && " · Opening…"}
                  </span>
                  <span lang="de" className="mt-0.5 block text-[13px] text-muted">
                    {text.description}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
