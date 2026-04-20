# kyo-http Usability Improvements -- Execution Plan

## Overview

4 fixes across 3 production files and 2 test files, organized into 6 phases. ~17 total tests.

**Completed work** (not in phases below):
- Status check behavior (`sendUrlBody`/`sendUrl` split) -- implemented and tested (12 tests, 2097 total passing)
- `*Response` methods getting `failOnError: Boolean = true` parameter (agent in progress)

**Removed from plan**:
- HttpStatusException body capture -- security risk (response bodies can leak tokens, PII, internal server details into logs). kyo-http already strips query params from URLs for the same reason.

## Phase 1: Fix HttpStatusException message + tests (Fix 1)

**Goal**: Remove the "route's expected type" message from `HttpStatusException`. Write tests that verify the new client-friendly message. Tests should FAIL first, then pass after the fix.

**Files to modify**: `kyo-http/shared/src/main/scala/kyo/HttpException.scala`, `kyo-http/shared/src/test/scala/kyo/HttpClientTest.scala`

### `HttpException.scala`

Remove the second line ("route's expected type") from the message. Keep the first line as-is:

```scala
case class HttpStatusException private (status: HttpStatus, method: String, url: String)(using Frame)
    extends HttpRequestException(
        s"${HttpException.showRequest(method, url)} returned ${status.code} (${status.name})."
    )
object HttpStatusException:
    def apply(status: HttpStatus, method: String, url: String)(using Frame): HttpStatusException =
        new HttpStatusException(status, method, HttpException.stripQuery(url))
```

Key change: Message becomes a single line like `POST http://example.com/api returned 404 (Not Found).` -- no body, no "route's expected type" mention.

### Tests to add (in a new `"HttpStatusException message"` section):

| # | Test name | What it verifies |
|---|-----------|------------------|
| 1 | `HttpStatusException message does not mention route` | Exception message does NOT contain `"route's expected type"` |
| 2 | `HttpStatusException message includes status code and name` | Exception message contains `"404"` and `"Not Found"` |

**Test pattern**:
```scala
"HttpStatusException message does not mention route" in run {
    val route = HttpRoute.getText("error-msg")
    val handler = route.handler(_ => HttpResponse.notFound("gone"))
    withServer(handler) { url =>
        HttpClient.withConfig(noTimeout) {
            Abort.run[HttpException](
                HttpClient.getText(url.copy(path = "/error-msg"))
            ).map { result =>
                assert(result.isFailure)
                result.failure match
                    case Present(e: HttpStatusException) =>
                        assert(!e.getMessage.contains("route's expected type"))
                    case other =>
                        fail(s"Expected HttpStatusException but got $other")
            }
        }
    }
}
```

**Verification**:
```bash
sbt 'kyo-http/testOnly kyo.HttpClientTest -- -z "HttpStatusException message"' 2>&1 | tail -20
```

## Phase 2: Fix unix socket error messages + tests (Fix 2)

**Goal**: Fix error constructors in `HttpClientBackend.scala` to show unix socket paths instead of `localhost:80`. Write tests that verify the fix.

**Files to modify**: `kyo-http/shared/src/main/scala/kyo/internal/client/HttpClientBackend.scala`, `kyo-http/shared/src/test/scala/kyo/HttpClientUnixTest.scala`

### `HttpClientBackend.scala`

Add a private helper at the top of the class:

```scala
private def targetInfo(url: HttpUrl): (String, Int) =
    url.unixSocket match
        case Present(path) => (s"unix:$path", 0)
        case Absent        => (url.host, url.port)
```

Then update all error sites:

#### connect() method (line 74-77):
```scala
case Result.Failure(closed) =>
    val (host, port) = targetInfo(url)
    resultPromise.completeDiscard(
        Result.fail(HttpConnectException(host, port, new IOException(closed.getMessage)))
    )
```

#### poolWithImpl() method (line 746-751):
```scala
val (host, port) = targetInfo(url)
Abort.fail(HttpPoolExhaustedException(host, port, maxConnectionsPerHost, clientFrame))
```

#### connectWebSocket() method (lines 499, 501-505):
```scala
case Result.Failure(_: Timeout) =>
    val (tHost, tPort) = targetInfo(url)
    Abort.fail(HttpConnectTimeoutException(tHost, tPort, connectTimeout))
case Result.Failure(closed: Closed) =>
    val (tHost, tPort) = targetInfo(url)
    Abort.fail(HttpConnectException(tHost, tPort, new IOException(...)))
```

### Tests to add (in a new `"unix socket error messages"` section):

| # | Test name | What it verifies |
|---|-----------|------------------|
| 3 | `unix socket connect failure mentions socket path` | Connect to nonexistent socket, error message contains the socket path |
| 4 | `unix socket connect failure does not say localhost:80` | Same scenario, error message does NOT contain `"localhost:80"` |
| 5 | `unix socket pool exhausted mentions socket path` | maxConnections=1, concurrent requests, error mentions socket path |
| 6 | `unix socket timeout mentions socket path` | Short timeout, slow server, error mentions socket path |

**Test pattern for connect failure**:
```scala
"unix socket connect failure mentions socket path" in run {
    val socketPath = "/tmp/kyo-nonexistent-test.sock"
    val url = mkUrl(socketPath, "/test")
    HttpClient.withConfig(noTimeout) {
        Abort.run[HttpException](HttpClient.getText(url)).map { result =>
            assert(result.isFailure)
            result.failure match
                case Present(e) =>
                    assert(
                        e.getMessage.contains(socketPath) || e.getMessage.contains("unix"),
                        s"Error should mention socket path, got: ${e.getMessage}"
                    )
                case _ => fail("Expected failure")
        }
    }
}
```

**Verification**:
```bash
sbt 'kyo-http/testOnly kyo.HttpClientUnixTest -- -z "unix socket error"' 2>&1 | tail -20
sbt 'kyo-http/test' 2>&1 | grep -E 'Tests:|FAILED'
```

## Phase 3: Streaming convenience methods (Fix 3)

**Goal**: Add `getStreamBytes` and `postStreamBytes` methods + tests.

**Files to modify**: `kyo-http/shared/src/main/scala/kyo/HttpClient.scala`, `kyo-http/shared/src/test/scala/kyo/HttpClientTest.scala`

### Add to HttpClient.scala (after NDJSON section):

```scala
// --- Binary Stream ---

def getStreamBytes(
    url: String | HttpUrl,
    headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
    query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
)(using Frame, Tag[Emit[Chunk[Span[Byte]]]]): Stream[Span[Byte], Async & Abort[HttpException]] =
    Stream(resolveUrl(url).map(u =>
        sendUrl(
            u,
            HttpRoute.getRaw("").response(_.bodyStream),
            resolveHeaders(headers),
            resolveQuery(query)
        )(_.fields.body).map(_.emit)
    ))

def postStreamBytes(
    url: String | HttpUrl,
    body: Span[Byte],
    headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
    query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
)(using Frame, Tag[Emit[Chunk[Span[Byte]]]]): Stream[Span[Byte], Async & Abort[HttpException]] =
    Stream(resolveUrl(url).map(u =>
        sendUrl(
            u,
            HttpRoute.postRaw("").request(_.bodyBinary).response(_.bodyStream),
            body,
            resolveHeaders(headers),
            resolveQuery(query)
        )(_.fields.body).map(_.emit)
    ))
```

### Add tests:

| # | Test name | What it verifies |
|---|-----------|------------------|
| 7 | `getStreamBytes returns chunks` | Server sends chunked response, stream emits data |
| 8 | `getStreamBytes completes when server closes` | Stream completes after all data received |
| 9 | `getStreamBytes fails on non-2xx` | Server returns 500, HttpStatusException thrown |
| 10 | `postStreamBytes sends body and streams response` | POST body sent, streaming response received |

**Verification**:
```bash
sbt 'kyo-http/testOnly kyo.HttpClientTest -- -z "stream bytes"' 2>&1 | tail -20
```

## Phase 4: Unit convenience methods (Fix 4)

**Goal**: Add `postUnit`, `deleteUnit`, `putUnit`, `patchUnit` methods + tests.

**Files to modify**: `kyo-http/shared/src/main/scala/kyo/HttpClient.scala`, `kyo-http/shared/src/test/scala/kyo/HttpClientTest.scala`

### Add to HttpClient.scala (new section after binary methods):

```scala
// ==================== Unit methods (fire-and-forget) ====================

def postUnit(
    url: String | HttpUrl,
    body: String = "",
    headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
    query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
)(using Frame): Unit < (Async & Abort[HttpException]) =
    resolveUrl(url).map(u => sendUrl(u, routePostText, body, resolveHeaders(headers), resolveQuery(query))(_ => ()))

def putUnit(
    url: String | HttpUrl,
    body: String = "",
    headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
    query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
)(using Frame): Unit < (Async & Abort[HttpException]) =
    resolveUrl(url).map(u => sendUrl(u, routePutText, body, resolveHeaders(headers), resolveQuery(query))(_ => ()))

def patchUnit(
    url: String | HttpUrl,
    body: String = "",
    headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
    query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
)(using Frame): Unit < (Async & Abort[HttpException]) =
    resolveUrl(url).map(u => sendUrl(u, routePatchText, body, resolveHeaders(headers), resolveQuery(query))(_ => ()))

def deleteUnit(
    url: String | HttpUrl,
    headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
    query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
)(using Frame): Unit < (Async & Abort[HttpException]) =
    resolveUrl(url).map(u => sendUrl(u, routeDeleteText, resolveHeaders(headers), resolveQuery(query))(_ => ()))
```

Note: These reuse `routePostText`/`routeDeleteText` etc. and the existing `sendUrl` which already checks status. The `_ => ()` extract function discards the body.

### Add tests:

| # | Test name | What it verifies |
|---|-----------|------------------|
| 11 | `postUnit succeeds on 200` | Server returns 200, postUnit returns Unit |
| 12 | `postUnit succeeds on 204` | Server returns 204 No Content, still succeeds |
| 13 | `postUnit fails on 404` | Server returns 404, throws HttpStatusException |
| 14 | `deleteUnit succeeds on 200` | Server returns 200, deleteUnit returns Unit |
| 15 | `deleteUnit fails on 500` | Server returns 500, throws HttpStatusException |
| 16 | `putUnit succeeds on 200` | Server returns 200, putUnit returns Unit |
| 17 | `patchUnit succeeds on 200` | Server returns 200, patchUnit returns Unit |

**Verification**:
```bash
sbt 'kyo-http/testOnly kyo.HttpClientTest -- -z "unit methods"' 2>&1 | tail -20
```

## Phase 5: Documentation

**Goal**: README updates and scaladocs on HttpClient methods.

**Files to modify**: `kyo-http/README.md` (or equivalent), `kyo-http/shared/src/main/scala/kyo/HttpClient.scala`

### README content to add:
- Explain status check behavior: convenience methods (`getText`, `postJson`, etc.) throw `HttpStatusException` on non-2xx responses
- Document `failOnError` parameter on `*Response` methods
- Explain difference between body-only methods (throw on error status) vs response methods (configurable via `failOnError`)

### Scaladocs:
- Add scaladocs to key HttpClient methods (being handled by current agent)

**Verification**:
```bash
# Ensure docs compile
sbt 'kyo-http/compile' 2>&1 | tail -5
```

## Phase 6: Final validation

**Goal**: Full test suite pass, cross-platform compile check.

**Verification**:
```bash
# Full test suite
sbt 'kyo-http/test' 2>&1 | grep -E 'Tests:|FAILED|passed|failed'

# Cross-compile check (JS)
sbt 'kyo-httpJS/compile' 2>&1 | tail -5

# Cross-compile check (Native)
sbt 'kyo-httpNative/compile' 2>&1 | tail -5
```

## Supervision Checklist Per Phase

For each phase, verify:

1. **No test weakening**: `git diff --stat -- '*Test*'` shows only additions, no deletions of existing test assertions
2. **Key requirements present**: Grep for critical strings in modified files
3. **Independent test run**: Run the targeted test class
4. **JVM compile**: At minimum, JVM compilation succeeds

## Dependency Graph

```
Phase 1 (message fix + tests) -- independent
Phase 2 (unix socket fix + tests) -- independent
Phase 3 (streaming methods + tests) -- independent
Phase 4 (unit methods + tests) -- independent
Phase 5 (documentation) -- after all impl phases
Phase 6 (final validation) -- after all phases
```

Phases 1-4 are independent of each other and could run in parallel, but sequential execution is preferred for easier debugging.
Phase 5 (docs) should wait until all implementation is settled.
Phase 6 is always last.

## Files Summary

| File | Phases | Changes |
|------|--------|---------|
| `HttpException.scala` | 1 | Fix message (remove "route's expected type") |
| `HttpClient.scala` | 3, 4 | Add streaming + unit methods |
| `HttpClientBackend.scala` | 2 | Fix unix socket error messages |
| `HttpClientTest.scala` | 1, 3, 4 | ~15 new tests |
| `HttpClientUnixTest.scala` | 2 | 4 new tests |
