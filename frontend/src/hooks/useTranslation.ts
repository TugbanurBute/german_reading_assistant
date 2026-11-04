import { useEffect, useState } from "react";
import { ApiError, translate } from "../api/translations.ts";
import type { TranslationRequest, TranslationResponse } from "../api/types.ts";

/** Only show the loading skeleton if the answer takes longer than this, so cached answers don't flicker. */
export const SKELETON_DELAY_MS = 150;

export type TranslationState =
  | { status: "loading"; showSkeleton: boolean }
  | { status: "success"; data: TranslationResponse }
  | { status: "error"; error: ApiError };

export function useTranslation(request: TranslationRequest | null) {
  const [state, setState] = useState<TranslationState>({ status: "loading", showSkeleton: false });
  const [attempt, setAttempt] = useState(0);
  const key = request ? JSON.stringify(request) : null;

  useEffect(() => {
    if (!key) {
      return;
    }
    // Answers that arrive after the reader moved to another word are ignored.
    let current = true;
    setState({ status: "loading", showSkeleton: false });
    const timer = setTimeout(() => {
      if (current) setState({ status: "loading", showSkeleton: true });
    }, SKELETON_DELAY_MS);

    translate(JSON.parse(key) as TranslationRequest)
      .then((data) => {
        if (current) setState({ status: "success", data });
      })
      .catch((error: unknown) => {
        if (current) {
          setState({
            status: "error",
            error: error instanceof ApiError ? error : new ApiError("INTERNAL_ERROR", String(error)),
          });
        }
      })
      .finally(() => clearTimeout(timer));

    return () => {
      current = false;
      clearTimeout(timer);
    };
  }, [key, attempt]);

  return { state, retry: () => setAttempt((n) => n + 1) };
}
