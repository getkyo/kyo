# kyo-http README Plan

## Writing Style

Voice: professional and clear. Readable, not sterile, but not casual either.
No analogies or metaphors. Say what things are and how they work.

- Code does the heavy lifting. Prose explains the "why" and connects ideas.
- When a concept is new or surprising (typed fields, filter composition), take the time
  to explain it well. When something is obvious from the code, don't over-explain.
- Vary density. Short sections for simple topics, more breathing room for core ideas.
- Natural transitions between sections are good. Mechanical/formulaic ones aren't.
- Trust the reader. They're a Scala developer.

Guardrails (things that make text feel AI-generated):
- No em dashes. Use parentheticals or start a new sentence.
- Avoid "seamlessly", "robust", "comprehensive", "leverage", "simply", "just".
- Don't start every section with a motivating question or "let's".
- Don't over-signpost ("In this section we'll cover...").
- No fake enthusiasm. No exclamation marks.

## Structure

### 1. Introduction
- One sentence: what kyo-http is. HTTP/1.1 client and server library for Kyo.
- Cross-platform: JVM, JS, Native. Same API everywhere.
- Backend table:

| Platform | Client | Server |
|----------|--------|--------|
| JVM      | Netty  | Netty  |
| JS       | Fetch API | Node.js HTTP |
| Native   | libcurl | H2O |

- Short feature list (not a wall of bullets, just the highlights):
  typed routes, JSON/text/binary, streaming (SSE, NDJSON), middleware,
  connection pooling, retries, auto OpenAPI generation, CORS.

### 2. Getting Started
- SBT dependency line.
- One complete example: define a case class with `derives Schema`, call `HttpClient.getJson`, done.
- This is the reader's first win. Keep it minimal.

### 3. Client
- Show `getJson`, `postJson` (with request body). Two code blocks.
- Mention text and binary variants exist. Don't enumerate them all.
- Note the effect type: `A < (Async & Abort[HttpError])`.

### 4. Client Configuration
- `HttpClient.withConfig` wrapping a block. Show baseUrl + timeout together.
- Retry with Schedule (one example, not an API listing).
- Client-side auth: `HttpFilter.client.bearerAuth(token)` applied to a route.

### 5. Client Streaming
- `getSseJson[V](url)` with a short example consuming events.
- Mention NDJSON. One line, same idea.
- Briefly explain `HttpEvent` fields (data, event, id, retry).

### 6. Server
- Complete example: two handlers + `HttpServer.init`. Runs on a random port.
- Show `HttpHandler.getJson`, `postJson`, `health()`.
- The handler function signatures speak for themselves:
  GET takes `req => A`, POST takes `(req, body) => A`.
- `HttpServer.Config` for port, host. One example with `.port(8080)`.
- `HttpServer.initWith` to access the server instance (e.g. get assigned port).

### 7. Typed Fields
- This is the conceptual section. Keep it tight.
- `"name" ~ Type` is a named field. `&` composes fields.
- `req.fields.name` gives type-safe access. That's the payoff.
- Show a concrete type: `HttpRequest["id" ~ Int & "name" ~ String]`.
- Access: `req.fields.id` is `Int`, `req.fields.name` is `String`.
- Same system for responses: `HttpResponse.ok.addField("body", user)`.

### 8. Path Captures
- `Capture[Int]("id")` produces an `"id" ~ Int` field.
- Path composition with `/`: `"users" / Capture[Int]("id")`.
- Complete handler example with a path capture, showing `req.fields.id`.
- `Rest("path")` for catch-all segments.
- Supported codecs: Int, Long, String, Boolean, Double, Float, UUID.

### 9. Routes
- This is where the full power opens up. Build one route step by step.
- Start with `HttpRoute.getRaw("users" / Capture[Int]("id"))`.
- Add `.request(_.query[Int]("page"))` and `.request(_.headerOpt[String]("authorization"))`.
- Add `.response(_.bodyJson[User])`.
- Add `.response(_.error[NotFound](HttpStatus.NotFound))`.
- Convert to handler with `.handler { req => ... }`.
- One complete example showing it all together.
- Mention other request fields exist (cookie, form, multipart) without listing every method.

### 10. Filters
- Show `HttpFilter.server.logging` applied to a route. Minimal example.
- Composition: `logging.andThen(bearerAuth(...))`.
- List built-in server filters with one-line descriptions (not signatures):
  bearerAuth, basicAuth, rateLimit, cors, securityHeaders, logging, requestId.
- CORS via server config (the simpler path): `HttpServer.Config().cors(HttpCors(...))`.
- Note that basicAuth adds a `"user"` field to the request (type-safe middleware output).

### 11. Streaming
- Server SSE: `HttpHandler.getSseJson[V]("events") { req => stream }`.
- Server NDJSON: `HttpHandler.getNdJson[V]("stream") { req => stream }`.
- Request streaming: mention `.bodyStream`, `.bodyNdjson[V]`, `.bodyMultipartStream` on routes.
- Show both sides (server + client) for SSE in one example if it fits naturally.

### 12. Error Handling
- Server domain errors: route declares `.error[E](status)`, handler uses `Abort.fail(e)`.
- Short-circuit: `HttpResponse.halt(HttpResponse.forbidden)` from handlers or filters.
- Client errors: `Abort[HttpError]` with subtypes listed briefly.
- Response helpers: `HttpResponse.badRequest`, `notFound`, `serverError`, etc.

### 13. OpenAPI
- One line to enable: `.openApi("/openapi.json", "My API", "1.0.0")` on server config.
- Route metadata: `.metadata(_.summary("...").tag("...").operationId("..."))`.
- That's it. Short section.

### 14. Cross-Platform
- Same code compiles for all platforms. Backend is selected automatically.
- Repeat the backend table if useful, or reference the intro.
- Any caveats (native needs libcurl/h2o installed, JS server needs Node.js).
