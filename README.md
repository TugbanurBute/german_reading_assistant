# German Reading Assistant

Read German text and see context-aware Turkish or English translations of words and sentences without leaving the page.

> Status: MVP v0.1 in progress — Phase 1 (project scaffold). A complete setup guide is written in the final phase.

## Layout

```
backend/   Spring Boot 4.1 (Java 21, Maven Wrapper), PostgreSQL, Flyway
frontend/  React 19 + TypeScript + Vite 8 + Tailwind CSS 4
```

## Prerequisites

- Java 21
- Node.js 22.12+ (frontend)
- PostgreSQL 15+ (needed from Phase 2 onwards to run the backend; not needed for backend tests)

Maven is not required — use the bundled wrapper (`mvnw` / `mvnw.cmd`).

## Backend

```bash
cd backend
cp .env.example .env      # then edit DB_PASSWORD etc.
./mvnw test               # unit tests; no database or Gemini key needed
./mvnw verify             # + *IT integration tests against the local PostgreSQL (schema it_test, mock provider)
./mvnw spring-boot:run    # starts on :8080 with the "local" profile (needs PostgreSQL)
```

Configuration is read from environment variables; with the `local` profile, `backend/.env` is also loaded.
See [backend/.env.example](backend/.env.example) for every variable.

## Frontend

```bash
cd frontend
npm install
npm run dev               # http://localhost:5173
npm test
```

Optional overrides go in `frontend/.env.local` (see [frontend/.env.example](frontend/.env.example)).
