# kyo-http Usability Improvements -- Analysis

## Source Material

Feedback document: `kyo-http/HTTP-USABILITY-FEEDBACK.md` (6 issues from kyo-pod integration)

## Current State Assessment

### Issue 1: Text methods silently succeed on non-2xx (DONE)

The `sendUrl` methods in `HttpClient.scala:552-585` already check `resp.status.isSuccess` and throw `HttpStatusException` for all convenience methods. This was added after the feedback was filed. The `sendUrlBody`/`sendUrl` split is implemented and tested (12 tests, 2097 total passing). `*Response` methods are getting `failOnError: Boolean = true` parameter (agent in progress).

**Status**: Done. Implemented and tested.

### Issue 2: HttpStatusException discards response body (DROPPED -- security risk)

Including response bodies in exception messages is a security risk -- they can leak tokens, PII, and internal server details into logs. kyo-http already strips query params from URLs for the same reason. This issue is dropped from the plan entirely.

**Status**: Dropped. Will not implement.

### Issue 3: HttpStatusException message is client-unfriendly (OPEN -- now Fix 1)

The message says "The response body could not be decoded into the route's expected type" -- server-framework language that makes no sense to API consumers.

**Fix**: Remove the second line entirely. Keep only the first line: `POST http://example.com/api returned 404 (Not Found).` -- clean, client-friendly, no body content (security), no "route" mention.

### Issue 4: Unix socket connection errors show localhost:80 (OPEN)

Three error sites in `HttpClientBackend.scala`:
1. **Line 74-77** -- `connect()` method failure path: `HttpConnectException(url.host, url.port, ...)` where `url.host="localhost"`, `url.port=80` for unix sockets
2. **Line 746-751** -- `poolWithImpl()` pool exhausted: `HttpPoolExhaustedException(url.host, url.port, ...)`
3. **Line 499** -- `connectWebSocket()` timeout: `HttpConnectTimeoutException(host, port, ...)` (but WebSocket over unix is edge case)
4. **Line 501-505** -- `connectWebSocket()` failure: `HttpConnectException(host, port, ...)`

**Design choice analysis**:
- Option A: New exception subtype `HttpUnixConnectException` -- cleaner, users matching on `HttpConnectException` keep working
- Option B: Modify error messages inline by checking `url.unixSocket` before creating the exception

Option B is simpler and sufficient. We just need the error message to say `"unix:/path/to/sock"` instead of `"localhost:80"`. Adding a new exception subtype is over-engineering for a message fix. The existing `host` and `port` fields can be set to the unix socket path and 0 respectively.

Actually, changing the `host` field semantics would break pattern matching. Better: add a helper method that formats the target correctly:

```scala
private def formatTarget(url: HttpUrl): (String, Int) =
    url.unixSocket match
        case Present(path) => (s"unix:$path", 0)
        case Absent => (url.host, url.port)
```

Then use `formatTarget(url)` at each error site. The `host` field of the exception would then be `"unix:/var/run/docker.sock"` for unix sockets.

### Issue 5: HttpPoolExhaustedException shows misleading info for unix (OPEN, subsumed by Issue 4)

Same root cause as Issue 4. Fix is part of the same change.

### Issue 6: No convenience methods for fire-and-forget operations (OPEN)

Missing: `postUnit`, `deleteUnit`, `putUnit`, `patchUnit`

These are straightforward to add using the existing text routes and discarding the body:
```scala
def postUnit(...) = postText(...).unit
```

The `sendUrl` status check already handles non-2xx, so `postUnit` will correctly throw `HttpStatusException` on error status.

### Additional: Binary streaming convenience methods (from task spec)

Missing: `getStreamBytes`, `postStreamBytes`

Pattern exists in `getSseText`, `getNdJson` -- they use `HttpRoute.getRaw("").response(_.bodyStream)` internally. Same approach for binary streaming but with `_.bodyStream` which returns `Stream[Span[Byte], Async]`.

## Files to Modify

### Production code
1. `kyo-http/shared/src/main/scala/kyo/HttpException.scala` -- Fix `HttpStatusException` message (remove "route's expected type")
2. `kyo-http/shared/src/main/scala/kyo/HttpClient.scala` -- Add streaming + unit convenience methods
3. `kyo-http/shared/src/main/scala/kyo/internal/client/HttpClientBackend.scala` -- Fix unix socket error messages

### Test code
4. `kyo-http/shared/src/test/scala/kyo/HttpClientTest.scala` -- Tests 1-2 (message), 7-10 (streaming), 11-17 (unit)
5. `kyo-http/shared/src/test/scala/kyo/HttpClientUnixTest.scala` -- Tests 3-6 (unix socket errors)

## Risk Assessment

- **Message change**: Any code that parses `HttpStatusException` messages (logs, tests) will see different output (second line removed). This is expected and documented.
- **Unix socket error host/port changes**: The `host` field of `HttpConnectException` will be `"unix:/path"` instead of `"localhost"` for unix sockets. Code matching on `host == "localhost"` for unix socket errors would change behavior (unlikely).
- **New methods**: Purely additive, no risk.
