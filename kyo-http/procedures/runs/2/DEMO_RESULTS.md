# Demo Creation Results — Run 2

## New Demos Implemented

### 1. NotePad (`NotePad.scala`)
- **Server:** CRUD notes with PATCH, cookie-based sessions, SSE live updates
- **Client:** Creates notes, patches them, lists, consumes SSE change feed
- **Gaps filled:** PATCH method, cookie attributes (maxAge/httpOnly/sameSite), `HttpClient.patchJson`, `HttpClient.getSseJson`, `HttpHandler.getJson` convenience, multiple error mappings on one route
- **Verified:** JVM ✓, JS compile ✓, Native compile ✓

### 2. ImageProxy (`ImageProxy.scala`)
- **Server:** POST binary to store, GET to retrieve with content-disposition, deprecated legacy endpoint
- **Client:** Uploads binary, downloads it back, verifies round-trip
- **Gaps filled:** `HttpClient.postBinary`, `HttpClient.getBinary`, custom user-written filter (timing), `noCache`/`noStore`, `contentDisposition`, OpenAPI `deprecated`, OpenAPI `externalDocs`
- **Verified:** JVM ✓, JS compile ✓, Native compile ✓

### 3. ChatRoom (`ChatRoom.scala`)
- **Server:** POST text messages, GET all, SSE text activity feed
- **Client:** Posts messages, lists, consumes SSE text feed
- **Gaps filled:** `HttpHandler.postText`, `HttpHandler.getText` (convenience), `HttpClient.postText`, `HttpClient.getSseText`, server-level CORS (`HttpServer.Config.cors`)
- **Verified:** JVM ✓, JS compile ✓, Native compile ✓

### 4. TaskBoard (`TaskBoard.scala`)
- **Server:** CRUD tasks with columns, multiple typed error channels (400+404+409)
- **Client:** CRUD operations + error recovery with `Abort.run[HttpError]`
- **Gaps filled:** `HttpClient.putJson`, `HttpClient.deleteText`, multiple error mappings on one route (ValidationError + NotFound + ConflictError), OpenAPI `security` metadata, error recovery pattern
- **Verified:** JVM ✓, JS compile ✓, Native compile ✓

## Coverage Gaps Addressed

| Gap | Addressed By |
|-----|-------------|
| PATCH method | NotePad |
| Cookie attributes (maxAge, httpOnly, sameSite) | NotePad |
| `HttpClient.patchJson` | NotePad |
| `HttpClient.getSseJson` | NotePad |
| `HttpClient.getSseText` | ChatRoom |
| `HttpClient.postBinary` / `getBinary` | ImageProxy |
| `HttpClient.putJson` | TaskBoard |
| `HttpClient.postText` | ChatRoom |
| `HttpHandler.postText` / `getText` convenience | ChatRoom |
| Custom user-written filter | ImageProxy |
| Multiple error mappings on one route | NotePad, TaskBoard |
| `noCache` / `noStore` | ImageProxy |
| `contentDisposition` | ImageProxy |
| OpenAPI `deprecated` | ImageProxy |
| OpenAPI `externalDocs` | ImageProxy |
| OpenAPI `security` | TaskBoard |
| Server-level CORS (`HttpServer.Config.cors`) | ChatRoom |
| Error recovery pattern (`Abort.run[HttpError]`) | TaskBoard |

## Remaining Gaps (not addressed)

- Request body: streaming (`bodyStream`), NDJSON input — would need specific use case
- Response body: byte stream (`bodyStream`) — similar
- `HttpClient.let` / `use` / `update` context management — library gap (not wired)
- Client-side filters (`bearerAuth`, `basicAuth`, `addHeader`, `logging`) — library gap (not wired into HttpClient)
- Client lifecycle (`init`/`close`/`closeNow`) — demos use `withConfig` which is the recommended pattern
- Route reuse between server and client — would need typed client API support
- Streaming backpressure — advanced topic, better suited for dedicated guide

## Existing Demo Review

All 16 existing demos were reviewed. No changes needed — all appropriately use raw routes where filters, metadata, or complex logic require them, and convenience methods where applicable.
