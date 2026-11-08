import { act, fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "./App.tsx";
import { clearTranslationCache } from "./api/translations.ts";
import type { VocabularyStats } from "./api/analytics.ts";
import type { SavedDocument } from "./api/documents.ts";
import { PROCESSING_POLL_MS } from "./components/LibraryScreen.tsx";
import type { TranslationRequest } from "./api/types.ts";
import { type SaveVocabularyInput, type VocabularyEntry, meaningKey } from "./api/vocabulary.ts";
import { paginate } from "./text/paginate.ts";
import { SAMPLE_TEXT } from "./text/sampleText.ts";
import { segmentText } from "./text/segment.ts";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

/** Answers like the backend in mock mode: known words get a translation, everything else a 404. */
function fakeBackend(request: TranslationRequest): Response {
  if (request.translationType === "SENTENCE") {
    return json({
      originalText: request.selectedText,
      translation: request.targetLanguage === "EN" ? "Then she takes her money to the bank." : "Sonra parasını bankaya götürür.",
      lemma: null,
      partOfSpeech: null,
      grammar: null,
      sentenceTranslation: null,
      sourceLanguage: "DE",
      targetLanguage: request.targetLanguage,
      translationType: "SENTENCE",
    });
  }
  if (request.selectedText !== "Bank") {
    return json({ code: "MOCK_TRANSLATION_NOT_FOUND", detail: "No mock translation exists for this text." }, 404);
  }
  const bench = request.context?.includes("Park");
  return json({
    originalText: "Bank",
    translation: bench ? "bench" : "bank",
    lemma: "Bank",
    partOfSpeech: "NOUN",
    grammar: { kind: "NOUN", article: "die", plural: bench ? "Bänke" : "Banken", grammaticalCase: "DATIVE", number: "SINGULAR" },
    sentenceTranslation: bench ? "In the morning my grandmother sits on the bench in the park." : "Then she takes her money to the bank.",
    sourceLanguage: "DE",
    targetLanguage: request.targetLanguage,
    translationType: "WORD",
  });
}

/** The backend's reading library, in memory. */
let library: SavedDocument[] = [];
let nextId = 1;

/** Saved documents are already analysed unless a test asks for `analysing`, like a backend whose queue is fast. */
function saveToLibrary(title: string, content: string, analysing = false): SavedDocument {
  const now = new Date(Date.UTC(2026, 10, 4, 10, nextId)).toISOString();
  const document: SavedDocument = {
    id: `doc-${nextId++}`,
    title,
    content,
    createdAt: now,
    updatedAt: now,
    processingStatus: analysing ? "PENDING" : "COMPLETED",
    wordCount: analysing ? null : content.split(/\s+/).filter(Boolean).length,
    characterCount: analysing ? null : content.length,
    paragraphCount: analysing ? null : content.split(/\n\s*\n/).filter((p) => p.trim()).length,
  };
  library.unshift(document);
  return document;
}

async function fakeLibrary(path: string, init: RequestInit | undefined): Promise<Response> {
  const method = init?.method ?? "GET";
  if (path === "/api/v1/documents" && method === "GET") {
    return json(library.map(({ content: _content, ...summary }) => summary));
  }
  if (path === "/api/v1/documents" && method === "POST") {
    const { title, content } = JSON.parse(init?.body as string) as { title: string; content: string };
    return json(saveToLibrary(title, content), 201);
  }
  if (path === "/api/v1/documents/upload") {
    const file = (init?.body as FormData).get("file") as File;
    const content = await file.text();
    if (content.includes("\u0000")) {
      return json({ code: "INVALID_UPLOAD", detail: "The file must be a non-empty, UTF-8 encoded .txt text file." }, 400);
    }
    return json(saveToLibrary(file.name.replace(/\.txt$/i, ""), content), 201);
  }
  const id = path.split("/").pop();
  const document = library.find((d) => d.id === id);
  if (!document) return json({ code: "DOCUMENT_NOT_FOUND", detail: "Not found." }, 404);
  if (method === "DELETE") {
    library = library.filter((d) => d !== document);
    return new Response(null, { status: 204 });
  }
  if (method === "PATCH") {
    const { title } = JSON.parse(init?.body as string) as { title: string };
    document.title = title.trim();
    document.updatedAt = new Date(Date.UTC(2026, 10, 7, 12)).toISOString();
    const { content: _content, ...summary } = document;
    return json(summary);
  }
  return json(document);
}

/** The backend's "My words", in memory: one entry per meaning, like ux_vocabulary_entry_meaning. */
let vocabulary: VocabularyEntry[] = [];

function fakeVocabulary(path: string, init: RequestInit | undefined): Response {
  const method = init?.method ?? "GET";
  if (method === "GET") return json(vocabulary);
  if (method === "POST") {
    const input = JSON.parse(init?.body as string) as SaveVocabularyInput;
    const existing = vocabulary.find((entry) => meaningKey(entry) === meaningKey(input));
    if (existing) return json(existing);
    const document = library.find((d) => d.id === input.sourceDocumentId);
    const entry: VocabularyEntry = {
      ...input,
      id: `word-${vocabulary.length + 1}`,
      lemma: input.lemma ?? input.originalText,
      sourceDocumentId: document?.id ?? null,
      sourceDocumentTitle: document?.title ?? null,
      createdAt: new Date(Date.UTC(2026, 10, 5, 10, vocabulary.length)).toISOString(),
    };
    vocabulary.unshift(entry);
    return json(entry, 201);
  }
  const id = path.split("/").pop();
  if (!vocabulary.some((entry) => entry.id === id)) return json({ code: "VOCABULARY_NOT_FOUND", detail: "Not found." }, 404);
  vocabulary = vocabulary.filter((entry) => entry.id !== id);
  return new Response(null, { status: 204 });
}

function savedWord(overrides: Partial<VocabularyEntry>): VocabularyEntry {
  const entry: VocabularyEntry = {
    id: `word-${vocabulary.length + 1}`,
    originalText: "Bank",
    lemma: "Bank",
    article: "die",
    partOfSpeech: "NOUN",
    translation: "bench",
    targetLanguage: "TR",
    sourceSentence: "Am Morgen sitzt meine Großmutter auf der Bank im Park.",
    sourceDocumentId: null,
    sourceDocumentTitle: null,
    createdAt: "2026-11-05T10:00:00Z",
    ...overrides,
  };
  vocabulary.unshift(entry);
  return entry;
}

/** The backend's accounts and login, in memory. Tests start logged in as Anna unless they log out. */
const ANNA = { id: "user-anna", email: "anna@example.com" };
const PASSWORDS: Record<string, string> = { "anna@example.com": "geheim-123" };
let loggedIn: { id: string; email: string } | null = ANNA;

function fakeAuth(path: string, init: RequestInit | undefined): Response {
  const unauthenticated = () => json({ code: "UNAUTHENTICATED", detail: "Please log in." }, 401);
  if (path === "/api/v1/auth/me") return loggedIn ? json(loggedIn) : unauthenticated();
  if (path === "/api/v1/auth/logout") {
    loggedIn = null;
    return new Response(null, { status: 204 });
  }
  const { email, password } = JSON.parse(init?.body as string) as { email: string; password: string };
  const normalized = email.trim().toLowerCase();
  if (path === "/api/v1/auth/register") {
    if (PASSWORDS[normalized]) return json({ code: "EMAIL_TAKEN", detail: "Taken." }, 409);
    PASSWORDS[normalized] = password;
    loggedIn = { id: `user-${normalized}`, email: normalized };
    return json(loggedIn, 201);
  }
  if (PASSWORDS[normalized] !== password) return json({ code: "INVALID_CREDENTIALS", detail: "Wrong." }, 401);
  loggedIn = normalized === ANNA.email ? ANNA : { id: `user-${normalized}`, email: normalized };
  return json(loggedIn);
}

/** The backend's learning statistics for the logged-in user: 3–9 November 2026, today last. */
function weekOf(counts: number[]): VocabularyStats {
  const days = counts.map((count, i) => ({ date: `2026-11-0${i + 3}`, count }));
  const total = counts.reduce((sum, count) => sum + count, 0);
  return { timeZone: "UTC", today: counts.at(-1)!, lastSevenDays: total, days };
}

let stats: VocabularyStats = weekOf([0, 0, 0, 0, 0, 0, 0]);

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  localStorage.clear();
  clearTranslationCache();
  library = [];
  nextId = 1;
  vocabulary = [];
  stats = weekOf([0, 0, 0, 0, 0, 0, 0]);
  loggedIn = ANNA;
  for (const email of Object.keys(PASSWORDS)) if (email !== ANNA.email) delete PASSWORDS[email];
  fetchMock.mockReset();
  fetchMock.mockImplementation(async (url, init) => {
    const path = new URL(url as string).pathname;
    if (path.startsWith("/api/v1/auth/")) return fakeAuth(path, init);
    // Like the real backend: everything else needs a login.
    if (!loggedIn) return json({ code: "UNAUTHENTICATED", detail: "Please log in." }, 401);
    if (path === "/api/v1/translations") return fakeBackend(JSON.parse(init?.body as string) as TranslationRequest);
    if (path.startsWith("/api/v1/vocabulary")) return fakeVocabulary(path, init);
    if (path === "/api/v1/analytics/vocabulary") return json(stats);
    return fakeLibrary(path, init);
  });
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

async function openWriteScreen() {
  const user = userEvent.setup();
  render(<App />);
  await user.click(await screen.findByRole("button", { name: "New text" }));
  return user;
}

async function startReadingSample() {
  const user = await openWriteScreen();
  await user.click(screen.getByRole("button", { name: "Load sample text" }));
  await user.click(screen.getByRole("button", { name: "Read without saving" }));
  return user;
}

const translationCalls = () => fetchMock.mock.calls.filter(([url]) => (url as string).endsWith("/api/v1/translations"));
const sentRequests = () => translationCalls().map(([, init]) => JSON.parse(init?.body as string) as TranslationRequest);

describe("write screen", () => {
  it("asks for text before reading", async () => {
    const user = await openWriteScreen();

    await user.click(screen.getByRole("button", { name: "Read without saving" }));

    expect(screen.getByText("Paste some German text first.")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "What would you like to read?" })).toHaveAttribute("aria-invalid", "true");
  });

  it("keeps the draft when going back from the reader to edit it", async () => {
    const user = await startReadingSample();

    await user.click(screen.getByRole("button", { name: "Edit text" }));

    expect(screen.getByRole("textbox", { name: "What would you like to read?" })).toHaveValue(SAMPLE_TEXT);
    expect(library).toHaveLength(0);
  });
});

describe("reading library", () => {
  it("shows the empty state, then saves pasted text and opens it in the reader", async () => {
    const user = userEvent.setup();
    render(<App />);
    expect(await screen.findByText("Your library is empty")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "New text" }));
    await user.type(screen.getByRole("textbox", { name: /Title/ }), "Im Park");
    await user.click(screen.getByRole("button", { name: "Load sample text" }));
    await user.click(screen.getByRole("button", { name: "Save and read" }));

    expect(await screen.findByRole("heading", { name: "Im Park" })).toBeInTheDocument();
    expect(library[0]).toMatchObject({ title: "Im Park", content: SAMPLE_TEXT });

    await user.click(screen.getByRole("button", { name: "Back to library" }));
    const list = await screen.findByRole("list", { name: "Saved texts" });
    expect(within(list).getByText("Im Park")).toBeInTheDocument();
  });

  it("takes the title from the first line when none is given", async () => {
    const user = await openWriteScreen();

    await user.type(screen.getByRole("textbox", { name: "What would you like to read?" }), "Der Hund schläft.{Enter}Die Katze auch.");
    await user.click(screen.getByRole("button", { name: "Save and read" }));

    expect(await screen.findByRole("heading", { name: "Der Hund schläft." })).toBeInTheDocument();
  });

  it("opens a saved text in the reader, where words translate as before", async () => {
    saveToLibrary("Großmutter", SAMPLE_TEXT);
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole("button", { name: /^Großmutter/ }));
    await user.click((await screen.findAllByRole("button", { name: "Bank" }))[0]!);

    const popup = await screen.findByRole("dialog", { name: "Translation of “Bank”" });
    expect(within(popup).getByText("bench")).toBeInTheDocument();
  });

  it("uploads a .txt file and lists it", async () => {
    const user = userEvent.setup();
    render(<App />);
    await screen.findByText("Your library is empty");

    const file = new File(["Es war einmal ein König."], "Märchen.txt", { type: "text/plain" });
    await user.upload(screen.getByLabelText("Upload .txt"), file);

    expect(await screen.findByText("Added “Märchen” to your library.")).toBeInTheDocument();
    expect(within(screen.getByRole("list", { name: "Saved texts" })).getByText("Märchen")).toBeInTheDocument();
  });

  it("rejects other file types before uploading, and shows the server's verdict on bad content", async () => {
    const user = userEvent.setup({ applyAccept: false });
    render(<App />);
    await screen.findByText("Your library is empty");
    const input = screen.getByLabelText("Upload .txt");

    await user.upload(input, new File(["%PDF"], "buch.pdf", { type: "application/pdf" }));
    expect(screen.getByText(/Only non-empty .txt files saved as UTF-8/)).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([url]) => (url as string).endsWith("/upload"))).toBe(false);

    await user.upload(input, new File(["bin\u0000ary"], "bild.txt", { type: "text/plain" }));
    expect(await screen.findByText(/Only non-empty .txt files saved as UTF-8/)).toBeInTheDocument();
    expect(screen.getByText("Your library is empty")).toBeInTheDocument();
  });

  it("deletes a text only after confirmation", async () => {
    saveToLibrary("Behalten", "Text eins.");
    saveToLibrary("Löschen", "Text zwei.");
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole("button", { name: "Delete “Löschen”" }));
    await user.click(within(screen.getByRole("group", { name: "Delete “Löschen”?" })).getByRole("button", { name: "Cancel" }));
    expect(library).toHaveLength(2);

    await user.click(screen.getByRole("button", { name: "Delete “Löschen”" }));
    await user.click(within(screen.getByRole("group", { name: "Delete “Löschen”?" })).getByRole("button", { name: "Delete" }));

    expect(await screen.findByText("Deleted “Löschen”.")).toBeInTheDocument();
    expect(library.map((d) => d.title)).toEqual(["Behalten"]);
    expect(screen.queryByRole("button", { name: /^Löschen/ })).not.toBeInTheDocument();
  });

  it("removes a text that was deleted elsewhere when it can't be opened", async () => {
    saveToLibrary("Verschwunden", "Text.");
    const user = userEvent.setup();
    render(<App />);
    const entry = await screen.findByRole("button", { name: /^Verschwunden/ });
    library = [];

    await user.click(entry);

    expect(await screen.findByText(/This text no longer exists/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Verschwunden/ })).not.toBeInTheDocument();
  });

  it("offers a retry when the library can't be loaded", async () => {
    const backend = fetchMock.getMockImplementation()!;
    let failed = false;
    fetchMock.mockImplementation(async (url, init) => {
      if (!failed && new URL(url as string).pathname === "/api/v1/documents") {
        failed = true;
        throw new TypeError("Failed to fetch");
      }
      return backend(url, init);
    });
    const user = userEvent.setup();
    render(<App />);

    expect(await screen.findByText("Couldn't load your library")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Try again" }));

    expect(await screen.findByText("Your library is empty")).toBeInTheDocument();
  });
});

describe("my words", () => {
  const vocabularyPosts = () =>
    fetchMock.mock.calls.filter(([url, init]) => (url as string).endsWith("/api/v1/vocabulary") && init?.method === "POST");

  async function openMyWords() {
    const user = userEvent.setup();
    render(<App />);
    await user.click(await screen.findByRole("button", { name: "My words" }));
    return user;
  }

  it("saves a translated word from a saved text without translating it again", async () => {
    const document = saveToLibrary("Im Park", SAMPLE_TEXT);
    const user = userEvent.setup();
    render(<App />);
    await user.click(await screen.findByRole("button", { name: /^Im Park/ }));
    await user.click((await screen.findAllByRole("button", { name: "Bank" }))[0]!);
    const popup = await screen.findByRole("dialog", { name: "Translation of “Bank”" });

    await user.click(await within(popup).findByRole("button", { name: "Save to My words" }));

    expect(await within(popup).findByText("Saved to My words")).toBeInTheDocument();
    expect(within(popup).queryByRole("button", { name: "Save to My words" })).not.toBeInTheDocument();
    expect(translationCalls()).toHaveLength(1);
    expect(JSON.parse(vocabularyPosts()[0]![1]?.body as string)).toEqual({
      originalText: "Bank",
      lemma: "Bank",
      article: "die",
      partOfSpeech: "NOUN",
      translation: "bench",
      targetLanguage: "TR",
      sourceSentence: "Am Morgen sitzt meine Großmutter auf der Bank im Park.",
      sourceDocumentId: document.id,
    });
  });

  it("shows Saved for a meaning that is already saved, and Save for another meaning of the same word", async () => {
    savedWord({ translation: "bench", targetLanguage: "TR" });
    const user = await startReadingSample();
    const [parkBank, moneyBank] = screen.getAllByRole("button", { name: "Bank" });

    await user.click(parkBank!);
    let popup = await screen.findByRole("dialog", { name: "Translation of “Bank”" });
    expect(await within(popup).findByText("Saved to My words")).toBeInTheDocument();

    await user.click(moneyBank!);
    popup = await screen.findByRole("dialog", { name: "Translation of “Bank”" });
    await within(popup).findByText("bank");
    await user.click(within(popup).getByRole("button", { name: "Save to My words" }));

    expect(await within(popup).findByText("Saved to My words")).toBeInTheDocument();
    expect(vocabulary.map((entry) => entry.translation)).toEqual(["bank", "bench"]);
  });

  it("offers no Save button in sentence mode", async () => {
    const user = await startReadingSample();
    await user.click(screen.getByRole("radio", { name: "Sentence" }));
    await user.click(screen.getAllByRole("button", { name: "Bank" })[0]!);

    await screen.findByText(/sits on the bench|bankaya|Sonra/);
    expect(screen.queryByRole("button", { name: "Save to My words" })).not.toBeInTheDocument();
  });

  it("shows an error and keeps the button when saving fails", async () => {
    const user = await startReadingSample();
    await user.click(screen.getAllByRole("button", { name: "Bank" })[0]!);
    const popup = await screen.findByRole("dialog", { name: "Translation of “Bank”" });
    const save = await within(popup).findByRole("button", { name: "Save to My words" });

    fetchMock.mockRejectedValueOnce(new TypeError("Failed to fetch"));
    await user.click(save);

    expect(await within(popup).findByText("Couldn't save. Try again.")).toBeInTheDocument();
    await user.click(within(popup).getByRole("button", { name: "Save to My words" }));
    expect(await within(popup).findByText("Saved to My words")).toBeInTheDocument();
  });

  it("shows the empty state", async () => {
    await openMyWords();

    expect(await screen.findByText("No saved words yet")).toBeInTheDocument();
  });

  it("lists saved words with article, translation, sentence and source text", async () => {
    savedWord({ sourceDocumentId: "doc-9", sourceDocumentTitle: "Im Park" });
    savedWord({
      originalText: "abzuheben",
      lemma: "abheben",
      article: null,
      partOfSpeech: "VERB",
      translation: "para çekmek",
      sourceSentence: "Danach geht sie zur Bank, um Geld abzuheben.",
    });
    await openMyWords();

    const list = await screen.findByRole("list", { name: "Saved words" });
    const [verb, noun] = within(list).getAllByRole("listitem");
    expect(verb).toHaveTextContent("abheben");
    expect(verb).toHaveTextContent("Verb");
    expect(verb).toHaveTextContent("para çekmek");
    expect(within(verb!).queryByText("die")).not.toBeInTheDocument();
    expect(noun).toHaveTextContent("die");
    expect(noun).toHaveTextContent("bench");
    expect(noun).toHaveTextContent("Am Morgen sitzt meine Großmutter auf der Bank im Park.");
    expect(noun).toHaveTextContent("from “Im Park”");
  });

  it("deletes a word only after confirmation", async () => {
    savedWord({ translation: "bench" });
    savedWord({ translation: "bank", sourceSentence: "Danach geht sie zur Bank, um Geld abzuheben." });
    const user = await openMyWords();

    await user.click(await screen.findByRole("button", { name: "Delete “Bank” (bank)" }));
    await user.click(within(screen.getByRole("group", { name: "Delete “Bank” (bank)?" })).getByRole("button", { name: "Cancel" }));
    expect(vocabulary).toHaveLength(2);

    await user.click(screen.getByRole("button", { name: "Delete “Bank” (bank)" }));
    await user.click(within(screen.getByRole("group", { name: "Delete “Bank” (bank)?" })).getByRole("button", { name: "Delete" }));

    expect(await screen.findByText("Deleted “Bank”.")).toBeInTheDocument();
    expect(vocabulary.map((entry) => entry.translation)).toEqual(["bench"]);
    expect(screen.queryByRole("button", { name: "Delete “Bank” (bank)" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Delete “Bank” (bench)" })).toBeInTheDocument();
  });

  it("treats a word deleted elsewhere as deleted", async () => {
    savedWord({});
    const user = await openMyWords();
    await user.click(await screen.findByRole("button", { name: "Delete “Bank” (bench)" }));
    vocabulary = [];

    await user.click(within(screen.getByRole("group", { name: "Delete “Bank” (bench)?" })).getByRole("button", { name: "Delete" }));

    expect(await screen.findByText("Deleted “Bank”.")).toBeInTheDocument();
    expect(screen.getByText("No saved words yet")).toBeInTheDocument();
  });

  it("offers a retry when the words can't be loaded", async () => {
    const user = userEvent.setup();
    render(<App />);
    const myWords = await screen.findByRole("button", { name: "My words" });
    const backend = fetchMock.getMockImplementation()!;
    let failed = false;
    fetchMock.mockImplementation(async (url, init) => {
      if (!failed && new URL(url as string).pathname === "/api/v1/vocabulary") {
        failed = true;
        throw new TypeError("Failed to fetch");
      }
      return backend(url, init);
    });

    await user.click(myWords);
    expect(await screen.findByText("Couldn't load your words")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Try again" }));

    expect(await screen.findByText("No saved words yet")).toBeInTheDocument();
  });

  it("keeps the list and the reader in step: a word saved while reading appears in My words", async () => {
    const user = await startReadingSample();
    await user.click(screen.getAllByRole("button", { name: "Bank" })[0]!);
    await user.click(await screen.findByRole("button", { name: "Save to My words" }));
    await screen.findByText("Saved to My words");

    await user.click(screen.getByRole("button", { name: "Edit text" }));
    await user.click(screen.getByRole("button", { name: "← Library" }));
    await user.click(await screen.findByRole("button", { name: "My words" }));

    const list = await screen.findByRole("list", { name: "Saved words" });
    expect(within(list).getByText("bench")).toBeInTheDocument();
  });
});

describe("reading a word", () => {
  it("shows the translation, grammar and sentence translation of the clicked word", async () => {
    const user = await startReadingSample();

    await user.click(screen.getAllByRole("button", { name: "Bank" })[0]!);

    const popup = await screen.findByRole("dialog", { name: "Translation of “Bank”" });
    expect(within(popup).getByText("bench")).toBeInTheDocument();
    expect(within(popup).getByText("die")).toBeInTheDocument();
    expect(within(popup).getByText("Dative · singular")).toBeInTheDocument();
    expect(within(popup).getByText(/my grandmother sits on the bench/)).toBeInTheDocument();
    expect(sentRequests()[0]).toEqual({
      selectedText: "Bank",
      context: "Am Morgen sitzt meine Großmutter auf der Bank im Park.",
      sourceLanguage: "DE",
      targetLanguage: "TR",
      translationType: "WORD",
    });
  });

  it("sends each occurrence with its own sentence, so the meaning follows the context", async () => {
    const user = await startReadingSample();

    await user.click(screen.getAllByRole("button", { name: "Bank" })[1]!);

    expect(await screen.findByText("bank")).toBeInTheDocument();
    expect(sentRequests()[0]?.context).toBe("Danach bringt sie ihr Geld zur Bank.");
  });

  it("closes with Escape, a click outside, or a second click on the same word", async () => {
    const user = await startReadingSample();
    const bank = screen.getAllByRole("button", { name: "Bank" })[0]!;

    await user.click(bank);
    await screen.findByRole("dialog");
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

    await user.click(bank);
    await screen.findByRole("dialog");
    await user.click(document.body);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

    await user.click(bank);
    await screen.findByRole("dialog");
    await user.click(bank);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("opens on mouse hover only after the delay", async () => {
    await startReadingSample();
    vi.useFakeTimers();
    const bank = screen.getAllByRole("button", { name: "Bank" })[0]!;

    fireEvent.pointerOver(bank, { pointerType: "mouse" });
    await act(() => vi.advanceTimersByTimeAsync(400));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

    await act(() => vi.advanceTimersByTimeAsync(200));
    expect(screen.getByRole("dialog")).toBeInTheDocument();

    fireEvent.pointerOut(bank, { pointerType: "mouse" });
    await act(() => vi.advanceTimersByTimeAsync(200));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("moves between words with the arrow keys and translates with Enter", async () => {
    const user = await startReadingSample();
    const words = screen.getAllByRole("button", { name: /^(Am|Morgen)$/ });

    // The text is a single tab stop: only the first word is tabbable at first.
    expect(words[0]).toHaveAttribute("tabindex", "0");
    expect(words[1]).toHaveAttribute("tabindex", "-1");
    act(() => words[0]!.focus());
    await user.keyboard("{ArrowRight}");
    expect(words[1]).toHaveFocus();
    await user.keyboard("{Enter}");

    expect(await screen.findByRole("dialog", { name: "Translation of “Morgen”" })).toBeInTheDocument();
  });

  it("explains that mock mode only knows the sample text, without a retry button", async () => {
    const user = await startReadingSample();

    await user.click(screen.getByRole("button", { name: "Kinder" }));

    expect(await screen.findByText("No sample translation for this")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Try again" })).not.toBeInTheDocument();
  });

  it("offers a retry after a timeout", async () => {
    const user = await startReadingSample();
    fetchMock.mockResolvedValueOnce(json({ code: "PROVIDER_TIMEOUT", detail: "Too slow." }, 504));

    await user.click(screen.getAllByRole("button", { name: "Bank" })[0]!);
    expect(await screen.findByText("Translation took too long")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Try again" }));

    expect(await screen.findByText("bench")).toBeInTheDocument();
    expect(translationCalls()).toHaveLength(2);
  });

  it("asks again in the new language when the target language changes", async () => {
    const user = await startReadingSample();

    await user.click(screen.getByRole("button", { name: "Geld" }));
    await screen.findByRole("dialog");
    await user.click(screen.getByRole("radio", { name: "EN" }));

    expect(sentRequests().map((r) => r.targetLanguage)).toEqual(["TR", "EN"]);
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });
});

describe("reading a sentence", () => {
  it("translates the whole sentence in sentence mode", async () => {
    const user = await startReadingSample();
    await user.click(screen.getByRole("radio", { name: "Sentence" }));

    await user.click(screen.getByRole("button", { name: "Geld" }));

    expect(await screen.findByText("Sonra parasını bankaya götürür.")).toBeInTheDocument();
    expect(sentRequests()[0]).toMatchObject({
      selectedText: "Danach bringt sie ihr Geld zur Bank.",
      context: null,
      translationType: "SENTENCE",
    });
  });
});

describe("on a phone", () => {
  beforeEach(() => {
    // Narrow screen, no reduced-motion preference.
    vi.spyOn(window, "matchMedia").mockImplementation(
      (query) =>
        ({
          matches: query.includes("max-width"),
          media: query,
          addEventListener: () => {},
          removeEventListener: () => {},
        }) as unknown as MediaQueryList,
    );
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("shows the translation in a bottom sheet with a close button", async () => {
    const user = await startReadingSample();

    await user.click(screen.getAllByRole("button", { name: "Bank" })[0]!);
    expect(await screen.findByText("bench")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Close" }));

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("scrolls a word that the sheet would cover into the space above it", async () => {
    const user = await startReadingSample();
    const scrollBy = vi.fn();
    vi.stubGlobal("scrollBy", scrollBy);
    // jsdom has no layout: the sheet is 300px tall and the word sits near the bottom of the 768px window.
    vi.spyOn(HTMLElement.prototype, "offsetHeight", "get").mockReturnValue(300);
    const bank = screen.getAllByRole("button", { name: "Bank" })[0]!;
    vi.spyOn(bank, "getBoundingClientRect").mockReturnValue({ top: 700, bottom: 730, height: 30 } as DOMRect);

    await user.click(bank);
    await screen.findByText("bench");

    // Visible area above the sheet: 768 - 300 = 468px; the word is centred in it: (468 - 30) / 2 = 219.
    expect(scrollBy).toHaveBeenCalledWith({ top: 700 - 219, behavior: "smooth" });
  });
});

describe("preferences", () => {
  it("remembers the theme", async () => {
    const user = userEvent.setup();
    render(<App />);
    await screen.findByRole("button", { name: "New text" }); // the session check is done

    await user.click(screen.getByRole("button", { name: "Switch to dark theme" }));

    expect(document.documentElement.dataset.theme).toBe("dark");
    expect(localStorage.getItem("lesehilfe.theme")).toBe('"dark"');
  });
});

describe("learning statistics", () => {
  async function openMyWordsWith(week: VocabularyStats) {
    stats = week;
    const user = userEvent.setup();
    render(<App />);
    await user.click(await screen.findByRole("button", { name: "My words" }));
    return screen.findByRole("region", { name: "Your week" });
  }

  it("shows today's count and one bar per day of the last week", async () => {
    const section = await openMyWordsWith(weekOf([1, 0, 0, 4, 0, 2, 3]));

    expect(await within(section).findByText("3")).toBeInTheDocument();
    expect(section).toHaveTextContent("words today");
    expect(section).toHaveTextContent("10 words in the last 7 days");
    const days = within(within(section).getByRole("list", { name: "Words saved per day" })).getAllByRole("listitem");
    expect(days).toHaveLength(7);
    expect(days[0]).toHaveTextContent("Tuesday, November 3: 1 word");
    expect(days[6]).toHaveTextContent("Monday, November 9: 3 words");
  });

  it("shows an empty state when nothing was saved this week", async () => {
    const section = await openMyWordsWith(weekOf([0, 0, 0, 0, 0, 0, 0]));

    expect(await within(section).findByText(/No words saved in the last 7 days yet/)).toBeInTheDocument();
    expect(within(section).queryByRole("list")).not.toBeInTheDocument();
  });

  it("shows an error with a retry, without hiding the saved words", async () => {
    savedWord({});
    const backend = fetchMock.getMockImplementation()!;
    let failed = false;
    fetchMock.mockImplementation(async (url, init) => {
      if (!failed && new URL(url as string).pathname === "/api/v1/analytics/vocabulary") {
        failed = true;
        return json({ code: "INTERNAL_ERROR", detail: "Oops." }, 500);
      }
      return backend(url, init);
    });
    stats = weekOf([0, 0, 0, 0, 0, 0, 2]);
    const user = userEvent.setup();
    render(<App />);
    await user.click(await screen.findByRole("button", { name: "My words" }));
    const section = await screen.findByRole("region", { name: "Your week" });

    expect(await within(section).findByText(/Couldn't load your statistics/)).toBeInTheDocument();
    expect(await screen.findByRole("list", { name: "Saved words" })).toBeInTheDocument();

    await user.click(within(section).getByRole("button", { name: "Try again" }));
    expect(await within(section).findByText("2 words in the last 7 days")).toBeInTheDocument();
  });

  it("shows a loading state first", async () => {
    stats = weekOf([0, 0, 0, 0, 0, 0, 1]);
    // Hold the statistics response back until the loading state has been checked.
    const backend = fetchMock.getMockImplementation()!;
    let release: () => void = () => undefined;
    const held = new Promise<void>((resolve) => (release = resolve));
    fetchMock.mockImplementation(async (url, init) => {
      if (new URL(url as string).pathname === "/api/v1/analytics/vocabulary") await held;
      return backend(url, init);
    });
    const user = userEvent.setup();
    render(<App />);
    await user.click(await screen.findByRole("button", { name: "My words" }));

    expect(await screen.findByText("Loading your statistics…")).toBeInTheDocument();
    release();
    expect(await screen.findByText(/1 word in the last 7 days/)).toBeInTheDocument();
  });
});

describe("document analysis", () => {
  it("shows the counts of analysed texts", async () => {
    saveToLibrary("Kurz", "Eins zwei drei.\n\nVier fünf.");
    render(<App />);

    expect(await screen.findByRole("button", { name: /^Kurz/ })).toHaveTextContent("· 5 words · 2 paragraphs");
  });

  it("marks texts still being analysed and shows their counts once done, without blocking reading", async () => {
    const pending = saveToLibrary("Neu", SAMPLE_TEXT, true);
    const user = userEvent.setup();
    render(<App />);
    const row = await screen.findByRole("button", { name: /^Neu/ });
    expect(row).toHaveTextContent("Analysing…");

    // The backend finishes in the background; the library refreshes on its own.
    Object.assign(pending, { processingStatus: "COMPLETED", wordCount: 63, characterCount: 410, paragraphCount: 3 });
    expect(await screen.findByText(/63 words · 3 paragraphs/, {}, { timeout: PROCESSING_POLL_MS + 2000 })).toBeInTheDocument();
    expect(screen.queryByText(/Analysing/)).not.toBeInTheDocument();

    // A pending text opens and translates like any other.
    pending.processingStatus = "PENDING";
    await user.click(screen.getByRole("button", { name: /^Neu/ }));
    await user.click((await screen.findAllByRole("button", { name: "Bank" }))[0]!);
    expect(await screen.findByText("bench")).toBeInTheDocument();
  });

  it("shows nothing extra when the analysis failed", async () => {
    const failed = saveToLibrary("Kaputt", "Text.", true);
    failed.processingStatus = "FAILED";
    render(<App />);

    const row = await screen.findByRole("button", { name: /^Kaputt/ });
    expect(row).not.toHaveTextContent("Analysing");
    expect(row).not.toHaveTextContent("words");
  });
});

describe("translation limit", () => {
  it("explains the limit in the popup, and Try again works once the wait is over", async () => {
    const user = await startReadingSample();
    fetchMock.mockResolvedValueOnce(
      json({ code: "TRANSLATION_RATE_LIMITED", detail: "Limit reached.", retryAfterSeconds: 42 }, 429),
    );

    await user.click(screen.getAllByRole("button", { name: "Bank" })[0]!);

    const popup = await screen.findByRole("dialog", { name: "Translation of “Bank”" });
    expect(await within(popup).findByText("Translation limit reached")).toBeInTheDocument();
    expect(within(popup).getByText(/You can continue in 42 seconds/)).toBeInTheDocument();
    // Still logged in: a 429 is not an ended session.
    expect(screen.queryByRole("heading", { name: "Log in" })).not.toBeInTheDocument();

    await user.click(within(popup).getByRole("button", { name: "Try again" }));
    expect(await screen.findByText("bench")).toBeInTheDocument();
  });
});

describe("accounts", () => {
  const field = (name: string) => screen.getByLabelText(name, { exact: false });

  async function showLogin() {
    loggedIn = null;
    const user = userEvent.setup();
    render(<App />);
    await screen.findByRole("heading", { name: "Log in" });
    return user;
  }

  it("shows the login instead of the library without a session", async () => {
    await showLogin();

    expect(screen.queryByRole("button", { name: "New text" })).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.map(([url]) => new URL(url as string).pathname)).toEqual(["/api/v1/auth/me"]);
  });

  it("logs in and opens the library", async () => {
    const user = await showLogin();

    await user.type(field("Email"), "  Anna@Example.com ");
    await user.type(field("Password"), "geheim-123");
    await user.click(screen.getByRole("button", { name: "Log in" }));

    expect(await screen.findByText("Your library is empty")).toBeInTheDocument();
    expect(screen.getByText("anna@example.com")).toBeInTheDocument();
    // Cookies travel with every request; the token itself is never visible here.
    expect(fetchMock.mock.calls.every(([, init]) => init?.credentials === "include")).toBe(true);
    // Only preferences are stored in the browser, no token.
    expect(Object.keys(localStorage).sort()).toEqual(["lesehilfe.language", "lesehilfe.mode", "lesehilfe.theme"]);
  });

  it("explains a wrong password and keeps the form", async () => {
    const user = await showLogin();

    await user.type(field("Email"), "anna@example.com");
    await user.type(field("Password"), "falsch-falsch");
    await user.click(screen.getByRole("button", { name: "Log in" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("The email or password is incorrect.");
    expect(field("Password")).toHaveAttribute("aria-invalid", "true");
  });

  it("creates an account, checking the password first", async () => {
    const user = await showLogin();
    await user.click(screen.getByRole("button", { name: "Create an account" }));
    expect(screen.getByRole("heading", { name: "Create an account" })).toBeInTheDocument();

    await user.type(field("Email"), "ben@example.com");
    await user.type(field("Password"), "kurz");
    await user.click(screen.getByRole("button", { name: "Create account" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Use at least 8 characters.");
    expect(fetchMock.mock.calls.some(([url]) => (url as string).endsWith("/register"))).toBe(false);

    await user.type(field("Password"), "-und-lang");
    await user.click(screen.getByRole("button", { name: "Create account" }));
    expect(await screen.findByText("ben@example.com")).toBeInTheDocument();
  });

  it("says when an email is already registered", async () => {
    const user = await showLogin();
    await user.click(screen.getByRole("button", { name: "Create an account" }));

    await user.type(field("Email"), "anna@example.com");
    await user.type(field("Password"), "irgendein-passwort");
    await user.click(screen.getByRole("button", { name: "Create account" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("An account with this email already exists.");
  });

  it("logs out and forgets the previous user's screens", async () => {
    saveToLibrary("Annas Text", SAMPLE_TEXT);
    const user = userEvent.setup();
    render(<App />);
    await screen.findByRole("button", { name: /^Annas Text/ });

    await user.click(screen.getByRole("button", { name: "Log out" }));

    expect(await screen.findByRole("heading", { name: "Log in" })).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("You have been logged out.");
    expect(screen.queryByText("Annas Text")).not.toBeInTheDocument();
  });

  it("returns to the login when the session ends while reading", async () => {
    const user = await startReadingSample();
    loggedIn = null; // e.g. the token expired

    await user.click(screen.getAllByRole("button", { name: "Bank" })[0]!);

    expect(await screen.findByRole("heading", { name: "Log in" })).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Your session has ended. Please log in again.");
  });

  it("sends the CSRF token from the XSRF-TOKEN cookie with changes, not with reads", async () => {
    document.cookie = "XSRF-TOKEN=token-123";
    try {
      const user = await openWriteScreen();
      await user.click(screen.getByRole("button", { name: "Load sample text" }));
      await user.click(screen.getByRole("button", { name: "Save and read" }));
      await vi.waitFor(() => expect(library).toHaveLength(1));
    } finally {
      document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
    }

    const headersOf = (method: string) =>
      fetchMock.mock.calls.filter(([, init]) => (init?.method ?? "GET") === method).map(([, init]) => init?.headers as Record<string, string>);
    expect(headersOf("POST").some((headers) => headers["X-XSRF-TOKEN"] === "token-123")).toBe(true);
    expect(headersOf("GET").every((headers) => headers["X-XSRF-TOKEN"] === undefined)).toBe(true);
  });

  it("offers a retry when the server can't be reached at start", async () => {
    const backend = fetchMock.getMockImplementation()!;
    fetchMock.mockImplementationOnce(async () => {
      throw new TypeError("Failed to fetch");
    });
    const user = userEvent.setup();
    render(<App />);

    expect(await screen.findByText("Couldn't reach the server")).toBeInTheDocument();
    fetchMock.mockImplementation(backend);
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText("Your library is empty")).toBeInTheDocument();
  });
});

describe("renaming a text", () => {
  const patchCalls = () => fetchMock.mock.calls.filter(([, init]) => init?.method === "PATCH");

  async function startRenaming(title: string) {
    const user = userEvent.setup();
    render(<App />);
    await user.click(await screen.findByRole("button", { name: `Rename “${title}”` }));
    return { user, form: screen.getByRole("form", { name: `Rename “${title}”` }) };
  }

  it("renames a text without touching its content", async () => {
    const saved = saveToLibrary("Im Park", SAMPLE_TEXT);
    const { user, form } = await startRenaming("Im Park");
    const input = within(form).getByRole("textbox", { name: "New title" });
    expect(input).toHaveValue("Im Park");

    await user.clear(input);
    await user.type(input, "  Großmutters Bank  ");
    await user.click(within(form).getByRole("button", { name: "Save" }));

    expect(await screen.findByText("Renamed to “Großmutters Bank”.")).toBeInTheDocument();
    expect(within(screen.getByRole("list", { name: "Saved texts" })).getByText("Großmutters Bank")).toBeInTheDocument();
    expect(patchCalls()).toHaveLength(1);
    expect(patchCalls()[0]![0]).toMatch(new RegExp(`/api/v1/documents/${saved.id}$`));
    expect(JSON.parse(patchCalls()[0]![1]?.body as string)).toEqual({ title: "Großmutters Bank" });
    expect(library[0]).toMatchObject({ id: saved.id, content: SAMPLE_TEXT });
  });

  it("rejects an empty title without a request, and Escape cancels", async () => {
    saveToLibrary("Im Park", SAMPLE_TEXT);
    const { user, form } = await startRenaming("Im Park");

    await user.clear(within(form).getByRole("textbox", { name: "New title" }));
    await user.click(within(form).getByRole("button", { name: "Save" }));
    expect(within(form).getByRole("alert")).toHaveTextContent("The title can't be empty.");

    await user.keyboard("{Escape}");
    expect(screen.queryByRole("form")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^Im Park/ })).toBeInTheDocument();
    expect(patchCalls()).toHaveLength(0);
  });

  it("removes a text that was deleted elsewhere", async () => {
    saveToLibrary("Verschwunden", "Text.");
    const { user, form } = await startRenaming("Verschwunden");
    library = [];

    await user.type(within(form).getByRole("textbox", { name: "New title" }), " neu");
    await user.click(within(form).getByRole("button", { name: "Save" }));

    expect(await screen.findByText(/This text no longer exists/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Verschwunden/ })).not.toBeInTheDocument();
  });
});

describe("long texts", () => {
  /** The sample text 40 times: about 17,000 characters, several pages. */
  const LONG_TEXT = Array.from({ length: 40 }, () => SAMPLE_TEXT).join("\n\n");
  const pages = paginate(segmentText(LONG_TEXT));
  const positionKey = (id: string) => `lesehilfe.position.${id}`;
  const firstWordOf = (page: number) =>
    pages[page]!.paragraphs[0]!.sentences[0]!.tokens.find((token) => token.wordIndex !== null)!.wordIndex!;

  async function openLongText() {
    const saved = saveToLibrary("Lang", LONG_TEXT);
    const user = userEvent.setup();
    render(<App />);
    await user.click(await screen.findByRole("button", { name: /^Lang/ }));
    await screen.findByText(`Page 1 of ${pages.length}`);
    return { user, saved };
  }

  beforeEach(() => {
    Element.prototype.scrollIntoView = vi.fn();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("shows one page at a time instead of every word", async () => {
    await openLongText();

    expect(pages.length).toBeGreaterThan(3);
    const wordButtons = document.querySelectorAll("[data-word]");
    expect(wordButtons.length).toBe(pages[0]!.wordCount);
    expect(wordButtons.length).toBeLessThan(segmentText(LONG_TEXT).wordCount / 3);
    expect(screen.getByRole("button", { name: "Previous page" })).toBeDisabled();
  });

  it("moves between pages with the buttons and the page menu, focusing the new page", async () => {
    const { user } = await openLongText();
    const nav = screen.getByRole("navigation", { name: "Pages" });

    await user.click(within(nav).getByRole("button", { name: "Next page" }));
    expect(screen.getByText(`Page 2 of ${pages.length}`)).toHaveFocus();
    expect(document.querySelector(`[data-word="${firstWordOf(1)}"]`)).not.toBeNull();
    expect(document.querySelector('[data-word="0"]')).toBeNull();

    await user.selectOptions(within(nav).getByRole("combobox", { name: "Go to page" }), String(pages.length - 1));
    expect(screen.getByText(`Page ${pages.length} of ${pages.length}`)).toBeInTheDocument();
    expect(within(nav).getByRole("button", { name: "Next page" })).toBeDisabled();
  });

  it("translates and saves words on a later page with their own sentence as context", async () => {
    const { user, saved } = await openLongText();
    await user.click(screen.getByRole("button", { name: "Next page" }));

    await user.click(screen.getAllByRole("button", { name: "Bank" })[1]!);
    const popup = await screen.findByRole("dialog", { name: "Translation of “Bank”" });
    expect(within(popup).getByText("bank")).toBeInTheDocument();
    expect(sentRequests().at(-1)).toMatchObject({ context: "Danach bringt sie ihr Geld zur Bank." });

    await user.click(within(popup).getByRole("button", { name: "Save to My words" }));
    expect(await within(popup).findByText("Saved to My words")).toBeInTheDocument();
    expect(vocabulary[0]).toMatchObject({ translation: "bank", sourceDocumentId: saved.id });
  });

  it("keeps arrow-key navigation on the current page", async () => {
    const { user } = await openLongText();
    await user.click(screen.getByRole("button", { name: "Next page" }));
    const first = firstWordOf(1);
    const firstWord = document.querySelector<HTMLElement>(`[data-word="${first}"]`)!;

    firstWord.focus();
    await user.keyboard("{ArrowLeft}");
    expect(firstWord).toHaveFocus();
    await user.keyboard("{ArrowRight}");
    expect(document.querySelector(`[data-word="${first + 1}"]`)).toHaveFocus();
  });

  it("reopens a text on the page and sentence where the reader stopped", async () => {
    const target = pages[2]!.paragraphs[1]!.sentences[1]!;
    const saved = saveToLibrary("Lang", LONG_TEXT);
    localStorage.setItem(positionKey(saved.id), JSON.stringify({ v: 1, sentence: target.id, snippet: target.text.slice(0, 32) }));
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole("button", { name: /^Lang/ }));

    expect(await screen.findByText(`Page 3 of ${pages.length}`)).toBeInTheDocument();
    const sentence = document.querySelector(`[data-sentence="${target.id}"]`);
    expect(vi.mocked(Element.prototype.scrollIntoView).mock.contexts).toContain(sentence);
  });

  it("starts at the top when the saved position is outdated", async () => {
    const saved = saveToLibrary("Lang", LONG_TEXT);
    localStorage.setItem(positionKey(saved.id), JSON.stringify({ v: 1, sentence: 30, snippet: "Ein Satz, den es nicht gibt." }));
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole("button", { name: /^Lang/ }));

    expect(await screen.findByText(`Page 1 of ${pages.length}`)).toBeInTheDocument();
    expect(localStorage.getItem(positionKey(saved.id)) ?? "").not.toContain("Ein Satz");
  });

  it("remembers the first visible sentence while scrolling, without writing on every scroll event", async () => {
    const { user, saved } = await openLongText();
    await user.click(screen.getByRole("button", { name: "Next page" }));
    const visible = pages[1]!.paragraphs[2]!.sentences[0]!;
    await new Promise((resolve) => setTimeout(resolve, 600)); // let the save for the page change pass
    // Now the reader scrolls: sentences before `visible` are above the window.
    vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function (this: HTMLElement) {
      const id = Number(this.dataset.sentence ?? Number.NaN);
      const bottom = id < visible.id ? -10 : 100;
      return { bottom, top: bottom - 20, left: 0, right: 0, width: 0, height: 20, x: 0, y: 0, toJSON: () => ({}) };
    });
    const stored = () => JSON.parse(localStorage.getItem(positionKey(saved.id)) ?? "null") as { sentence: number } | null;
    expect(stored()?.sentence).not.toBe(visible.id);

    for (let i = 0; i < 20; i++) fireEvent.scroll(window);

    // Throttled: nothing is written while the scroll events arrive, one write follows after the interval.
    expect(stored()?.sentence).not.toBe(visible.id);
    await vi.waitFor(() => expect(stored()).toMatchObject({ sentence: visible.id }), { timeout: 2000 });
  });

  it("does not remember positions for texts read without saving", async () => {
    const user = await openWriteScreen();
    await user.type(screen.getByRole("textbox", { name: "What would you like to read?" }), "Kurzer Text.");
    await user.click(screen.getByRole("button", { name: "Read without saving" }));
    await new Promise((resolve) => setTimeout(resolve, 600));

    expect(Object.keys(localStorage).filter((key) => key.startsWith("lesehilfe.position."))).toEqual([]);
  });
});
