# Finance AI Analyst Frontend Demo

This document describes the current `frontend` demo. It presents the product as a Finance AI Analyst and uses the preferred `/analysis/**` backend API where aliases are available.

## Page Structure

The frontend is in `frontend`, built with Vite + React + Fetch API. During development, Vite proxies `/api` to `http://127.0.0.1:8081`.

Current UI areas:

- Header: product name, global operation status, and error display.
- Login: demo login with `demo / demo123`, stores the Bearer token, supports logout.
- Knowledge upload: uploads `.txt` research material such as earnings notes, announcements, news, or internal research notes.
- Knowledge list: lists current user's uploaded files and supports deletion for deletable files.
- Chat: sends financial research questions and receives streaming SSE output.
- Agent trace: shows `plan_parse` metadata and `PLAN -> RETRIEVE -> TOOL -> GUARD -> WRITE` stage events.
- Sources: shows structured sources or parsed citation blocks.
- User profile: shows and confirms user profile extraction when enabled.
- Feedback: submits thumb/rating/comment after an answer is complete.

## Interface List

The frontend API is centralized in `frontend/src/api.js`.

The visible product is Finance AI Analyst. New frontend code should use `/analysis/**`; `/finance/**` is the equivalent finance-semantic alias, and `/travel/**` remains available only for legacy-compatible callers.

| Capability | Preferred endpoint |
| --- | --- |
| Login | `POST /auth/login` |
| Create conversation | `POST /analysis/conversations` |
| Upload knowledge | `POST /knowledge/upload` |
| Knowledge list | `GET /analysis/knowledge` |
| Delete knowledge | `DELETE /analysis/knowledge/{fileId}` |
| SSE chat | `POST /analysis/chat/{conversationId}` |
| Current profile | `GET /analysis/profile` |
| Extract profile suggestion | `POST /analysis/profile/extract-suggestion` |
| Read pending profile | `GET /analysis/profile/pending-extraction?conversationId=...` |
| Confirm pending profile | `POST /analysis/profile/confirm-extraction` |
| Discard pending profile | `DELETE /analysis/profile/pending-extraction?conversationId=...` |
| Reset profile | `DELETE /analysis/profile` |
| Submit feedback | `POST /analysis/feedback` |
| Recent feedback | `GET /analysis/feedback?limit=5&offset=0` |

`/travel/**` is legacy/compatibility naming. It is retained for old clients and should not be used by new frontend code.

## Manual Acceptance

1. Start dependencies:

```powershell
docker compose up -d postgres redis
```

2. Run backend `com.travel.ai.TravelAiApplication`, then verify health:

```powershell
Invoke-RestMethod http://localhost:8081/actuator/health
```

3. Start frontend:

```powershell
cd frontend
npm install
npm run dev
```

4. Open `http://localhost:5173` and log in with `demo / demo123`.
5. Confirm the header reads `Finance AI Analyst` / `金融研究分析助手`.
6. Confirm the default query is a financial research question about earnings highlights, risks, and data needing review.
7. Upload a `.txt` file containing financial research material.
8. Send a finance analysis question and confirm the assistant streams a response.
9. Confirm the UI states that output is for research/education only, is not investment advice, does not execute trades, and needs human review.
10. Confirm the browser network requests use preferred routes such as `/analysis/chat/{conversationId}`.

## Known Limits

- This is a demo frontend, not a complete financial research workstation.
- It does not execute trades.
- It does not provide personalized investment advice.
- The backend still exposes legacy-compatible `/travel/**` routes for old clients.
- Knowledge upload currently supports `.txt` only.
- Source material, citations, generated conclusions, and tool results require human review.
