# Demo Validation Procedure

> **Before starting:** Read [0-READ_THIS_FIRST.md](0-READ_THIS_FIRST.md), especially the **Bash command rules**.

## Role

You are a **QA engineer** validating the kyo-http framework. Your job is to run demos, test them thoroughly, observe what happens, and report your findings clearly. You are not debugging the framework or proposing fixes — you are testing it from the outside and documenting what you see.

## Goal

Validate the kyo-http framework by running demo applications on each target platform. The demos exercise real framework features — routing, JSON serialization, streaming, HTTP client, error handling, filters, OpenAPI generation, etc. The goal is **comprehensive validation** that surfaces bugs, platform incompatibilities, and usability issues.

**Every demo must work on every platform.** There are no expected failures. If a demo fails to compile, link, or run on any platform, that is a bug — report it. Demos are cross-platform by design; if one uses APIs unavailable on a platform (e.g., `java.nio.file.*` on JS), that's a demo bug, not a platform limitation to skip.

## What We're Validating

The demos are not the point — they're vehicles for exercising **framework capabilities**. Before testing, read each demo's source code and identify which of these it exercises:

- Route-declared status codes (e.g., 201 Created, 204 No Content)
- Typed error responses with status mapping (e.g., 404 with JSON body)
- JSON request/response with Schema derivation
- Form and multipart request parsing
- Binary responses (file download, raw bytes)
- Path captures (`/resource/:id`)
- Query parameters (required, optional, defaults)
- SSE and streaming responses (Server-Sent Events, NDJSON)
- HttpClient outbound requests (via platform-specific backend)
- Server filters (logging, CORS, auth, security headers)
- OpenAPI spec generation
- Health endpoints
- Composite operations (multiple outbound calls combined into one response)

This list will evolve as the framework grows. The demos in `kyo-http/shared/src/main/scala/demo/` are the source of truth — read them to discover what capabilities are in play.

## Platforms

Validate on each platform the project targets:

- **JVM** — Netty backend. The reference platform; should have fewest issues.
- **JS / Node.js** — Requires `scalaJSUseMainModuleInitializer := true` and `Compile / mainClass` in jsSettings. Some java.* APIs unavailable (e.g., `java.nio.file.*`).
- **Scala Native** — H2o + libcurl backends. Requires libh2o-evloop and libcurl via pkg-config. Some java.* APIs may have partial support.

## Execution Order

Validate **one demo at a time across all platforms** before moving to the next demo. For each demo:

1. Run and test on JVM
2. Run and test on JS
3. Run and test on Native
4. Record results for all three platforms
5. Move to the next demo

This approach surfaces cross-platform differences immediately while context is fresh, rather than testing all demos on one platform and then trying to remember details when testing the same demo on another platform later.

## How to Run

### Setting the main class

JVM demos can be run directly with `sbt 'kyo-http/runMain demo.XXX'`. For JS and Native, sbt's `runMain` doesn't work with cross-projects — you must set the main class in build.sbt temporarily.

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

Then run:
- JVM: `sbt 'kyo-http/run'` or `sbt 'kyo-http/runMain demo.XXX'`
- JS: `sbt 'kyo-httpJS/run'`
- Native: `sbt 'kyo-httpNative/run'`

Change the `mainClass` in build.sbt for each demo, then run again. Remember to revert these build.sbt changes when validation is complete.

### Running the demo

1. If the demo fails to compile or link, record why — this is valuable platform compatibility information
2. If it starts, test its endpoints thoroughly (see below)
3. Stop the server, move to the next demo

### Port management

Demos may hardcode ports or use dynamic port assignment (`port(0)`). Either way, port conflicts are a common source of confusing failures.

- **Before starting a demo**, check if its port is free: `lsof -i :<port>`
- **Kill stale processes** from previous runs: `kill <pid>`. Demos run via `sbt run` leave JVM processes behind; Native binaries leave native processes behind. Check with `ps aux | grep kyo-http`.
- **After stopping sbt**, verify the port was released. sbt's `run` task sometimes doesn't clean up child processes.
- **If a demo crashes on startup with a port error**, the error message quality itself is worth recording. An unhelpful message like "server start failed" (with no port info) is a usability bug.

## Testing Approach

You are testing from the outside — as a user of the framework would. You send HTTP requests, observe responses, and check whether the behavior matches what the demo source code declares.

**Start by reading the source code.** Before testing any demo, read its source to understand every route, filter, error type, and configuration it declares. This tells you what to test. If a route declares `.error[ApiError](HttpStatus.NotFound)`, you know to send a request that triggers that error case and verify the response is a 404 with the right JSON body. If a filter adds security headers, check they appear in responses.

**Test systematically.** For each route in the demo:
- Happy path with valid inputs
- Error cases declared in the route definition
- Edge cases (missing params, wrong types, empty bodies, special characters)
- Wrong HTTP method
- Verify response status code, headers (Content-Type, CORS, custom headers), and body

**Go deeper when something looks interesting:**
- Use `curl -sv` to see full request/response headers
- Pipe JSON through `jq` to validate structure against the Schema definitions in source
- Fire parallel requests to check for race conditions on stateful demos
- Test boundary conditions: unicode in path captures, very long values, empty strings
- After a 500 error, send another valid request to verify the server recovered
- For SSE/NDJSON endpoints: verify Content-Type headers, wait long enough for actual data, check wire format
- Compare the OpenAPI spec against actual routes — are all paths present? Are error responses documented?

**Check the server console.** After any unexpected response (500, empty body, wrong status), check the sbt output for stack traces or error messages. These are part of your observation.

## Sanity Check (Required)

After finishing testing a demo (on each platform), **stop and audit yourself** before moving on. Answer every question below honestly. If the answer to any question is "no", go back and fill the gap before proceeding.

1. **Did I test every route in the demo?** List them. If any route is missing, test it now.
2. **Did I test each route's declared error cases?** For every `.error[T](status)` in the source, did I send a request that triggers it and verify the status code and body?
3. **Did I test edge cases?** For each route: empty body, missing params, wrong types, wrong HTTP method, special characters (unicode, slashes, spaces), empty strings in path captures.
4. **Did I verify response headers?** Content-Type, CORS headers (if CORS filter is used), security headers (if security filter is used), custom headers (X-Total-Count, ETag, etc.), Set-Cookie (if cookies are used).
5. **Did I check the OpenAPI spec?** Are all routes present? Do error responses match what routes declare? Are query params and path params documented?
6. **For stateful demos: did I test concurrent access?** Fire parallel requests and check for race conditions or data corruption.
7. **Did I check the server console?** After any 500 or unexpected response, did I look at the sbt output for stack traces?
8. **Did I record every command and its full output?** Not summaries — the actual curl invocations and verbatim responses.

Only after answering "yes" to all of these should you write up the results and move to the next demo.

## What to Record

Create a `DEMO_VALIDATION_<PLATFORM>.md` file for each platform.

**Update the file as you go — do not wait until the end.** After testing each demo on each platform, immediately write its results before moving on. This is critical because:
- Context is freshest right after testing — you'll forget details if you defer writing
- If the session is interrupted, partial results are preserved
- It forces you to articulate what you actually tested while you can still re-run a command if you realize a gap

The report must be **auditable** — someone reading it should be able to judge whether the testing was truly comprehensive or superficial. Include the exact commands, full unedited responses, and what you observed.

For each demo, record:

- **Status:** PASS / PARTIAL FAIL / FAIL / SKIP (with reason)
- **Every command and its verbatim output** — the exact curl invocation and the full unedited response (headers + body). This is how the reader evaluates test quality.
- **What was verified** — which framework capabilities this demo exercised and what you checked
- **What was NOT tested** — be honest about gaps
- **Bugs found** — with full detail (see below)
- **Observations** — anything surprising, slow, confusing, or worth noting, even if it's not a bug

## Bug Reporting

You are reporting bugs as a QA engineer. Your job is to describe **what you observed**, make it **reproducible**, assess its **impact**, and provide enough context so a developer can investigate. You are not debugging internals or proposing code fixes.

A bug report that just says "500 empty body" is useless. Every bug report **must** include:

1. **What happened vs what was expected.** Be specific. Not "error response" — say "Got `HTTP/1.1 500 Internal Server Error` with empty body. Expected `HTTP/1.1 404 Not Found` with JSON body `{"error": "..."}` since the route declares `.error[ApiError](HttpStatus.NotFound)`."

2. **How to reproduce.** The exact command, copy-pasteable. Include any prerequisite state (e.g., "start the demo with `sbt 'kyo-http/runMain demo.BookmarkStore'`, then run:"). Someone should be able to paste your commands and see the same result.

3. **Full observed output.** The complete `curl -sv` output — request headers, response status line, response headers, response body. Don't summarize or truncate. If there was server-side output in the sbt console (stack traces, warnings, error logs), include that too.

4. **Impact.** Explain concretely what this breaks:
   - Does it make a feature unusable? (e.g., "CORS is broken — browser clients cannot use this API")
   - Is it cosmetic? (e.g., "reason phrase says 'OK' instead of 'Not Found' — most clients ignore reason phrases")
   - How likely is a real user to hit this? (e.g., "any unknown city triggers this, so it's easy to hit")
   - Does it affect all routes or just this specific one?

5. **Platform comparison.** Report what each platform does for the exact same request. If behavior differs across platforms, that is itself a finding worth calling out separately. Example: "JVM returns 500 with empty body. Native returns 500 with JSON body `{"status":500,"error":"InternalServerError"}`. JS returns 500 with empty body. The error response format is inconsistent across platforms."

6. **Classification:**
   - **Framework bug** — the framework itself behaves incorrectly regardless of demo code
   - **Demo bug** — the demo code is wrong; the framework is working as designed
   - **Both** — the demo triggers it but the framework could handle the case more gracefully (e.g., demo passes wrong error type, but framework should still return a useful error response instead of an empty body)

7. **Source context.** Reference what the demo source code declares, since you already read it. Example: "The weather route at line 82 declares `.error[ApiError](HttpStatus.NotFound)` but the `fetchWeather` function at line 57 calls `Abort.fail(HttpError.ConnectionError(...))`. The error type in the handler doesn't match what the route expects." This is an observation from reading the source, not internal debugging.

### Usability issues

Also capture issues that aren't bugs but hurt developer experience:
- Unhelpful error messages — what context is missing that would help debugging?
- Empty response bodies on errors — the client gets no information about what went wrong
- Platform-specific limitations that aren't documented
- APIs that are confusing or produce surprising behavior
- Configuration that is error-prone (e.g., hardcoded ports causing silent failures)

## Cross-Platform Comparison

After validating all platforms, produce a comparison that highlights:

- Which demos work on which platforms and which don't (and why)
- Framework features that are platform-specific (e.g., streaming works on one platform but not another)
- java.* API availability differences across platforms
- Response differences for the same request across platforms (should be identical — any diff is a finding)
- Performance differences (compilation time, response latency, startup time)

## Output

The validation should produce:
1. One `DEMO_VALIDATION_<PLATFORM>.md` per platform
2. A bugs section in each file with enough detail to reproduce every issue
3. Cross-references to bugs that appear on multiple platforms vs single-platform issues
