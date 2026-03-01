# kyo-http README Analysis

## Module Overview
- Package: `kyo` (all types prefixed with `Http*`)
- Cross-platform: JVM (Netty), JS (Fetch/Node), Native (libcurl/h2o)
- Zero external dependencies for the API layer (backends are platform-specific)
- Built on Kyo's algebraic effect system

## Key Features to Highlight

### 1. Type-Safe Routes with Typed Records
- Routes encode input/output types at compile time via `~` (field) and `&` (composition)
- Path captures, query params, headers, cookies, body — all type-checked
- `req.fields.fieldName` accessor pattern

### 2. Convenience API (HttpHandler)
- One-liner handlers: `HttpHandler.getJson[User]("users") { req => ... }`
- Covers GET/POST/PUT/PATCH/DELETE for JSON, Text, Binary
- Built-in health check endpoint

### 3. Streaming (SSE, NDJSON)
- Server-Sent Events with typed JSON or text data
- NDJSON streaming for both server and client
- Multipart streaming

### 4. Filters (Middleware)
- Composable with `.andThen()`
- Built-in server filters: basicAuth, bearerAuth, rateLimit, cors, securityHeaders, logging, requestId
- Built-in client filters: bearerAuth, basicAuth, addHeader, logging
- Type-safe: filters can add typed fields to request/response

### 5. Client API
- Static convenience: `HttpClient.getJson[User](url)`
- Config: baseUrl, timeout, retries with Schedule, redirect following
- Connection pooling with configurable limits
- Context-based: `HttpClient.let(client)(...)`, `HttpClient.withConfig(...)`

### 6. Server API
- Scoped lifecycle (auto-cleanup) or unscoped (manual)
- Config: port, host, CORS, keepAlive, OpenAPI
- Auto-generated OpenAPI spec from route metadata

### 7. OpenAPI Generation
- Route metadata: summary, description, tags, operationId, security, deprecated
- Auto-serves `/openapi.json` when configured

### 8. Error Handling
- Domain errors mapped to HTTP status codes via route definition
- `HttpResponse.halt()` for short-circuit responses from handlers/filters
- Client-side typed errors: `Abort[HttpError]`
- Retry with configurable schedule and predicate

### 9. Content Types
- JSON (via Schema derivation), Text, Binary, Form, Multipart
- Streaming: ByteStream, NDJSON, SSE, MultipartStream

## README Structure Proposal
1. Introduction (what it is, key value props)
2. Dependency setup (SBT)
3. Quick Start — minimal server + client example
4. Defining Routes (path captures, query params, headers, cookies, body types)
5. Handlers (convenience API, raw handlers)
6. Filters (built-in, composition, custom)
7. Client (convenience methods, config, streaming)
8. Server (init, config, lifecycle)
9. Streaming (SSE, NDJSON)
10. Error Handling (domain errors, halt, client errors, retries)
11. OpenAPI
12. CORS
13. Cross-Platform Support
