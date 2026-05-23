# Finance AI Analyst - minimal frontend

This Vite + React frontend demonstrates the finance research assistant UI for the current backend.

## Prerequisites

- Backend is running locally as `TravelAiApplication` at `http://127.0.0.1:8081`, or the full stack is running with `docker compose`.
- Node.js 18+ is installed.

## Start

```powershell
cd frontend
npm install
npm run dev
```

Open the Vite URL, usually `http://localhost:5173`.

## Notes

- The dev server proxies `/api` to `http://127.0.0.1:8081`.
- The frontend now calls the finance-oriented `/analysis/**` backend aliases through `frontend/src/api.js`.
- Legacy-compatible `/travel/**` backend routes still exist, but the frontend no longer uses them for the main chat, knowledge, profile, or feedback flows.
- Chat uses `POST /analysis/chat/{conversationId}` with JSON body `{"query":"..."}` and `Accept: text/event-stream`.
- Knowledge upload uses `POST /api/knowledge/upload`, form field `file`, and currently supports `.txt`.
- The assistant is a financial research aid. It does not provide investment advice, does not execute trades, and all source material, citations, and tool results require human review.

## Demo Account

The demo account matches backend `SecurityConfig`:

```text
demo / demo123
```
