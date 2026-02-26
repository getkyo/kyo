# Procedure 1: Validate Demos

Run demo applications on a target platform. Your job is to break things — find bugs, edge cases, and inconsistencies that the developer didn't anticipate. Output feeds into [Procedure 2: Plan Test Coverage](2-PLAN_TEST_COVERAGE.md).

## Setup

### Choosing a platform

Validate one platform per run. All demos should work on all platforms — a demo that doesn't is a bug.

- **JVM** — Netty backend. `sbt 'kyo-http/runMain demo.XXX'`
- **JS / Node.js** — Requires `scalaJSUseMainModuleInitializer := true` and `Compile / mainClass` in jsSettings. Run with `sbt 'kyo-httpJS/run'`.
- **Scala Native** — H2o + libcurl backends. Requires libh2o-evloop and libcurl via pkg-config. Set `Compile / mainClass` in nativeSettings. Run with `sbt 'kyo-httpNative/run'`.

For JS and Native, sbt's `runMain` doesn't work with cross-projects — set the main class in build.sbt temporarily:

**For JS**, add both settings to the `.jsSettings(...)` block:
```scala
.jsSettings(
    `js-settings`,
    // ... existing settings ...
    scalaJSUseMainModuleInitializer := true,  // required for JS
    Compile / mainClass := Some("demo.XXX")
)
```

**For Native**, add the main class to the `.nativeSettings(...)` block:
```scala
.nativeSettings(
    `native-settings`,
    Compile / mainClass := Some("demo.XXX"),
    // ... existing settings ...
)
```

Revert these build.sbt changes when validation is complete.

### Port management

- **Before starting a demo**, check if its port is free: `lsof -i :<port>`
- **Kill stale processes** from previous runs. Demos run via `sbt run` leave processes behind. Check with `ps aux | grep kyo-http`.
- **If a demo crashes on startup with a port error**, the error message quality itself is worth recording.

## Mindset

You are QA, not a developer confirming their own work. Your goal is to find problems. Assume every feature has an edge case that breaks. Assume every error path was never tested. Assume every platform behaves slightly differently.

**Don't verify that things work — try to make them fail.**

A report full of "PASS" with basic curl calls means you didn't try hard enough. Real QA finds bugs. If you find zero bugs, be suspicious of your own testing.

## Read first, then attack

Before testing any demo, read its source code in `kyo-http/shared/src/main/scala/demo/`. Map out:

- Every route and what HTTP method it uses
- Every input type (JSON body, form data, query params, path captures)
- Every output type (JSON, text, binary, SSE, NDJSON)
- Every error type and its declared status mapping
- Every filter (CORS, auth, logging, security headers)
- The OpenAPI configuration (if any)
- Any shared state or concurrency patterns

This map is your attack surface.

## Attack strategies

### 1. Protocol abuse

HTTP servers must handle garbage gracefully. Don't just send well-formed requests.

- **Malformed requests** — use `nc` or `printf ... | nc` to send raw HTTP: missing headers, wrong Content-Length, truncated bodies, HTTP/0.9, invalid method names, headers with no colon, duplicate Content-Type headers.
- **Method mismatches** — send POST to a GET endpoint, DELETE to a POST endpoint, PATCH to everything. Verify 405 responses include an `Allow` header listing valid methods.
- **HEAD requests** — every GET endpoint should respond to HEAD with same headers but empty body. Check Content-Length matches what GET would return. This is a common failure.
- **OPTIONS/CORS** — send preflight requests. Check Access-Control-Allow-Origin, Allow-Methods, Allow-Headers. Try with and without Origin header.
- **Trailing slashes** — `/path` vs `/path/`. Does one 404 while the other works? Is this consistent?
- **URL encoding** — path captures with `%20`, `%2F`, `+`, unicode. Query params with `&` in values, `=` in values, empty values, missing values.
- **Huge inputs** — 10MB body on an endpoint expecting a small JSON. URL with 10K query string. 1000 headers. What errors do you get? Are they useful?

### 2. Serialization attack

Every type boundary is a place where data can be garbled or rejected poorly.

- **Malformed JSON** — missing fields, extra fields, wrong types (string where int expected), null where required, empty object, empty array, deeply nested, unicode escapes.
- **Empty bodies** — POST with Content-Length: 0 to an endpoint expecting JSON. POST with no Content-Type.
- **Wrong Content-Type** — send `text/plain` body to a JSON endpoint. Send `application/xml` to a form endpoint.
- **Form data edge cases** — url-encoded with special chars, missing required fields, extra fields, duplicate keys.
- **Response validation** — is every JSON response actually valid JSON? Use `jq .` to verify. Check for trailing commas, unescaped strings, wrong number formats.

### 3. Streaming attack

Streaming endpoints have timing-dependent bugs that basic tests miss.

- **Immediate disconnect** — connect to SSE endpoint, read headers, close immediately. Does the server crash? Leak resources?
- **Slow consumer** — connect to SSE/NDJSON, read one event, sleep 30 seconds, read again. Does backpressure work or does the server buffer infinitely?
- **Event format** — for SSE, verify wire format: `event:`, `data:`, `id:`, `retry:` fields, double-newline terminators. For NDJSON, verify one JSON object per line, newline-terminated.
- **First-byte timing** — does the server send headers immediately on connection, or buffer until the first data chunk? Use `curl -v --no-buffer` and watch when headers arrive.
- **Empty stream** — an endpoint that produces zero events. Does it hang? Return empty 200? Send headers then close?

### 4. Error path attack

Error handling is where most bugs hide because developers test the happy path.

- **Error response bodies** — every 4xx and 5xx response should have a useful body (JSON with status and error message). An empty error body is a bug — it makes debugging impossible.
- **Error response headers** — error responses should have `Content-Type: application/json` if they return JSON bodies. Check this.
- **Handler exceptions** — if a handler throws, the server should return 500 with a body, not hang or crash.
- **Double errors** — trigger an error, then immediately send a normal request. Does the server recover?
- **Concurrent errors** — fire 10 requests that will all fail simultaneously. Does the server handle this cleanly?
- **Error in streaming** — what happens if a streaming endpoint errors after sending some events? Does the stream end cleanly?

### 5. Concurrency attack

Race conditions only show up under load.

- **Parallel requests** — `for i in $(seq 1 20); do curl ... & done; wait`. Check all responses are correct and complete.
- **Stateful endpoints** — if a demo has shared state (counters, stores), hit it from multiple clients. Look for lost updates, inconsistent reads.
- **Connection reuse** — send multiple sequential requests on the same connection (HTTP keep-alive). Does the second request work correctly? Are headers from the first request leaking into the second?

### 6. OpenAPI/metadata attack

Generated specs should match reality.

- **Fetch `/openapi.json`** — verify Content-Type is `application/json`. Parse it with `jq`. Check every route in the source is present in the spec.
- **Parameter accuracy** — check that path params, query params, and body schemas in the OpenAPI spec match the actual route definitions.
- **Try undocumented paths** — does the server expose any routes not in the OpenAPI spec?

## Recording results

Create a run folder: `kyo-http/procedures/runs/<platform>-<N>/` (e.g., `native-1/`). Write results to `VALIDATION.md` inside that folder.

**Update the file as you go — do not wait until the end.** After testing each demo, immediately write its results before moving on:
- Context is freshest right after testing
- If the session is interrupted, partial results are preserved
- It forces you to articulate what you actually tested while you can still re-run a command

The report must be **auditable**. Include the exact commands, full unedited responses, reasoning about what to test and why, and what was observed. A report that just says "PASS" with a couple of curl calls is not useful.

For each demo, record:

- **Status:** PASS / PARTIAL FAIL / FAIL
- **Attack surface** — what you identified from reading the source
- **What you tried** — every command and its verbatim output
- **What broke** — bugs found with full detail (see below)
- **What survived** — things that worked correctly under stress
- **What you didn't try** — be honest about gaps

### Bug reporting

For each bug:

- **Exact symptom** — what happened vs what was expected
- **Reproduction steps** — the exact command
- **Full error output** — stack traces, HTTP responses, server logs
- **Severity** — does it block a framework capability or is it cosmetic?
- **Classification** — demo bug (demo code is wrong) or code bug (framework/library is wrong). A demo using platform-specific APIs is a demo bug. A backend that doesn't flush headers is a code bug.
- **Platform specificity** — does it only happen on one platform? If so, what works differently on other platforms?
- **Root cause hypothesis** — point to the likely code location if you can
- **Workaround** — if there is one

Also capture **usability issues** that aren't bugs but hurt developer experience:
- Unhelpful error messages (what context is missing?)
- Missing error bodies on failure responses (makes debugging impossible)
- Platform-specific limitations that aren't documented
- APIs that are confusing or have surprising behavior
- Configuration that is error-prone (e.g., hardcoded ports causing silent failures)

## Cross-platform comparison

After validating multiple platforms, compare results across runs and produce a comparison highlighting:

- Which demos work on which platforms and which don't (and why)
- Framework features that are platform-specific (e.g., streaming works on one platform but not another)
- java.* API availability differences across platforms
- Response differences for the same request across platforms (should be identical — any diff is a finding)
- Performance differences (compilation time, response latency, startup time)

## Output

Written to `kyo-http/procedures/runs/<platform>-<N>/VALIDATION.md`:
- Per-demo results with commands, responses, and findings
- Bugs classified as demo bugs or code bugs
- Cross-platform observations (if comparing against a previous run on another platform)

This output is the input to [Procedure 2: Plan Test Coverage](2-PLAN_TEST_COVERAGE.md).
