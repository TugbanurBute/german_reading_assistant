import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, clearTranslationCache, translate } from "./translations.ts";
import type { TranslationRequest } from "./types.ts";

const REQUEST: TranslationRequest = {
  selectedText: "Bank",
  context: "Danach bringt sie ihr Geld zur Bank.",
  sourceLanguage: "DE",
  targetLanguage: "EN",
  translationType: "WORD",
};

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

describe("translate", () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    clearTranslationCache();
    fetchMock.mockReset();
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("posts the request as JSON to the translations endpoint", async () => {
    fetchMock.mockResolvedValue(json({ translation: "bank" }));

    await expect(translate(REQUEST)).resolves.toEqual({ translation: "bank" });

    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe("http://localhost:8080/api/v1/translations");
    expect(init?.method).toBe("POST");
    expect(JSON.parse(init?.body as string)).toEqual(REQUEST);
  });

  it("turns problem details into an ApiError with the backend code", async () => {
    fetchMock.mockResolvedValue(json({ code: "PROVIDER_TIMEOUT", detail: "took too long" }, 504));

    const error = await translate(REQUEST).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ code: "PROVIDER_TIMEOUT" });
  });

  it("reports an unreachable server as NETWORK_ERROR", async () => {
    fetchMock.mockRejectedValue(new TypeError("Failed to fetch"));

    await expect(translate(REQUEST)).rejects.toMatchObject({ code: "NETWORK_ERROR" });
  });

  it("treats a non-JSON error body as INTERNAL_ERROR", async () => {
    fetchMock.mockResolvedValue(new Response("<html>Bad gateway</html>", { status: 502 }));

    await expect(translate(REQUEST)).rejects.toMatchObject({ code: "INTERNAL_ERROR" });
  });

  it("reuses a successful answer for the same request", async () => {
    fetchMock.mockImplementation(async () => json({ translation: "bank" }));

    await translate(REQUEST);
    await translate({ ...REQUEST });
    await translate({ ...REQUEST, targetLanguage: "TR" });

    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("forgets failures so the next attempt asks the server again", async () => {
    fetchMock.mockResolvedValueOnce(json({ code: "PROVIDER_UNAVAILABLE" }, 503));
    fetchMock.mockResolvedValueOnce(json({ translation: "bank" }));

    await expect(translate(REQUEST)).rejects.toBeInstanceOf(ApiError);
    await expect(translate(REQUEST)).resolves.toEqual({ translation: "bank" });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});
