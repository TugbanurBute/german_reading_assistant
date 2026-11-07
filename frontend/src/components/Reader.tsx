import { useEffect, useMemo, useRef, useState } from "react";
import type { TargetLanguage, TranslationType } from "../api/types.ts";
import { pageOfSentence, paginate } from "../text/paginate.ts";
import { findSentence, loadPosition, savePosition } from "../text/readingPosition.ts";
import type { ReadingDocument } from "../text/segment.ts";
import { ReadingView } from "./ReadingView.tsx";

/** At most one position write per this interval while scrolling. */
export const POSITION_SAVE_INTERVAL_MS = 500;

interface Props {
  document: ReadingDocument;
  mode: TranslationType;
  language: TargetLanguage;
  /** Saved library texts remember the reading position; unsaved texts don't. */
  documentId: string | null;
}

/**
 * Shows a document page by page (see paginate) and, for saved texts, remembers and restores the reading
 * position. Translation itself stays in ReadingView, which only ever renders the current page.
 */
export function Reader({ document, mode, language, documentId }: Props) {
  const pages = useMemo(() => paginate(document), [document]);
  const [resumeAt] = useState(() => (documentId ? loadPosition(documentId, document) : null));
  const [page, setPage] = useState(() => (resumeAt === null ? 0 : Math.max(0, pageOfSentence(pages, resumeAt))));
  const containerRef = useRef<HTMLDivElement>(null);
  const pageLabelRef = useRef<HTMLParagraphElement>(null);
  const restored = useRef(false);
  const pageChanged = useRef(false);

  // Reopening a text: bring the sentence where the reader stopped to the top of the screen, once.
  useEffect(() => {
    if (resumeAt === null || restored.current) return;
    restored.current = true;
    containerRef.current?.querySelector(`[data-sentence="${resumeAt}"]`)?.scrollIntoView?.({ block: "start" });
  }, [resumeAt]);

  // After Previous/Next: start the new page at its top and move focus there, so keyboard and
  // screen-reader users continue on the new page.
  useEffect(() => {
    if (!pageChanged.current) return;
    pageChanged.current = false;
    containerRef.current?.scrollIntoView?.({ block: "start" });
    pageLabelRef.current?.focus({ preventScroll: true });
  }, [page]);

  // Remember the first visible sentence while scrolling, at most every POSITION_SAVE_INTERVAL_MS.
  useEffect(() => {
    if (!documentId) return;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const save = () => {
      timer = undefined;
      const id = firstVisibleSentence(containerRef.current);
      const sentence = id === null ? undefined : findSentence(pages[page]!, id);
      if (sentence) savePosition(documentId, sentence);
    };
    const schedule = () => {
      timer ??= setTimeout(save, POSITION_SAVE_INTERVAL_MS);
    };
    window.addEventListener("scroll", schedule, { passive: true });
    schedule(); // a new page counts as a new position, even without scrolling
    return () => {
      window.removeEventListener("scroll", schedule);
      clearTimeout(timer);
    };
  }, [documentId, pages, page]);

  const goTo = (target: number) => {
    if (target === page || target < 0 || target >= pages.length) return;
    pageChanged.current = true;
    setPage(target);
  };

  const paged = pages.length > 1;

  return (
    <div ref={containerRef} className="scroll-mt-4">
      {paged && (
        <p ref={pageLabelRef} tabIndex={-1} className="mb-4 text-[13px] text-muted outline-none">
          Page {page + 1} of {pages.length}
        </p>
      )}
      {/* A new key resets the popup and keyboard position when the mode or the page changes. */}
      <ReadingView key={`${mode}:${page}`} document={pages[page]!} mode={mode} language={language} documentId={documentId} />
      {paged && <PageNav page={page} count={pages.length} onGoTo={goTo} />}
    </div>
  );
}

function PageNav({ page, count, onGoTo }: { page: number; count: number; onGoTo: (page: number) => void }) {
  const button = "rounded-lg border border-line px-3 py-1.5 text-sm hover:bg-control disabled:opacity-40 disabled:hover:bg-transparent";
  return (
    <nav aria-label="Pages" className="mt-8 flex items-center justify-between gap-2 border-t border-line pt-4">
      <button type="button" className={button} disabled={page === 0} aria-label="Previous page" onClick={() => onGoTo(page - 1)}>
        ← Previous
      </button>
      <label className="flex items-center gap-2 text-[13px] text-muted">
        <span className="sr-only sm:not-sr-only">Go to page</span>
        <select
          value={page}
          aria-label="Go to page"
          onChange={(event) => onGoTo(Number(event.target.value))}
          className="rounded-lg border border-line bg-sheet px-2 py-1.5 text-sm text-ink"
        >
          {Array.from({ length: count }, (_, index) => (
            <option key={index} value={index}>
              {index + 1} / {count}
            </option>
          ))}
        </select>
      </label>
      <button type="button" className={button} disabled={page === count - 1} aria-label="Next page" onClick={() => onGoTo(page + 1)}>
        Next →
      </button>
    </nav>
  );
}

/** The id of the first sentence whose bottom edge is below the top of the window, or null. */
function firstVisibleSentence(container: HTMLElement | null): number | null {
  if (!container) return null;
  for (const element of container.querySelectorAll<HTMLElement>("[data-sentence]")) {
    if (element.getBoundingClientRect().bottom > 0) return Number(element.dataset.sentence);
  }
  return null;
}
