import type { TranslationResponse } from "../api/types.ts";
import type { TranslationState } from "../hooks/useTranslation.ts";
import { ARTICLE_STYLES, errorCopy, grammarRows, partOfSpeechLabel } from "./labels.ts";

export function TranslationCard({ state, onRetry }: { state: TranslationState; onRetry: () => void }) {
  return (
    <div aria-live="polite">
      {state.status === "loading" && <Skeleton />}
      {state.status === "error" && <ErrorView code={state.error.code} onRetry={onRetry} />}
      {state.status === "success" &&
        (state.data.translationType === "WORD" ? <WordResult data={state.data} /> : <SentenceResult data={state.data} />)}
    </div>
  );
}

function WordResult({ data }: { data: TranslationResponse }) {
  const grammar = data.grammar;
  const article = grammar?.kind === "NOUN" ? grammar.article : null;
  const headword = data.lemma ?? data.originalText;
  const rows = grammarRows(grammar);
  if (data.lemma && data.lemma !== data.originalText) {
    rows.unshift(["In the text", data.originalText]);
  }

  return (
    <>
      <div className="flex items-center gap-2">
        {article && (
          <span className={`rounded-full px-2 py-0.5 text-[11px] font-medium ${ARTICLE_STYLES[article]}`}>
            {article}
          </span>
        )}
        <span lang="de" className="font-serif text-xl font-medium">
          {headword}
        </span>
        {data.partOfSpeech && (
          <span className="ml-auto text-[11px] tracking-wide text-muted">{partOfSpeechLabel(data.partOfSpeech)}</span>
        )}
      </div>
      <p className="mt-2 font-serif text-lg text-accent">{data.translation}</p>

      {rows.length > 0 && (
        <dl className="mt-3 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 border-t border-line pt-2.5">
          {rows.map(([label, value]) => (
            <div key={label} className="contents">
              <dt className="text-[11px] leading-5 tracking-wide text-muted">{label}</dt>
              <dd className="text-[13px] leading-5">{value}</dd>
            </div>
          ))}
        </dl>
      )}

      {data.sentenceTranslation && (
        <p className="mt-2.5 border-t border-line pt-2.5 font-serif text-sm leading-normal text-muted italic">
          {data.sentenceTranslation}
        </p>
      )}
    </>
  );
}

function SentenceResult({ data }: { data: TranslationResponse }) {
  return (
    <>
      <p className="text-[11px] tracking-wide text-muted">Sentence</p>
      <p className="mt-1 font-serif text-lg leading-relaxed">{data.translation}</p>
    </>
  );
}

function ErrorView({ code, onRetry }: { code: Parameters<typeof errorCopy>[0]; onRetry: () => void }) {
  const copy = errorCopy(code);
  return (
    <div className="text-[13px]">
      <p className="font-medium">{copy.title}</p>
      <p className="mt-1 text-muted">{copy.body}</p>
      {copy.canRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="mt-3 rounded-lg border border-line px-3 py-1.5 hover:bg-control"
        >
          Try again
        </button>
      )}
    </div>
  );
}

function Skeleton() {
  return (
    <div role="status" aria-label="Loading translation" className="animate-pulse">
      <div className="mb-3 h-3.5 w-2/5 rounded bg-control" />
      <div className="mb-2 h-3 w-3/4 rounded bg-control" />
      <div className="h-3 w-1/2 rounded bg-control" />
    </div>
  );
}
