import { act, fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "./App.tsx";
import { clearTranslationCache } from "./api/translations.ts";
import type { SavedDocument } from "./api/documents.ts";
import type { TranslationRequest } from "./api/types.ts";
import { SAMPLE_TEXT } from "./text/sampleText.ts";

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

function saveToLibrary(title: string, content: string): SavedDocument {
  const now = new Date(Date.UTC(2026, 10, 4, 10, nextId)).toISOString();
  const document = { id: `doc-${nextId++}`, title, content, createdAt: now, updatedAt: now };
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
  return json(document);
}

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  localStorage.clear();
  clearTranslationCache();
  library = [];
  nextId = 1;
  fetchMock.mockReset();
  fetchMock.mockImplementation(async (url, init) => {
    const path = new URL(url as string).pathname;
    return path === "/api/v1/translations"
      ? fakeBackend(JSON.parse(init?.body as string) as TranslationRequest)
      : fakeLibrary(path, init);
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
    fetchMock.mockRejectedValueOnce(new TypeError("Failed to fetch"));
    const user = userEvent.setup();
    render(<App />);

    expect(await screen.findByText("Couldn't load your library")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Try again" }));

    expect(await screen.findByText("Your library is empty")).toBeInTheDocument();
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

    await user.click(screen.getByRole("button", { name: "Switch to dark theme" }));

    expect(document.documentElement.dataset.theme).toBe("dark");
    expect(localStorage.getItem("lesehilfe.theme")).toBe('"dark"');
  });
});
