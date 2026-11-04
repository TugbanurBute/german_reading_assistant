import { act, fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "./App.tsx";
import { clearTranslationCache } from "./api/translations.ts";
import type { TranslationRequest } from "./api/types.ts";

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

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  localStorage.clear();
  clearTranslationCache();
  fetchMock.mockReset();
  fetchMock.mockImplementation(async (_url, init) => fakeBackend(JSON.parse(init?.body as string) as TranslationRequest));
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

async function startReadingSample() {
  const user = userEvent.setup();
  render(<App />);
  await user.click(screen.getByRole("button", { name: "Load sample text" }));
  await user.click(screen.getByRole("button", { name: "Start reading" }));
  return user;
}

const sentRequests = () => fetchMock.mock.calls.map(([, init]) => JSON.parse(init?.body as string) as TranslationRequest);

describe("write screen", () => {
  it("asks for text before reading", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("button", { name: "Start reading" }));

    expect(screen.getByText("Paste some German text first.")).toBeInTheDocument();
    expect(screen.getByRole("textbox")).toHaveAttribute("aria-invalid", "true");
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
    fetchMock.mockResolvedValueOnce(json({ code: "PROVIDER_TIMEOUT", detail: "Too slow." }, 504));
    const user = await startReadingSample();

    await user.click(screen.getAllByRole("button", { name: "Bank" })[0]!);
    expect(await screen.findByText("Translation took too long")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Try again" }));

    expect(await screen.findByText("bench")).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(2);
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

describe("preferences", () => {
  it("remembers the theme", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("button", { name: "Switch to dark theme" }));

    expect(document.documentElement.dataset.theme).toBe("dark");
    expect(localStorage.getItem("lesehilfe.theme")).toBe('"dark"');
  });
});
