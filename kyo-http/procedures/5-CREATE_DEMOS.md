# Procedure 5: Create Demos

> **Before starting:** Read [0-READ_THIS_FIRST.md](0-READ_THIS_FIRST.md), especially the **Bash command rules**.

Demos are the showcase of the library. They should look like code a developer would actually write — clean, idiomatic, and using the best available APIs. Each demo should make someone think "I want to use this library."

## Principles

- **Demos are marketing.** Every line of code is an advertisement for the library's API. Use the highest-level, most ergonomic APIs available. If there's a convenience method, use it. If there's a builder pattern, use it. Never drop down to raw/low-level APIs unless the demo specifically showcases that capability.
- **Real-world scenarios.** Demos should solve problems developers actually face. Not toy examples, not contrived scenarios — real use cases that someone could adapt for their own project.
- **Both sides of the wire.** The library has a client and a server. Demos should cover both. A server-only demo misses half the library. A client that talks to an external API is good; a client that talks to its own server is better.
- **Be creative.** The best demos are ones that make people say "oh, that's clever." Think about interesting combinations of features, not just feature checklists.

## Process

### Step 1: Map feature coverage

Read every existing demo in `kyo-http/shared/src/main/scala/demo/`. Build a feature coverage matrix: features on one axis, demos on the other. Mark which demos exercise which features.

**Feature categories to map:**

Server-side:
- Route definition styles (raw, JSON, text, binary convenience methods vs explicit `.request()/.response()` builders)
- Path captures (single, multiple, rest/wildcard)
- Query parameters (required, optional, multiple)
- Request headers (typed, optional)
- Request cookies
- Request body types (JSON, text, binary, form, multipart, multipart stream)
- Response body types (JSON, text, binary, SSE JSON, SSE text, NDJSON, byte stream)
- Response headers (typed, optional)
- Response cookies (with attributes: maxAge, domain, path, secure, httpOnly, sameSite)
- Status codes (success variants, redirects, client errors, server errors)
- Error mappings (`.error[E](status)` with typed error responses)
- Filters (auth, rate limiting, CORS, security headers, logging, request ID, custom)
- Filter composition (chaining multiple filters, per-route vs server-level)
- OpenAPI metadata (tags, summary, description, operationId, deprecated, security)
- `HttpResponse.halt` for short-circuit responses (304, 401, 403)
- Cache control (etag, cacheControl, noCache, noStore, contentDisposition)
- Server config options (port, host, maxContentLength, backlog, keepAlive, CORS config, OpenAPI config)

Client-side:
- Convenience methods (getJson, postJson, putJson, patchJson, deleteJson, getText, postText, etc.)
- Streaming consumption (getSseJson, getSseText, getNdJson)
- Client config (baseUrl, timeout, connectTimeout, followRedirects, maxRedirects, retry with Schedule, retryOn)
- Client context (HttpClient.let, HttpClient.use, HttpClient.withConfig)
- Client-side filters (bearerAuth, basicAuth, addHeader, logging)
- Binary operations (getBinary, postBinary)
- Error handling (Abort[HttpError], typed error recovery)
- Connection management (init with Scope, initUnscoped, close, closeNow)

Cross-cutting:
- Streaming backpressure (large streams, slow consumers)
- Concurrent requests (parallel fetches, fan-out/fan-in)
- Route + client integration (server defines route, client uses same route to call it)
- Request/response field composition (addField, addHeader, setHeader)

**Output:** A coverage matrix showing gaps. Write to the run folder.

### Step 2: Identify gaps and design new demos

From the coverage matrix, identify the most significant gaps. Prioritize:

1. **Features with zero demo coverage** — these are invisible to users
2. **Client-side features** — the client API is as important as the server API but historically under-demoed
3. **Feature combinations** — individual features may be covered but interesting combinations are not
4. **Advanced patterns** — streaming backpressure, filter composition, typed error channels, route reuse between client and server

For each gap, design a demo that covers it naturally. A single demo should cover multiple gaps when they fit together organically — don't create a demo just to check a box. Each demo should tell a story.

**Demo design checklist:**
- Does it solve a real problem someone would actually have?
- Does it showcase the library's strengths (type safety, composability, cross-platform)?
- Does it use both client and server where possible?
- Does it cover gaps from the coverage matrix without feeling forced?
- Is it interesting enough that someone would read the code voluntarily?
- Can it run standalone with no external dependencies (or with freely available public APIs)?

**Output:** A list of proposed demos with:
- Name and one-line description
- The real-world scenario it models
- Which gaps from the coverage matrix it fills
- Key features it showcases
- Whether it uses client, server, or both

Present the list for approval before implementing.

### Step 3: Implement demos

For each approved demo:

1. **Write clean, idiomatic code.** The demo IS the documentation. Every line matters.
   - Use the highest-level API available. If `HttpHandler.getJson` does what you need, don't use `HttpRoute.getRaw(...).request(...).response(...).handler(...)`.
   - But when the demo specifically showcases route composition, filters, or typed errors, use the full builder API — that's the point.
   - Use `derives Schema` for case classes. Use meaningful field names.
   - Keep handlers focused. One handler, one responsibility.
   - Use Kyo idioms: `Maybe` not `Option`, `Result` not `Either`, `Chunk` not `Seq`, `.map` chains not `.flatMap`, `discard()` for unused values.

2. **Make it runnable.** Each demo should:
   - Print clear startup instructions (URL, example curl commands)
   - Work on all three platforms (JVM, JS, Native) — no platform-specific APIs in shared code
   - Use port 0 (random) to avoid conflicts
   - Clean up resources properly

3. **Test on all platforms.** Run each demo on JVM, JS, and Native. Verify:
   - It compiles
   - It starts without errors
   - The example curl commands work
   - Client-side demos produce expected output
   - No resource leaks (ports released after shutdown)

### Step 4: Review existing demos

While creating new demos, review the existing ones against the same quality bar. If existing demos:
- Use low-level APIs where convenience methods exist → simplify them
- Have dead code, unused imports, or unnecessary complexity → clean them up
- Don't print startup instructions → add them
- Have inconsistent style → normalize them

Do NOT change demo behavior or features — only improve code quality and API usage. Present changes for approval.

## Code quality standards

Demos represent the library's best face. Apply these standards rigorously:

- **Naming**: Case classes should have domain-meaningful names (`Bookmark`, `Paste`, `Ticket`), not generic ones (`Item`, `Data`, `Payload`).
- **Comments**: No comments explaining what the code does — the code should be self-evident. Comments only for why (e.g., "GitHub rate-limits unauthenticated requests to 60/hour").
- **Imports**: Clean imports, no wildcards except `kyo.*`.
- **Structure**: Models at the top, routes/handlers in the middle, server setup at the bottom. Main method last.
- **Error handling**: Use typed error channels (`.error[E](status)`) for domain errors. Use `HttpResponse.halt` for HTTP-level short-circuits (auth failures, not-modified). Don't catch and swallow errors silently.
- **Configuration**: Use `HttpServer.Config` builder for non-default settings. Don't hardcode ports (use 0).
- **Filters**: Apply filters at the appropriate level — server-wide for cross-cutting concerns (logging, security headers), per-route for endpoint-specific concerns (auth, rate limiting).

## Output

- Coverage matrix (step 1)
- Proposed demo list with gap analysis (step 2)
- Implemented demos in `kyo-http/shared/src/main/scala/demo/` (step 3)
- Improved existing demos if applicable (step 4)
- Verification notes confirming all demos run on all platforms
