# Proposed New Demos — Run 2

## Gap Analysis Summary

The biggest coverage gaps are:

1. **Client-side SSE/SSE consumption** — `getSseJson`, `getSseText` never demoed
2. **Handler convenience methods** — `HttpHandler.getText`, `postJson`, `putJson`, `deleteJson`, `getBinary`, `postBinary` never used
3. **Request body types** — text input, binary input, streaming request body never demoed
4. **PATCH method** — zero coverage
5. **Cookie attributes** — `maxAge`, `secure`, `httpOnly`, `sameSite` never shown
6. **Custom filters** — no user-written filter demonstrated
7. **Multiple error mappings** — never shown on a single route
8. **Client lifecycle** — `init`/`close`/`closeNow` never explicitly shown
9. **`HttpClient.let`/`use`/`update`** — context management never demoed
10. **Binary client methods** — `getBinary`, `postBinary` never used
11. **OpenAPI `deprecated`/`security`/`externalDocs`** — never used
12. **Server-level CORS** (via `HttpServer.Config.cors`) vs filter-level — never shown
13. **Streaming request body** — never demoed

**Note:** Client-side filters (`HttpFilter.client.*`) exist in the API but are not wired into `HttpClient` — they have no effect. This is a library gap, not a demo gap. We should not demo them until they work.

---

## Proposed Demos

### Demo 1: NotePad — Collaborative Text Editor API

**One-liner:** CRUD text notes with PATCH support, cookie-based sessions, and SSE live updates.

**Scenario:** A simple note-taking API where users create text notes, update them with PATCH, and subscribe to live changes via SSE. A companion client creates notes and watches for changes.

**Gaps filled:**
- `HttpHandler.postJson`, `HttpHandler.getText`, `HttpHandler.deleteJson` (convenience handlers)
- PATCH method (`HttpHandler.patchJson` convenience — or `HttpRoute.patchRaw` if needed)
- Request body: text (`bodyText` on a PUT route for raw note content)
- Cookie attributes (`maxAge`, `httpOnly`, `sameSite` on session cookie)
- `getSseJson` client consumption
- `HttpClient.let` / `use` context management
- Client lifecycle (`init`/`close`)
- Multiple error mappings (404 Not Found + 409 Conflict on same route)

**Key features:** Server + Client (self-server). Client starts server, creates notes via `postJson`, reads via `getText`, patches via `patchJson`, subscribes to SSE changes via `getSseJson`, then closes cleanly.

---

### Demo 2: ImageProxy — Binary Content Pipeline

**One-liner:** Fetch images from URLs, cache them, serve with proper HTTP caching and content-disposition.

**Scenario:** A binary content proxy. POST a URL to fetch and cache an image. GET to retrieve the cached binary. Companion client uploads binary data and downloads it back.

**Gaps filled:**
- `HttpHandler.getBinary`, `HttpHandler.postBinary` (convenience handlers)
- `HttpClient.getBinary`, `HttpClient.postBinary` (client methods)
- Request body: binary (`bodyBinary`)
- `noCache` / `noStore` response methods
- Custom filter (user-written: request timing + custom header injection)
- OpenAPI `deprecated` (on a legacy endpoint)

**Key features:** Server + Client (self-server). Demonstrates the full binary pipeline: client uploads binary, server stores it, client downloads it back. Custom filter adds `X-Processing-Time` header.

---

### Demo 3: ChatRoom — Streaming Request + SSE Response

**One-liner:** Post messages as a text stream, receive room activity as SSE.

**Scenario:** A chat room where clients can POST text messages and subscribe to room activity via SSE. Shows streaming request bodies and SSE response consumption together.

**Gaps filled:**
- Request body: streaming (`bodyStream` for streaming message uploads)
- Response body: byte stream via route builder (`bodyStream`)
- `getSseText` client consumption
- Server-level CORS (via `HttpServer.Config.cors`)
- OpenAPI `security` metadata
- `HttpClient.update` for dynamic config changes

**Key features:** Server + Client (self-server). The SSE consumption gap is critical — no existing demo shows a client consuming SSE from its own server.

---

### Demo 4: TaskBoard — Full CRUD with Rich Error Handling

**One-liner:** Kanban-style task API with multiple error types, OpenAPI documentation, and a typed client.

**Scenario:** A task management API with columns (todo/doing/done), assignment, and rich validation. Shows how typed error channels let the framework return different error shapes for different failure modes.

**Gaps filled:**
- Multiple error mappings on one route (400 ValidationError + 404 NotFound + 409 ConflictError)
- `HttpHandler.putJson` convenience
- OpenAPI `externalDocs`
- OpenAPI `security` metadata
- Rich typed error responses with multiple fields

**Key features:** Server + Client (self-server). Client exercises all CRUD operations and demonstrates typed error recovery. Shows how `.error[ValidationError](BadRequest).error[NotFound](NotFound)` works.

---

## Recommendation

I recommend implementing **Demo 1 (NotePad)** and **Demo 3 (ChatRoom)** first, as they cover the most critical gaps:

- NotePad: PATCH, cookie attributes, convenience handlers, client SSE consumption, client lifecycle, multiple errors
- ChatRoom: streaming request body, SSE text client consumption, server-level CORS, byte stream response

Demo 2 (ImageProxy) and Demo 4 (TaskBoard) are valuable but cover narrower gaps (binary operations, rich error mappings).

All four together would cover 14 of the 16 identified gaps (the remaining two — client-side filters and `HttpClient.update` — depend on library changes).
