import { createContext, useCallback, useContext, useMemo, useState } from "react";
import { ApiError, type ErrorCode } from "../api/client.ts";
import {
  type SaveVocabularyInput,
  type VocabularyEntry,
  deleteVocabulary,
  listVocabulary,
  meaningKey,
  saveVocabulary,
} from "../api/vocabulary.ts";

export type VocabularyList =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "error"; code: ErrorCode }
  | { status: "ready"; entries: VocabularyEntry[] };

/**
 * "My words", shared by the My words screen and the Save button in the reader, so both always agree.
 * The list is loaded the first time either needs it (load), not on every app start.
 */
export interface VocabularyStore {
  list: VocabularyList;
  /** Loads the list unless it is loaded or loading already. */
  load: () => void;
  /** Loads the list again (after an error). */
  reload: () => void;
  /** The saved entry for this meaning, if the list is loaded and contains it. */
  find: (input: SaveVocabularyInput) => VocabularyEntry | undefined;
  /** Rejects with an ApiError. */
  save: (input: SaveVocabularyInput) => Promise<VocabularyEntry>;
  /** Rejects with an ApiError; an entry that is already gone counts as removed. */
  remove: (id: string) => Promise<void>;
}

const codeOf = (error: unknown): ErrorCode => (error instanceof ApiError ? error.code : "INTERNAL_ERROR");

export function useVocabularyStore(): VocabularyStore {
  const [list, setList] = useState<VocabularyList>({ status: "idle" });

  const fetchList = useCallback(() => {
    setList({ status: "loading" });
    listVocabulary().then(
      (entries) => setList({ status: "ready", entries }),
      (error: unknown) => setList({ status: "error", code: codeOf(error) }),
    );
  }, []);

  const load = useCallback(() => {
    if (list.status === "idle") fetchList();
  }, [list.status, fetchList]);

  const keys = useMemo(
    () => new Map(list.status === "ready" ? list.entries.map((entry) => [meaningKey(entry), entry]) : []),
    [list],
  );

  const find = useCallback((input: SaveVocabularyInput) => keys.get(meaningKey(input)), [keys]);

  const save = useCallback(async (input: SaveVocabularyInput) => {
    const entry = await saveVocabulary(input);
    setList((state) =>
      state.status === "ready" && !state.entries.some((e) => e.id === entry.id)
        ? { ...state, entries: [entry, ...state.entries] }
        : state,
    );
    return entry;
  }, []);

  const remove = useCallback(async (id: string) => {
    try {
      await deleteVocabulary(id);
    } catch (error) {
      if (codeOf(error) !== "VOCABULARY_NOT_FOUND") throw error;
    }
    setList((state) => (state.status === "ready" ? { ...state, entries: state.entries.filter((e) => e.id !== id) } : state));
  }, []);

  return useMemo(() => ({ list, load, reload: fetchList, find, save, remove }), [list, load, fetchList, find, save, remove]);
}

export const VocabularyContext = createContext<VocabularyStore | null>(null);

/** Null outside a VocabularyContext provider (e.g. components rendered on their own in tests). */
export function useVocabulary(): VocabularyStore | null {
  return useContext(VocabularyContext);
}
