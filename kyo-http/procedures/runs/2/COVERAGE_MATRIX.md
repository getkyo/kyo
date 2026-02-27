# Feature Coverage Matrix — Run 2

## Existing Demos (16)

| # | Demo | Type | Description |
|---|------|------|-------------|
| 1 | ApiGateway | Server + Client (ext) | Weather/currency aggregator, parallel external API calls |
| 2 | BookmarkClient | Client (self-server) | CRUD client exercising BookmarkStore |
| 3 | BookmarkStore | Server | CRUD API with auth, filters, typed headers/cookies |
| 4 | CryptoTicker | Server + Client (ext+self) | NDJSON price stream, separate client object |
| 5 | EventBus | Server | Event posting (JSON + form), NDJSON stream |
| 6 | FileLocker | Server | Multipart upload, binary download |
| 7 | GithubFeed | Server + Client (ext) | SSE stream of GitHub events |
| 8 | HackerNews | Server + Client (ext) | HN proxy with parallel story fetching |
| 9 | LinkChecker | Client only | Fan-out link checking, error recovery |
| 10 | McpServer | Server + Client (ext) | MCP protocol, JSON-RPC, SSE heartbeat |
| 11 | PasteBin | Server | ETag/304, REST path, basic auth, content-disposition |
| 12 | StaticSite | Server | File serving, ETag, multipart upload, path traversal protection |
| 13 | UptimeMonitor | Server + Client (ext) | SSE health checks, concurrent pings |
| 14 | UrlShortener | Server | 301 redirect, rate limiting, cookies, HEAD |
| 15 | WebhookRelay | Server | SSE text, 202 Accepted, custom CORS headers |
| 16 | WikiSearch | Server + Client (ext) | Wikipedia proxy, query params |

---

## Server-Side Feature Coverage

### Route Definition Styles

| Feature | Demos Using It | Gap? |
|---------|---------------|------|
| `HttpHandler.getJson` (convenience) | CryptoTicker, EventBus, HackerNews, StaticSite, UptimeMonitor | |
| `HttpHandler.getNdJson` (convenience) | CryptoTicker, EventBus | |
| `HttpHandler.getSseJson` (convenience) | GithubFeed, McpServer, UptimeMonitor | |
| `HttpHandler.getSseText` (convenience) | WebhookRelay | |
| `HttpHandler.getText` (convenience) | — | **GAP** |
| `HttpHandler.postJson` (convenience) | — | **GAP** |
| `HttpHandler.putJson` (convenience) | — | **GAP** |
| `HttpHandler.deleteJson` (convenience) | — | **GAP** |
| `HttpHandler.getBinary` (convenience) | — | **GAP** |
| `HttpHandler.postBinary` (convenience) | — | **GAP** |
| `HttpHandler.health` (convenience) | All except McpServer | |
| `HttpRoute.getRaw(...).handler(...)` (builder) | ApiGateway, BookmarkStore, HackerNews, PasteBin, StaticSite, UrlShortener, WebhookRelay, WikiSearch | |
| `HttpRoute.postRaw(...).handler(...)` (builder) | BookmarkStore, EventBus, FileLocker, McpServer, PasteBin, UrlShortener, WebhookRelay | |
| `HttpRoute.putRaw(...).handler(...)` (builder) | BookmarkStore | |
| `HttpRoute.deleteRaw(...).handler(...)` (builder) | BookmarkStore, PasteBin | |
| `HttpRoute.headRaw(...).handler(...)` (builder) | PasteBin, StaticSite, UrlShortener | |
| `HttpRoute.patchRaw(...).handler(...)` (builder) | — | **GAP** |

### Path Captures & Params

| Feature | Demos Using It | Gap? |
|---------|---------------|------|
| Single path capture (`Capture[Int]`) | BookmarkStore, HackerNews | |
| Single path capture (`Capture[String]`) | ApiGateway, UrlShortener, WikiSearch, FileLocker | |
| Multiple path captures | GithubFeed (owner + repo) | |
| Rest/wildcard (`HttpPath.Rest`) | PasteBin, StaticSite | |
| Required query param | HackerNews, WikiSearch | |
| Optional query param (`queryOpt`) | WebhookRelay | |
| Query with default | ApiGateway, HackerNews, WikiSearch | |
| Multiple query params | ApiGateway (budget + currency), HackerNews (q + limit) | |

### Request/Response Body Types

| Feature | Demos Using It | Gap? |
|---------|---------------|------|
| Request: JSON (`bodyJson`) | BookmarkStore, EventBus, McpServer, PasteBin, UrlShortener, WebhookRelay | |
| Request: Form (`bodyForm`) | EventBus | |
| Request: Multipart (`bodyMultipart`) | FileLocker, StaticSite | |
| Request: Text (`bodyText`) | — | **GAP** |
| Request: Binary (`bodyBinary`) | — | **GAP** |
| Request: Stream (`bodyStream`) | — | **GAP** |
| Request: NDJSON (`bodyNdjson`) | — | **GAP** |
| Request: Multipart Stream (`bodyMultipartStream`) | — | **GAP** |
| Response: JSON (`bodyJson`) | Most demos | |
| Response: Text (`bodyText`) | PasteBin, StaticSite | |
| Response: Binary (`bodyBinary`) | FileLocker | |
| Response: SSE JSON (`bodySseJson`) | GithubFeed | |
| Response: SSE Text (`bodySseText`) | — (WebhookRelay uses convenience) | |
| Response: NDJSON (`bodyNdjson`) | — (CryptoTicker, EventBus use convenience) | |
| Response: Byte Stream (`bodyStream`) | — | **GAP** |

### Headers, Cookies, Status

| Feature | Demos Using It | Gap? |
|---------|---------------|------|
| Response header (typed) | BookmarkStore (`X-Total-Count`) | |
| Response header (via `setHeader`) | UrlShortener, WebhookRelay | |
| Request header (typed, `headerOpt`) | BookmarkStore, PasteBin | |
| Request cookie (`cookieOpt`) | UrlShortener | |
| Response cookie | BookmarkStore, UrlShortener | |
| Cookie with attributes (maxAge, secure, etc.) | — | **GAP** |
| Status 201 Created | BookmarkStore, EventBus, FileLocker, PasteBin, StaticSite, UrlShortener | |
| Status 202 Accepted | WebhookRelay | |
| Status 204 NoContent | BookmarkStore | |
| Status 301 MovedPermanently | UrlShortener | |
| Status 304 NotModified | PasteBin, StaticSite | |
| Status 403 Forbidden | StaticSite | |
| `HttpResponse.halt` | PasteBin, StaticSite, UrlShortener | |
| `etag` / `cacheControl` / `contentDisposition` | PasteBin, StaticSite | |
| `noCache` / `noStore` | — | **GAP** |

### Error Handling

| Feature | Demos Using It | Gap? |
|---------|---------------|------|
| `.error[E](status)` (typed error mapping) | ApiGateway, BookmarkStore, EventBus, FileLocker, HackerNews, UrlShortener, WebhookRelay, WikiSearch | |
| Multiple error mappings on one route | — | **GAP** |
| Custom error types with rich fields | ApiGateway (error + detail) | |

### Filters

| Feature | Demos Using It | Gap? |
|---------|---------------|------|
| `logging` | Most demos | |
| `securityHeaders` (default) | BookmarkStore, UrlShortener | |
| `securityHeaders` (custom hsts/csp) | PasteBin, StaticSite, WebhookRelay | |
| `bearerAuth` | BookmarkStore | |
| `basicAuth` | PasteBin | |
| `rateLimit` | UrlShortener | |
| `cors()` (default) | ApiGateway, FileLocker, McpServer, StaticSite | |
| `cors(custom)` | WebhookRelay | |
| `requestId` | BookmarkStore, UrlShortener | |
| Filter composition (`andThen`) | BookmarkStore, UrlShortener, PasteBin, WebhookRelay | |
| Per-route vs server-level filter | BookmarkStore (auth on write routes only) | |
| Custom filter (user-defined) | — | **GAP** |

### OpenAPI

| Feature | Demos Using It | Gap? |
|---------|---------------|------|
| `openApi(path, title)` | Most demos | |
| `summary` | Most demos | |
| `description` | PasteBin, WebhookRelay | |
| `operationId` | PasteBin, WebhookRelay | |
| `tag` | Most demos | |
| `deprecated` | — | **GAP** |
| `security` | — | **GAP** |
| `externalDocs` | — | **GAP** |

### Server Config

| Feature | Demos Using It | Gap? |
|---------|---------------|------|
| `port(0)` (random) | All | |
| `maxContentLength` | FileLocker, StaticSite | |
| `openApi` | Most demos | |
| `cors(CorsConfig)` (server-level) | — | **GAP** (all use filter-level CORS) |
| `host` | — | |
| `backlog` | — | |
| `keepAlive` | — | |

---

## Client-Side Feature Coverage

| Feature | Demos Using It | Gap? |
|---------|---------------|------|
| `getJson` | ApiGateway, BookmarkClient, CryptoTicker, HackerNews, McpServer, WikiSearch | |
| `postJson` | BookmarkClient | |
| `putJson` | BookmarkClient | |
| `deleteText` | BookmarkClient | |
| `getText` | LinkChecker, UptimeMonitor | |
| `patchJson` | — | **GAP** |
| `deleteJson` | — | **GAP** |
| `postText` / `putText` / `patchText` | — | **GAP** |
| `getBinary` / `postBinary` / `putBinary` | — | **GAP** |
| `getNdJson` | CryptoTickerClient | |
| `getSseJson` | — | **GAP** |
| `getSseText` | — | **GAP** |
| `withConfig(_.baseUrl(...))` | BookmarkClient, CryptoTicker | |
| `withConfig(_.timeout(...))` | ApiGateway, CryptoTicker, HackerNews, LinkChecker, UptimeMonitor, WikiSearch | |
| `withConfig(_.connectTimeout(...))` | CryptoTicker, CryptoTickerClient | |
| `withConfig(_.followRedirects(...))` | LinkChecker, UptimeMonitor | |
| `withConfig(_.retry(...))` | BookmarkClient, CryptoTicker | |
| `withConfig(_.retryOn(...))` | CryptoTicker | |
| `withConfig(_.maxRedirects(...))` | — | **GAP** |
| `HttpClient.let / use / update` | — | **GAP** |
| Client-side filter: `bearerAuth` | — | **GAP** |
| Client-side filter: `basicAuth` | — | **GAP** |
| Client-side filter: `addHeader` | — | **GAP** |
| Client-side filter: `logging` | — | **GAP** |
| `init` with Scope (lifecycle) | — | **GAP** |
| `initUnscoped` | — | **GAP** |
| `close` / `closeNow` | — | **GAP** |
| Error handling (`Abort.run[HttpError]`) | LinkChecker, UptimeMonitor, CryptoTicker | |

---

## Cross-Cutting Feature Coverage

| Feature | Demos Using It | Gap? |
|---------|---------------|------|
| Client talks to own server | BookmarkClient, CryptoTickerClient | |
| Route reuse (server defines, client uses same route) | — | **GAP** |
| Streaming backpressure | — | **GAP** |
| Concurrent requests (fan-out) | HackerNews, LinkChecker, UptimeMonitor | |
| `addField` / `addHeader` / `setHeader` on responses | BookmarkStore, UrlShortener, WebhookRelay, PasteBin | |

---

## Summary of Gaps

### Critical Gaps (zero coverage)

**Client-side:**
1. Client-side filters (`bearerAuth`, `basicAuth`, `addHeader`, `logging`) — no demo uses them
2. `HttpClient.let` / `use` / `update` context management — never demonstrated
3. `getSseJson` / `getSseText` client consumption — never demonstrated
4. `patchJson` client method — never used
5. Binary client operations (`getBinary`, `postBinary`) — never used
6. Client lifecycle (`init`, `close`, `closeNow`) — never shown explicitly

**Server-side:**
7. Request body: text, binary, stream, NDJSON — no demo accepts these as input
8. Response body: byte stream — never used
9. Handler convenience methods: `getText`, `postJson`, `putJson`, `deleteJson`, `getBinary`, `postBinary` — never used
10. PATCH method — no demo uses it
11. Cookie attributes (maxAge, secure, httpOnly, sameSite) — never demonstrated
12. Custom filter (user-written) — no demo writes one
13. Multiple error mappings on one route — never shown
14. OpenAPI `deprecated`, `security`, `externalDocs` — never used

**Cross-cutting:**
15. Route reuse between server and client (shared route definition) — never demonstrated
16. Streaming backpressure — never demonstrated
