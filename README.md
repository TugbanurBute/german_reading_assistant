# German Reading Assistant

Read German text and see context-aware Turkish or English translations of words and sentences without leaving the page.

Paste a German text, then hover over a word (desktop, 500 ms) or tap it (phone) to see:

- its translation **in this context** ("Bank" next to "Park" is a bench, next to "Geld" a bank),
- the dictionary form, part of speech and grammar (article, case, plural; tense, Perfekt form, separable prefix),
- the translation of the whole sentence.

Switch to **Sentence** mode to translate whole sentences. Translations are cached in PostgreSQL, so asking twice costs one AI call.

Texts can be saved to a personal **reading library**, by pasting them or uploading a UTF-8 `.txt` file,
and opened again later.

> Status: MVP v0.1. The frontend is called "Lesehilfe".

Every user has an account. Texts and saved words belong to their owner and are invisible to everyone else.

> [!WARNING]
> **Before using it anywhere but your own machine:**
> - Set `JWT_SECRET`.
> - Serve it over HTTPS (the login cookie is `Secure` outside the `local` profile).
> - Add rate limiting for login and registration, which the app doesn't do yet.

## Layout

```
backend/   Spring Boot 4.1 (Java 21, Maven Wrapper), PostgreSQL + Flyway, Google Gemini
frontend/  React 19 + TypeScript + Vite 8 + Tailwind CSS 4
```

## Prerequisites

| Tool        | Version | Needed for                                              |
|-------------|---------|---------------------------------------------------------|
| Java        | 21      | backend                                                 |
| Node.js     | 22.12+  | frontend                                                |
| PostgreSQL  | 15+     | running the backend and `./mvnw verify` (not for `./mvnw test`) |
| Gemini key  | –       | only with `TRANSLATION_PROVIDER=gemini`                 |

Maven is not required — use the bundled wrapper (`./mvnw`, or `mvnw.cmd` on Windows).

## Quick start (mock mode, no API key)

1. **Database** — create an empty database and user, for example:

   ```sql
   CREATE USER german_reader WITH PASSWORD 'change-me';
   CREATE DATABASE german_reader OWNER german_reader;
   ```

   Flyway creates the table on the first start.

2. **Backend** — on :8080:

   ```bash
   cd backend
   cp .env .env          # then set DB_PASSWORD
   ./mvnw spring-boot:run        # uses the "local" profile (set in pom.xml)
   ```

3. **Frontend** — on :5173:

   ```bash
   cd frontend
   npm install
   npm run dev
   ```

4. Open http://localhost:5173, **create an account**, then click **New text**, then **Load sample text**,
   then **Save and read** (or **Read without saving**).

The mock provider only knows the words and sentences of the sample text. Any other word answers
"No sample translation for this". Use the Gemini provider for real text.

## Configuration

The backend reads environment variables. With the `local` profile it also loads `backend/.env`;
real environment variables win over the file. All variables are listed in
[backend/.env.example](backend/.env.example).

| Variable               | Required          | Example / default                                | Meaning |
|------------------------|-------------------|--------------------------------------------------|---------|
| `DB_URL`               | yes               | `jdbc:postgresql://localhost:5432/german_reader` | JDBC URL |
| `DB_USERNAME`          | yes               | `german_reader`                                  | |
| `DB_PASSWORD`          | yes               | –                                                | Never commit it |
| `CORS_ALLOWED_ORIGINS` | yes               | `http://localhost:5173`                          | Comma-separated frontend origins |
| `TRANSLATION_PROVIDER` | yes               | `mock` or `gemini`                               | Which provider answers |
| `GEMINI_API_KEY`       | with `gemini`     | –                                                | From https://aistudio.google.com/apikey |
| `GEMINI_MODEL`         | with `gemini`     | a current Flash model                            | Must support structured JSON output |
| `GEMINI_TIMEOUT`       | no                | `15s`                                            | Per-request timeout |
| `GEMINI_VERIFY_MODEL`  | no                | `false`                                          | `true`: check once at startup that `GEMINI_MODEL` exists (one call to Google; result is logged, never stops the app) |
| `JWT_SECRET`           | yes, outside `local` | at least 32 random bytes                      | Signs login tokens. Without it the `local` profile uses a random key (everyone logs in again after a restart); other profiles refuse to start |
| `AUTH_TOKEN_TTL`       | no                | `8h`                                             | How long a login lasts |
| `AUTH_COOKIE_SECURE`   | no                | `true` (`false` in `local`)                      | Send the login cookie over HTTPS only |
| `SERVER_PORT`          | no                | `8080`                                           | |

The `local` profile also provides defaults for `DB_URL`, `DB_USERNAME`, `CORS_ALLOWED_ORIGINS`
(`http://localhost:5173`) and `TRANSLATION_PROVIDER` (`mock`).

The frontend has one optional variable, `VITE_API_BASE_URL` (default `http://localhost:8080`), set in
`frontend/.env.local` (see [frontend/.env](frontend/.env)). Everything prefixed
with `VITE_` ends up in browser code, so never put secrets there.

### Running from IntelliJ

An IntelliJ run configuration does not read `backend/.env` unless the `local` profile is active.
Either set **Active profiles** to `local`, or enter the variables above under **Environment variables**.

### Common problem: "Can't reach the server"

If every word shows "Can't reach the server" while the backend is running, the browser is
probably blocking the request because of CORS (the backend answers `403 Invalid CORS request`).

- Check that `CORS_ALLOWED_ORIGINS` contains the exact address in the browser's address bar:
  scheme, host and port, with no path and no trailing slash.
- `localhost` and `127.0.0.1` are different origins.
- The backend refuses to start when an origin is malformed, or when the variable is missing and
  Spring is left with the literal placeholder `${CORS_ALLOWED_ORIGINS}`.

### Using Gemini

Set `TRANSLATION_PROVIDER=gemini`, `GEMINI_API_KEY` and `GEMINI_MODEL`, then restart the backend.

- The key stays on the server. It is never logged, never returned to the browser, and the
  frontend only ever sees short error codes.
- Mock and Gemini answers are cached separately: the cache namespace is `mock` or
  `gemini:<model>`. Changing the model or the prompt version starts a fresh namespace.
- Only successful, validated answers are cached. Errors and timeouts are not.
- **Startup checks:** the backend refuses to start without a key or with a malformed model name. Spaces or
  quotes pasted around the key or model are removed. Whether the model still exists is only checked
  with `GEMINI_VERIFY_MODEL=true`.

**Retries:** at most one retry per translation, done by the backend (the SDK's own retry is off).

| Gemini answer | Retried? | Code the frontend sees |
|---|---|---|
| 500, 502, 503 | once, after 250–500 ms (exponential backoff with jitter) | `PROVIDER_UNAVAILABLE` |
| 504 | once | `PROVIDER_TIMEOUT` |
| Network error (connection refused or reset, DNS) | once | `PROVIDER_UNAVAILABLE` |
| Our own timeout (`GEMINI_TIMEOUT`) | no: the time budget is already used | `PROVIDER_TIMEOUT` |
| 429 with "retry in N s", N ≤ 3 | once, after N s | `PROVIDER_RATE_LIMITED` |
| 429 with a longer wait (a quota) | no | `PROVIDER_RATE_LIMITED` |
| 429 without a wait | once, with the normal backoff | `PROVIDER_RATE_LIMITED` |
| 404 (model unknown or retired) | no | `PROVIDER_MODEL_UNAVAILABLE` |
| 400, 401, 403 (e.g. invalid key) | no | `PROVIDER_REJECTED` |

The last two are settings problems, so the popup names the variable to fix and offers no "Try again".
The server log has the HTTP status for each failure. Google's message stays in the stack trace and is
never sent to the browser.

## Tests

```bash
cd backend
./mvnw test       # unit and web-slice tests: no database, no Gemini key
./mvnw verify     # also runs *IT integration tests against your local PostgreSQL
```

`./mvnw verify` uses the `local` profile (so it needs `backend/.env` or the DB variables) and the
mock provider. It works in its own schema, `it_test`, and never touches your data or calls Gemini.

```bash
cd frontend
npm test          # Vitest + Testing Library (jsdom), no backend needed
npm run build     # type check + production build
```

## API

Everything except registration, login and logout needs a login (translations included); otherwise the answer is
`401 UNAUTHENTICATED`.

### Accounts

| Method and path | Body | Success |
|---|---|---|
| `POST /api/v1/auth/register` | JSON `{"email": "…", "password": "…"}` | `201`, `{id, email}`, and the login cookie |
| `POST /api/v1/auth/login` | JSON `{"email": "…", "password": "…"}` | `200`, `{id, email}`, and the login cookie |
| `POST /api/v1/auth/logout` | – | `204`, removes the cookie |
| `GET /api/v1/auth/me` | – | `200`, `{id, email}` |

- **Passwords:** 8 to 72 characters, at most 72 bytes (BCrypt's limit). Only the BCrypt hash (strength 12) is stored.
- **Emails:** case-insensitive and unique (`409 EMAIL_TAKEN`).
- **Wrong logins:** a wrong password and an unknown email both get `401 INVALID_CREDENTIALS`.
- **The token:**
  - It's a JWT signed with HS256, containing the user id, issuer, issue time and expiry.
  - It travels only in the `access_token` cookie: `HttpOnly`, `SameSite=Strict`, `Path=/api`, and `Secure` outside `local`.
  - JavaScript never sees it, and it is never logged.
- **CSRF:** POST, PATCH and DELETE also need the CSRF token. The backend sets it in the readable `XSRF-TOKEN`
  cookie, and the frontend echoes it in the `X-XSRF-TOKEN` header. Without it the answer is `403 FORBIDDEN`.
- **Other users' resources:** a text or word of another user answers `404`, exactly like a missing one, so ids
  reveal nothing.

### Translations

`POST /api/v1/translations`

```json
{
  "selectedText": "Bank",
  "context": "Am Morgen sitzt meine Großmutter auf der Bank im Park.",
  "sourceLanguage": "DE",
  "targetLanguage": "EN",
  "translationType": "WORD"
}
```

- `translationType` is `WORD` or `SENTENCE`; `context` is the surrounding sentence, used only for words.
- Limits: a word is at most 100 characters, a sentence or context at most 1000.
- `targetLanguage` is `TR` or `EN`.

A successful answer contains `translation`, `lemma`, `partOfSpeech`, a `grammar` object whose shape
depends on `grammar.kind` (`NOUN`, `VERB`, `OTHER`), and `sentenceTranslation`.

### Reading library (your own texts only)

| Method and path | Body | Success |
|---|---|---|
| `POST /api/v1/documents` | JSON `{"title": "…", "content": "…"}` | `201` + `Location`, the document |
| `POST /api/v1/documents/upload` | `multipart/form-data` with a `file` part | `201` + `Location`, the document |
| `GET /api/v1/documents` | – | `200`, `[{id, title, createdAt, updatedAt}]`, newest first, no content |
| `GET /api/v1/documents/{id}` | – | `200`, `{id, title, content, createdAt, updatedAt}` |
| `PATCH /api/v1/documents/{id}` | JSON `{"title": "…"}` | `200`, `{id, title, createdAt, updatedAt}`: renames only, the content never changes |
| `DELETE /api/v1/documents/{id}` | – | `204` |

Rules:
- **Title and content:** neither can be blank. The title has at most 200 characters and the content at most 100,000. Line breaks are stored as `\n`, and surrounding whitespace is removed.
- **Uploads:**
  - The file name must end in `.txt`, and at most 512 KB is accepted.
  - The bytes must be valid UTF-8, checked strictly: Windows-1252 or Latin-1 files are rejected rather than garbled. A leading BOM is removed.
  - Control characters other than tab and line breaks are rejected, since they mean a binary file. The MIME type the browser sends is ignored.
  - The title is the file name without `.txt`.

### My words (your own words only)

| Method and path | Body | Success |
|---|---|---|
| `POST /api/v1/vocabulary` | JSON, see below | `201` + `Location` for a new entry; `200` with the existing entry when this meaning is already saved |
| `GET /api/v1/vocabulary` | – | `200`, all entries, newest first, each with `sourceDocumentTitle` |
| `DELETE /api/v1/vocabulary/{id}` | – | `204` |

```json
{
  "originalText": "Bank",
  "lemma": "Bank",
  "article": "die",
  "partOfSpeech": "NOUN",
  "translation": "bench",
  "targetLanguage": "EN",
  "sourceSentence": "Am Morgen sitzt meine Großmutter auf der Bank im Park.",
  "sourceDocumentId": "…"
}
```

Rules:
- **No new AI call.** The body is a translation the reader already received from `/api/v1/translations`; saving only stores it.
- **Required fields:** `originalText` (at most 100 characters), `translation` (at most 300) and `targetLanguage`.
  - `lemma` defaults to the word itself.
  - `article` (only nouns have one), `partOfSpeech`, `sourceSentence` (at most 1000 characters) and `sourceDocumentId` may be null.
- **Duplicates:** a meaning is lemma + translation + target language, ignoring case. A unique index enforces this.
  - "Bank → bench" and "Bank → bank" are two entries.
  - Saving "Bank → bench" again, from any sentence, returns the existing entry unchanged.
- **Deleted texts:** deleting a library text keeps its words; their `sourceDocumentId` and `sourceDocumentTitle` become null. A word saved with the id of a text that no longer exists is stored without the link.

Errors are RFC 9457 problem details with a stable `code`:

| Status | `code` |
|--------|--------|
| 400 | `VALIDATION_ERROR`, `MALFORMED_REQUEST`, `REQUEST_ERROR` (e.g. an id that is not a UUID), `INVALID_UPLOAD` |
| 401 | `UNAUTHENTICATED` (no, expired or invalid token), `INVALID_CREDENTIALS` |
| 403 | `FORBIDDEN` (missing or wrong CSRF token) |
| 404 | `MOCK_TRANSLATION_NOT_FOUND`, `DOCUMENT_NOT_FOUND`, `VOCABULARY_NOT_FOUND` (also for other users' resources) |
| 409 | `EMAIL_TAKEN` |
| 413 | `UPLOAD_TOO_LARGE` |
| 502 | `PROVIDER_INVALID_RESPONSE` |
| 503 | `PROVIDER_RATE_LIMITED`, `PROVIDER_UNAVAILABLE`, `PROVIDER_MODEL_UNAVAILABLE`, `PROVIDER_REJECTED` |
| 504 | `PROVIDER_TIMEOUT` |
| 500 | `INTERNAL_ERROR` |

Internal provider messages never reach the client. Document titles and contents, and saved words and
sentences, are never logged.

## How it works

```
Browser ──POST──▶ TranslationController ──▶ TranslationService ──▶ TranslationCacheRepository (PostgreSQL)
                                                   │
                                                   └── on a cache miss ──▶ TranslationProvider (mock | Gemini)
```

### Backend

1. **Controller** — validates the request (`@Valid`) and hands it to the service.
2. **Service** — builds a SHA-256 cache key from the provider namespace, prompt version, type,
   languages, normalized text and, for words, the normalized context. It then looks up the key.
3. **On a miss** — the provider is called with no database transaction open.
   - Identical requests that arrive at the same time share one provider call (single-flight).
   - A failure is passed to every waiting request and is not remembered.
4. **Repository** — stores the answer with `INSERT … ON CONFLICT DO NOTHING`, so two servers racing
   to insert the same key cannot fail.
5. **Exception handler** — turns every error into a problem-details response with a stable code.

The reading library follows the same layers: `DocumentController` → `DocumentService` →
`ReadingDocumentRepository`, with the `reading_document` table (migration `V2`).
- The service checks uploads.
- Reads use read-only transactions.
- Listing selects only the metadata columns, never the content.
- Deleting is a single `DELETE` statement that reports whether a row existed.
- Renaming is a single `UPDATE` of `title` and `updated_at` that never reads the content. The id stays the same, so saved words keep their link.

"My words" works the same way: `VocabularyController` → `VocabularyService` → `VocabularyEntryRepository`, with the
`vocabulary_entry` table (migration `V3`).
- The unique index `ux_vocabulary_entry_meaning` on `(lower(lemma), target_language, lower(translation))` stops duplicates.
- Saving looks up the meaning first. If two saves of the same meaning race, the loser's INSERT fails on the
  index and it returns the winner's entry.
- `source_document_id` is a foreign key with `ON DELETE SET NULL`, so deleting a text keeps its words.

**Accounts and ownership** (`security/`, migration `V4`):
- `SecurityConfig` is stateless. A `BearerTokenAuthenticationFilter` reads the JWT from the cookie and checks its
  signature, issuer and expiry.
- That filter is registered directly, not through `oauth2ResourceServer()`, because the latter switches CSRF off
  for requests that carry a token. That's right for `Authorization` headers, but wrong for a cookie.
- `reading_document` and `vocabulary_entry` have an `owner_id`. Every query the API uses filters by it, and a word
  can only link to its owner's own texts.
- "One entry per meaning" is now per user.
- The translation cache stays shared: it only holds translations of text, and a hit requires sending exactly that text.
- Rows from before accounts existed have `owner_id = NULL`, so no user can see them.

### Frontend

The app first asks the backend who is logged in (`GET /api/v1/auth/me`). Without a session it shows the
**login / create account** form. Every request sends the cookies (`credentials: "include"`), and every change
also sends the CSRF header. If a request answers `401` (for example, the login expired), the app returns to the
login screen.

After logging in, the app opens on the **library**. From there:
- Your email and **Log out** are shown under the heading.
- **New text** opens the paste screen.
- **Upload .txt** adds a file.
- A saved text opens in the reader, whose header button leads back to the library.
- The pencil icon renames a text (Enter saves, Escape cancels).
- **My words** lists saved words. In the reader, a word translation has a **Save to My words** button,
  which shows "Saved" for meanings already in the list.

### Inside the reader

1. **Splitting the text** — `Intl.Segmenter("de")` splits the text into sentences and words.
   - German abbreviations such as "z. B." and "Dr." don't end a sentence.
   - Hyphenated compounds stay one word.
2. **Showing a translation** — a word or sentence opens a popup.
   - On desktop it is a floating card positioned by Floating UI.
   - On phones it is a bottom sheet, and the page scrolls the tapped word above it.
3. **Caching in the browser** — answers are kept for the session, so switching TR ⇄ EN back and forth
   asks the server only once per language.
4. **Keyboard** — the text is one tab stop. Use the arrow keys to move between words and Enter to translate.
5. **Preferences** — theme, language and mode are saved in `localStorage`.
6. **Pages** — texts longer than about 4,000 characters are shown page by page (`paginate.ts`), so the
   browser never renders thousands of word buttons at once.
   - Pages break between paragraphs; a paragraph longer than a page is split between sentences.
   - Words and sentences are never split, so translation context is unchanged.
   - **Previous**, **Next** and a page menu sit below the text. Changing pages moves to the top of the
     new page and focuses its "Page X of N" label.
7. **Reading position** — for saved texts, the first visible sentence is stored in `localStorage`
   (`lesehilfe.position.<documentId>`), at most every 500 ms while scrolling.
   - The anchor is the sentence's number plus the start of its text, not a pixel offset, so it survives
     other screen and font sizes.
   - Reopening the text shows that page and scrolls the sentence into view. An entry that no longer
     matches the text is removed, and reading starts at the top.
   - The position is kept per browser, not on the server. Deleting a text removes its position.
