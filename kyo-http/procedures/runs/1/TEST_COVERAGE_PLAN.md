# Test Coverage Plan — Run 1

## Step 1: All Findings

### Code Bugs (focus of test coverage)

1. **Reason phrase always "OK" on Native** — h2o backend returns "OK" as reason phrase for all status codes (201→"201 OK", 404→"404 OK", etc.). Platform: Native. Demo: BookmarkStore, StaticSite. Classification: Framework bug in h2o backend.

2. **HttpClient error responses lose typed error body** — When server returns 409 with `{"error":"Task 'Buy milk' already exists","existingId":1}`, client catches `HttpError.ParseError` instead of making the error body accessible. The original error JSON is lost. Platform: All. Demo: TaskBoardClient. Classification: Framework bug in HttpClient error handling.

3. **Netty shutdown warning on client demos** — All client demos that call `server.closeNow` produce `RejectedExecutionException: event executor terminated` in stderr. Platform: JVM. Demo: All client demos. Classification: Framework bug (cosmetic).

### Demo Bugs (need demo fixes, not tests)

4. **BookmarkClient crashes — auth filter blocks client requests** — Client starts BookmarkStore server (with auth filters) but sends requests without Authorization header. Server returns 401, client tries to parse as Bookmark, fails. File: `demo/BookmarkStore.scala` (BookmarkClient object, ~line 147+). Fix: Either remove auth filter from the server started by the client, or add a note that this demo is blocked until HttpClient supports custom headers per request.

5. **ApiGateway /weather/unknown returns 500 instead of 404** — Handler uses `Abort.fail(HttpError.ConnectionError(...))` for unknown cities instead of `Abort.fail(ApiError(...))`. Framework catches ConnectionError as unhandled → 500. File: `demo/ApiGateway.scala`, the weather handler. Fix: Change the handler to `Abort.fail(ApiError("Unknown city", "..."))` so the declared `.error[ApiError](HttpStatus.NotFound)` mapping kicks in.

### Usability Observations (not bugs, no tests needed)

6. **400 responses lack detail** — `{"status":400,"error":"BadRequest"}` with no field-level info.
7. **500 responses lose error context** — original error message swallowed in generic 500 response.
8. **JS requires `clean` after changing mainClass** — linker caches previous main class.

---

## Step 2: Categories of Behavior

### Category A: Status Code Reason Phrases (Bug #1)

**What it reveals:** No test verifies the reason phrase portion of HTTP responses. Tests check `status == 404` but never inspect the reason phrase string.

**Assumptions challenged:**
- Existing tests only check numeric status codes, assuming reason phrases are always correct
- No cross-platform assertion on reason phrase text

**Other untested scenarios:** Custom status codes with custom reason phrases, all standard status codes returning correct phrases.

### Category B: Client-Side Error Response Handling (Bug #2)

**What it reveals:** When the server returns a non-2xx response with a typed error body, `HttpClient` convenience methods (postJson, putJson, etc.) try to parse the response as the success type, fail, and throw a `ParseError` that discards the original response body. There's no way for the client to access the error response body.

**Assumptions challenged:**
- Client tests may only test happy-path responses
- Error scenarios in client tests may not verify that the error body content is accessible
- Tests may not exercise the full round-trip: server returns typed error → client receives and can read it

**Other untested scenarios:** Client receiving 400 with JSON error body, client receiving 404 with JSON error body, client receiving 500 with error body, client receiving non-JSON error body on a JSON endpoint.

### Category C: Server Shutdown Lifecycle (Bug #3)

**What it reveals:** `server.closeNow` on Netty produces RejectedExecutionException warnings. No test verifies clean shutdown behavior.

**Assumptions challenged:**
- Tests use `withServer` which handles lifecycle automatically — never tests explicit shutdown
- No assertion that shutdown completes without warnings/errors

**Other untested scenarios:** Shutdown while requests are in-flight, shutdown while SSE streams are active, graceful shutdown vs force shutdown.

---

## Step 3: Audit of Existing Tests

### Category A: Status Code Reason Phrases

**Existing tests:**
- `HttpStatusTest.scala:207-225` — "reason phrases" section: only checks that standard codes resolve to named enum values (not `Custom`). Does NOT check the actual reason phrase string — `HttpStatus` has no `reason` field. The comment at line 204 explicitly notes: "reason phrases are set by the backend."
- `HttpClientTest.scala:582-615` — "error responses": checks `resp.status == HttpStatus.NotFound`, `resp.status == HttpStatus.MethodNotAllowed`, `resp.status == HttpStatus.Forbidden`. Never inspects reason phrase text.
- `HttpServerTest.scala` — All response assertions use `resp.status == HttpStatus.X` pattern. No reason phrase text assertions anywhere.

**Gap:** Zero tests check the reason phrase string. The `HttpStatus` type doesn't even expose a reason phrase — it's purely a backend concern. Testing this requires a raw HTTP response parse or backend-specific test. This is a Native-only bug; shared tests can't reproduce it. A Native-specific integration test would be needed, or the h2o backend code must be fixed and the fix verified by inspection.

**Verdict:** This bug is best fixed directly in the h2o backend rather than tested in shared tests. The fix is straightforward (map status code → standard reason phrase). No new shared test needed.

### Category B: Client-Side Error Response Handling

**Existing tests:**
- `HttpClientTest.scala:582-615` — "error responses" section: Tests 404 (unknown path), 405 (wrong method), and status code propagation. All use `sendWith` (raw API) and only assert `resp.status`. Never test convenience methods with error responses.
- `HttpClientTest.scala:1056-1083` — "works after error responses": Sends requests that return 500, then checks recovery. Uses `sendWith` raw API, asserts `r.status == HttpStatus.InternalServerError`. Never checks error body content.
- `HttpServerTest.scala:1525-1566` — "getJson, postJson, putJson, deleteText convenience methods": Only tests happy paths. All server handlers return 200. No test for what happens when convenience methods receive non-2xx responses.
- `HttpServerTest.scala:1568-1585` — "getSseJson streaming convenience method": Happy path only.

**Gap:** No test exercises convenience methods (postJson, getJson, etc.) when the server returns an error status with a typed error body. The bug is: convenience methods try to deserialize the error body as the success type, fail with ParseError, and the original error body is lost. A test should:
1. Set up a server that returns 409 with a JSON error body
2. Call `HttpClient.postJson[SuccessType, RequestType](...)`
3. Assert that the resulting error preserves the original error status code and body

### Category C: Server Shutdown Lifecycle

**Existing tests:**
- `HttpServerTest.scala:1125-1135` — "close stops accepting new connections": Creates server, sends one request, asserts 200. Does NOT actually call close or verify shutdown behavior (the test name is misleading).
- `HttpServerTest.scala:1153-1158` — "initUnscopedWith": Calls `server.closeNow.andThen(succeed)`. Only checks that closeNow doesn't throw, not that it's clean.
- `HttpClientTest.scala:1124-1129` — "close/idempotent": Tests `c.closeNow.andThen(c.closeNow)` for the client, not the server.

**Gap:** No test verifies that server shutdown is clean (no exceptions/warnings in stderr). This is cosmetic and Netty-specific. Testing would require capturing stderr output during shutdown, which is fragile. Better fixed in the Netty backend directly.

**Verdict:** Like Category A, this is best fixed in backend code rather than tested. The fix is to ensure Netty event loop shutdown is sequenced correctly.

---

## Step 4: Bug Reproduction Test Scenarios

### Category B: Client-Side Error Response Handling (Bug #2)

This is the only category where shared tests are appropriate and valuable.

#### Scenario B1: Convenience method receives typed error response (direct reproduction)

**Demo code pattern (TaskBoardClient):**
```scala
// Server: route with .error[ConflictError](HttpStatus.Conflict)
val create = HttpRoute.postRaw("tasks")
    .request(_.bodyJson[CreateTask])
    .response(_.bodyJson[Task].status(HttpStatus.Created).error[ConflictError](HttpStatus.Conflict))
    .handler { req => Abort.fail(ConflictError("already exists", 1)) }

// Client: convenience method
HttpClient.postJson[Task, CreateTask]("/tasks", CreateTask("dup", None, None))
// Result: HttpError.ParseError — original 409 body lost
```

**Proposed test code pattern:**
```scala
// Server: handler that returns 409 with JSON error body
val route = HttpRoute.postRaw("items")
    .request(_.bodyJson[CreateItem])
    .response(_.bodyJson[Item].error[ConflictError](HttpStatus.Conflict))
    .handler { req => Abort.fail(ConflictError("already exists", 1)) }

// Client: convenience method, catch error
Abort.run[HttpError](HttpClient.postJson[Item, CreateItem](url, CreateItem("dup")))
// Assert: error preserves status code (409) and ideally the error body
```

**Comparison:** Both use the same pattern — `HttpRoute.postRaw` with `.error[E](status)` and `Abort.fail(E(...))` on server, `HttpClient.postJson` on client. The test strips away the AtomicRef store (irrelevant to the bug) and uses simpler types. The code path is identical: server encodes error → client receives non-2xx → client tries to decode as success type → ParseError.

**Difference:** The demo has multiple error types (400 + 409); the test uses just one. This is acceptable because the bug is in the client's handling of any non-2xx response, not in multi-error routing.

#### Scenario B2: Convenience method receives 400 error response

**Proposed test:**
```scala
// Server returns 400 with ValidationError JSON body
// Client calls postJson, catches error
// Assert: error preserves status and body
```
Same code path as B1 but with 400 instead of 409. Tests that the issue isn't status-code-specific.

#### Scenario B3: Convenience method receives 404 error response

**Proposed test:**
```scala
// Server returns 404 with NotFound JSON body
// Client calls getJson, catches error
// Assert: error preserves status and body
```
Tests GET path (not just POST). Same underlying issue.

#### Scenario B4: Raw sendWith receives error response (control test)

**Proposed test:**
```scala
// Server returns 409
// Client uses sendWith (raw API) to access the response
// Assert: can read status AND body from the raw response
```
This verifies that the raw API works correctly, establishing that the bug is specific to convenience methods.

---

## Step 5: Coverage Expansion Beyond Bugs

### Category B Expansion: Client Error Handling Matrix

The bug exposed that convenience methods don't handle non-2xx responses. Let's map all convenience methods × error statuses:

| Convenience Method | Happy path tested? | 4xx error tested? | 5xx error tested? |
|---|---|---|---|
| `HttpClient.getJson` | ✓ (HttpServerTest:1550) | ✗ | ✗ |
| `HttpClient.postJson` | ✓ (HttpServerTest:1553) | ✗ | ✗ |
| `HttpClient.putJson` | ✓ (HttpServerTest:1557) | ✗ | ✗ |
| `HttpClient.deleteText` | ✓ (HttpServerTest:1561) | ✗ | ✗ |
| `HttpClient.getSseJson` | ✓ (HttpServerTest:1576) | ✗ | ✗ |
| `HttpClient.postText` | Not tested | ✗ | ✗ |
| `HttpClient.getText` | Not tested | ✗ | ✗ |
| `HttpClient.getSseText` | Not tested | ✗ | ✗ |
| `HttpClient.getNdJson` | Not tested | ✗ | ✗ |
| `HttpClient.postBinary` | Not tested | ✗ | ✗ |
| `HttpClient.getBinary` | Not tested | ✗ | ✗ |
| `HttpClient.patchJson` | Not tested | ✗ | ✗ |

**Expansion scenarios (coverage, not bug reproduction):**

- **B5:** `getText` happy path + error response
- **B6:** `postText` happy path + error response
- **B7:** `patchJson` happy path + error response
- **B8:** `getNdJson` happy path + error response
- **B9:** `postBinary` / `getBinary` happy path + error response
- **B10:** `getSseText` happy path + error response

### Server Error Response Body Content

The validation noted that 400 and 500 responses have generic bodies. Let's check what's tested:

| Error Source | Status tested? | Body content tested? |
|---|---|---|
| Router 404 (unknown path) | ✓ | ✗ |
| Router 405 (wrong method) | ✓ | ✗ (Allow header not checked) |
| Handler Abort.fail with typed error | ✓ (HttpServerTest) | ✓ (typed error body checked) |
| Parse error (bad JSON body) | ✓ (HttpServerTest) | ✗ (body content not checked) |
| Unhandled exception in handler | ✓ (HttpServerTest) | ✗ (body content not checked) |

**Expansion scenarios:**

- **B11:** 404 response body format — assert it contains `{"status":404,"error":"NotFound"}` or equivalent
- **B12:** 405 response body + `Allow` header content
- **B13:** 400 parse error response body content
- **B14:** 500 unhandled error response body content

---

## Step 6: Summary and Prioritization

### Tests to Write (ordered by priority)

**Bug reproduction (Category B):**
1. **B1** — postJson receives 409 typed error → verify error is accessible (direct reproduction of Bug #2)
2. **B4** — sendWith receives error response → verify raw API works (control test)
3. **B2** — postJson receives 400 typed error
4. **B3** — getJson receives 404 typed error

**Coverage expansion:**
5. **B11-B14** — Server error response body content assertions
6. **B5-B10** — Untested convenience method happy paths + error paths

**Not writing tests for (fix directly instead):**
- Category A (reason phrase) — Fix h2o backend, no shared test possible
- Category C (shutdown warnings) — Fix Netty shutdown sequencing, too fragile to test

**Demo fixes (no tests):**
- BookmarkClient: Remove auth filter from client-started server
- ApiGateway: Change `Abort.fail(HttpError.ConnectionError(...))` to `Abort.fail(ApiError(...))`

### Total new tests: ~14-18 scenarios across 4-6 test methods
