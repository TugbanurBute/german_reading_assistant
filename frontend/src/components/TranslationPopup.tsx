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
import { type PointerEvent, type RefObject, useRef } from "react";
import type { TranslationRequest } from "../api/types.ts";
import { useTranslation } from "../hooks/useTranslation.ts";
import { TranslationCard } from "./TranslationCard.tsx";

interface Props {
  anchor: HTMLElement;
  request: TranslationRequest;
  /** Bottom sheet instead of a floating card (narrow screens). */
  asSheet: boolean;
  popupRef: RefObject<HTMLDivElement | null>;
  onClose: () => void;
  onPointerEnter: (event: PointerEvent) => void;
  onPointerLeave: (event: PointerEvent) => void;
}

const SWIPE_CLOSE_PX = 60;

export function TranslationPopup({ anchor, request, asSheet, popupRef, onClose, onPointerEnter, onPointerLeave }: Props) {
  const { state, retry } = useTranslation(request);
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
  if (state.status === "loading" && !state.showSkeleton) {
    return null;
  }

  const label = `Translation of “${request.selectedText}”`;
  const card = <TranslationCard state={state} onRetry={retry} />;

  if (asSheet) {
    return (
      <FloatingPortal>
        <div
          ref={popupRef}
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
