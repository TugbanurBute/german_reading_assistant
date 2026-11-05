import {
  FloatingArrow,
  FloatingPortal,
  arrow,
  autoUpdate,
  flip,
  inline,
  offset,
  shift,
  useFloating,
} from "@floating-ui/react";
import { type PointerEvent, type RefObject, useCallback, useEffect, useRef, useState } from "react";
import type { TranslationRequest } from "../api/types.ts";
import { useTranslation } from "../hooks/useTranslation.ts";
import { SaveWordButton } from "./SaveWordButton.tsx";
import { TranslationCard } from "./TranslationCard.tsx";

interface Props {
  anchor: HTMLElement;
  request: TranslationRequest;
  /** The library text being read, linked to saved words; null for unsaved texts. */
  documentId: string | null;
  /** Bottom sheet instead of a floating card (narrow screens). */
  asSheet: boolean;
  popupRef: RefObject<HTMLDivElement | null>;
  onClose: () => void;
  onPointerEnter: (event: PointerEvent) => void;
  onPointerLeave: (event: PointerEvent) => void;
  /** Current height of the bottom sheet in px, 0 when no sheet is shown. */
  onSheetResize: (height: number) => void;
}

const SWIPE_CLOSE_PX = 60;

export function TranslationPopup(props: Props) {
  const { anchor, request, documentId, asSheet, popupRef, onClose, onPointerEnter, onPointerLeave, onSheetResize } = props;
  const { state, retry } = useTranslation(request);
  const visible = !(state.status === "loading" && !state.showSkeleton);
  // State, not just a ref: FloatingPortal mounts its content a render later, and the effect below
  // must run again once the sheet element really exists.
  const [sheet, setSheet] = useState<HTMLDivElement | null>(null);
  const sheetRef = useCallback(
    (element: HTMLDivElement | null) => {
      popupRef.current = element;
      setSheet(element);
    },
    [popupRef],
  );

  // The sheet grows when the skeleton is replaced by the result, so its height is followed, not measured once.
  useEffect(() => {
    if (!sheet) return;
    const report = () => onSheetResize(sheet.offsetHeight);
    report();
    const observer = typeof ResizeObserver === "undefined" ? null : new ResizeObserver(report);
    observer?.observe(sheet);
    return () => {
      observer?.disconnect();
      onSheetResize(0);
    };
  }, [sheet, onSheetResize]);

  const arrowRef = useRef<SVGSVGElement>(null);
  const touchStartY = useRef<number | null>(null);
  const { refs, floatingStyles, context } = useFloating({
    open: true,
    elements: { reference: anchor },
    placement: "bottom",
    // inline() positions against the hovered line of a sentence that wraps over several lines.
    middleware: [inline(), offset(10), flip({ padding: 12 }), shift({ padding: 12 }), arrow({ element: arrowRef })],
    whileElementsMounted: autoUpdate,
  });

  // Fast answers (e.g. cached) appear directly, without a flash of an empty card.
  if (!visible) {
    return null;
  }

  const label = `Translation of “${request.selectedText}”`;
  const card = (
    <>
      <TranslationCard state={state} onRetry={retry} />
      {state.status === "success" && state.data.translationType === "WORD" && (
        <SaveWordButton data={state.data} sentence={request.context} documentId={documentId} />
      )}
    </>
  );

  if (asSheet) {
    return (
      <FloatingPortal>
        <div
          ref={sheetRef}
          role="dialog"
          aria-label={label}
          className="animate-sheet fixed inset-x-0 bottom-0 z-50 max-h-[70vh] overflow-y-auto rounded-t-2xl border-t border-line bg-popup px-5 pt-3 pb-[max(1.25rem,env(safe-area-inset-bottom))] shadow-[0_-8px_28px_var(--shadow)]"
          onTouchStart={(event) => {
            touchStartY.current = event.touches[0]?.clientY ?? null;
          }}
          onTouchEnd={(event) => {
            const endY = event.changedTouches[0]?.clientY;
            if (touchStartY.current !== null && endY !== undefined && endY - touchStartY.current > SWIPE_CLOSE_PX) {
              onClose();
            }
            touchStartY.current = null;
          }}
        >
          <div className="mb-2 flex items-center">
            <div aria-hidden="true" className="mx-auto h-1 w-10 rounded-full bg-line" />
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="absolute top-2 right-3 rounded-lg px-2 py-1 text-lg leading-none text-muted hover:bg-control"
          >
            ×
          </button>
          {card}
        </div>
      </FloatingPortal>
    );
  }

  return (
    <FloatingPortal>
      <div
        ref={(element) => {
          refs.setFloating(element);
          popupRef.current = element;
        }}
        style={floatingStyles}
        role="dialog"
        aria-label={label}
        className="z-50 w-80 max-w-[calc(100vw-24px)]"
        onPointerEnter={onPointerEnter}
        onPointerLeave={onPointerLeave}
      >
        <div className="animate-popup rounded-xl border border-line bg-popup px-[18px] py-4 shadow-[0_10px_28px_var(--shadow)]">
          {card}
        </div>
        <FloatingArrow ref={arrowRef} context={context} fill="var(--popup)" stroke="var(--line)" strokeWidth={1} />
      </div>
    </FloatingPortal>
  );
}
