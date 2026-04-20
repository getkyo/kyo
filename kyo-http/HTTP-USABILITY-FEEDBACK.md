# kyo-http Usability Feedback

Collected while building `kyo-pod` — a container orchestration module that uses `HttpClient` to talk to the Docker Engine API via Unix domain sockets (`http+unix://` URLs). These are real issues encountered during production-quality integration work.

---

## Issue 1: Text methods silently succeed on non-2xx status codes

**Severity: Critical — causes silent data corruption / lost errors**

### Problem

`HttpClient.getText`, `postText`, `deleteText` (and their `*Response` variants) return the response body as a `String` regardless of HTTP status code. A `DELETE` returning 404 or a `POST` returning 409 just returns the error body text — no exception is thrown.

This happens because `RouteUtil.decodeBufferedResponse` (line ~152-158) only throws `HttpStatusException` when body decoding FAILS on a non-2xx response. For text routes, decoding never fails (bytes → string always succeeds), so the status code is silently ignored.

JSON methods work correctly by accident: the error body (e.g. `{"message":"No such container"}`) fails to decode into the expected type `A`, which triggers the fallback to `HttpStatusException`.

### Where in the code

- `RouteUtil.decodeBufferedResponse` at `kyo-http/shared/src/main/scala/kyo/internal/server/RouteUtil.scala:152-158`
- The decision point:
  ```scala
  result match
      case Result.Error(_: HttpDecodeException) if !status.isSuccess =>
          Result.fail(HttpStatusException(status, method, url.toString))
      case other => other
  ```
- For text routes, `result` is always `Result.Success(...)` because string decode always succeeds.

### Impact

Any consumer using text methods for REST APIs gets silent failures. In kyo-pod, this caused:
- `remove()` appearing to succeed while containers stayed alive (DELETE returning 404/409)
- `start()` appearing to succeed on already-running containers (POST returning 304/409)
- `stop()` appearing to succeed on already-stopped containers (POST returning 304)
- `kill()` appearing to succeed on non-existent containers (POST returning 404)
- Error classification was impossible — all errors became `General` because `HttpStatusException` was never thrown

### Suggested fix

Add a status code check in `decodeBufferedResponse` that fires for ALL content types, not just when decode fails:

```scala
val result = decodeBody()
result match
    case Result.Success(_) if !status.isSuccess =>
        Result.fail(HttpStatusException(status, method, url.toString))
    case Result.Error(_: HttpDecodeException) if !status.isSuccess =>
        Result.fail(HttpStatusException(status, method, url.toString))
    case other => other
```

Or better yet, check status BEFORE attempting decode so the error body is available:

```scala
if !status.isSuccess then
    Result.fail(HttpStatusException(status, method, url.toString))
else
    decodeBody()
```

Note: Some APIs intentionally return non-2xx with a useful body (e.g. validation errors). Consider whether the body should be preserved in the exception. See Issue 2.

### Test scenarios

These tests should verify the fix works correctly. They should be placed in `HttpClientTest.scala` alongside existing text method tests.

**Test 1: `getText` should fail with `HttpStatusException` on 404**
```scala
"getText fails on 404" in run {
    val handler = HttpRoute.getText("/missing")
        .handle { _ => HttpResponse.notFound }
    withServer(handler) { url =>
        Abort.run[HttpException](HttpClient.getText(s"$url/missing")).map { result =>
            assert(result.isFailure)
            result.failure match
                case Present(e: HttpStatusException) =>
                    assert(e.status.code == 404)
                case other =>
                    fail(s"Expected HttpStatusException(404) but got $other")
        }
    }
}
```

**Test 2: `postText` should fail with `HttpStatusException` on 409**
```scala
"postText fails on 409" in run {
    val handler = HttpRoute.postText("/conflict")
        .handle { _ => HttpResponse(HttpStatus.Conflict) }
    withServer(handler) { url =>
        Abort.run[HttpException](HttpClient.postText(s"$url/conflict", "body")).map { result =>
            assert(result.isFailure)
            result.failure match
                case Present(e: HttpStatusException) =>
                    assert(e.status.code == 409)
                case other =>
                    fail(s"Expected HttpStatusException(409) but got $other")
        }
    }
}
```

**Test 3: `deleteText` should fail with `HttpStatusException` on 500**
```scala
"deleteText fails on 500" in run {
    val handler = HttpRoute.deleteText("/broken")
        .handle { _ => HttpResponse.internalServerError }
    withServer(handler) { url =>
        Abort.run[HttpException](HttpClient.deleteText(s"$url/broken")).map { result =>
            assert(result.isFailure)
            result.failure match
                case Present(e: HttpStatusException) =>
                    assert(e.status.code == 500)
                case other =>
                    fail(s"Expected HttpStatusException(500) but got $other")
        }
    }
}
```

**Test 4: `getTextResponse` should also fail on non-2xx (not return a response with error status)**
```scala
"getTextResponse fails on 403" in run {
    val handler = HttpRoute.getText("/forbidden")
        .handle { _ => HttpResponse(HttpStatus.Forbidden) }
    withServer(handler) { url =>
        Abort.run[HttpException](HttpClient.getTextResponse(s"$url/forbidden")).map { result =>
            assert(result.isFailure)
            result.failure match
                case Present(e: HttpStatusException) =>
                    assert(e.status.code == 403)
                case other =>
                    fail(s"Expected HttpStatusException(403) but got $other")
        }
    }
}
```

**Test 5: Consistency — JSON and text methods should behave the same on 404**
```scala
"text and json methods both fail on 404" in run {
    val textHandler = HttpRoute.getText("/text-404")
        .handle { _ => HttpResponse.notFound }
    val jsonHandler = HttpRoute.getJson[User]("/json-404")
        .handle { _ => HttpResponse.notFound }
    withServer(textHandler, jsonHandler) { url =>
        val textResult = Abort.run[HttpException](HttpClient.getText(s"$url/text-404"))
        val jsonResult = Abort.run[HttpException](HttpClient.getJson[User](s"$url/json-404"))
        textResult.map { tr =>
            jsonResult.map { jr =>
                // Both should fail with HttpStatusException(404)
                assert(tr.isFailure, "text method should fail on 404")
                assert(jr.isFailure, "json method should fail on 404")
                (tr.failure, jr.failure) match
                    case (Present(te: HttpStatusException), Present(je: HttpStatusException)) =>
                        assert(te.status.code == 404)
                        assert(je.status.code == 404)
                    case other =>
                        fail(s"Both should be HttpStatusException(404) but got $other")
            }
        }
    }
}
```

**Test 6: 2xx status codes should still work for text methods**
```scala
"getText succeeds on 200" in run {
    val handler = HttpRoute.getText("/ok")
        .handle { _ => HttpResponse.ok.addField("body", "hello") }
    withServer(handler) { url =>
        HttpClient.getText(s"$url/ok").map { body =>
            assert(body == "hello")
        }
    }
}
```

**Test 7: 204 No Content should succeed for text methods**
```scala
"postTextResponse succeeds on 204" in run {
    val handler = HttpRoute.postText("/no-content")
        .handle { _ => HttpResponse(HttpStatus.NoContent) }
    withServer(handler) { url =>
        HttpClient.postTextResponse(s"$url/no-content", "").map { resp =>
            assert(resp.status == HttpStatus.NoContent)
        }
    }
}
```

---

## Issue 2: `HttpStatusException` discards the response body

**Severity: High — key debugging information is lost**

### Problem

When `HttpStatusException` is thrown, it only stores the status code, method, and URL. The response body — which often contains the most important debugging information — is discarded.

For example, Docker API returns:
```json
{"message":"No such container: abc123"}
```

But `HttpStatusException` shows:
```
POST http+unix://%2Fvar%2Frun%2Fdocker.sock/v1.43/containers/abc123/start returned 404 (Not Found).

  The response body could not be decoded into the route's expected type.
```

The actual error message from Docker (`"No such container: abc123"`) is gone.

### Where in the code

- `HttpStatusException` case class at `kyo-http/shared/src/main/scala/kyo/HttpException.scala:125-133`
- `RouteUtil.decodeBufferedResponse` at line 157 creates the exception without the body bytes

### Suggested fix

Add a `body` field to `HttpStatusException` that stores a truncated response body:

```scala
case class HttpStatusException private (
    status: HttpStatus, method: String, url: String, body: String
)(using Frame)
    extends HttpRequestException(
        s"""${HttpException.showRequest(method, url)} returned ${status.code} (${status.name}).
           |
           |  Response: ${if body.isEmpty then "(empty)" else body}""".stripMargin
    )
object HttpStatusException:
    private val MaxBodyLength = 500
    def apply(status: HttpStatus, method: String, url: String, body: Span[Byte] = Span.empty)(using Frame): HttpStatusException =
        val bodyStr = if body.isEmpty then ""
            else new String(body.toArrayUnsafe, "UTF-8").take(MaxBodyLength)
        new HttpStatusException(status, method, HttpException.stripQuery(url), bodyStr)
```

Then pass the body bytes from `RouteUtil.decodeBufferedResponse`:
```scala
Result.fail(HttpStatusException(status, method, url.toString, body))
```

### Test scenarios

**Test 8: `HttpStatusException` should include response body**
```scala
"HttpStatusException includes error body" in run {
    val handler = HttpRoute.getJson[User]("/error-body")
        .handle { _ =>
            HttpResponse(HttpStatus.NotFound).addField("body", """{"message":"not found"}""")
        }
    withServer(handler) { url =>
        Abort.run[HttpException](HttpClient.getJson[User](s"$url/error-body")).map { result =>
            result.failure match
                case Present(e: HttpStatusException) =>
                    assert(e.getMessage.contains("not found") || e.body.contains("not found"),
                        s"Exception should include response body, got: ${e.getMessage}")
                case other =>
                    fail(s"Expected HttpStatusException but got $other")
        }
    }
}
```

---

## Issue 3: `HttpStatusException` message is misleading for client-side usage

**Severity: Medium — confusing error messages**

### Problem

The message says:
```
The response body could not be decoded into the route's expected type.
```

This is written from a server framework perspective. When a user is calling `HttpClient.getJson[User](url)` and gets a 404, this message makes no sense — they're a client, not defining routes. The actual problem is "the server returned an error", not "the body couldn't be decoded".

### Where in the code

- `HttpStatusException` at `kyo-http/shared/src/main/scala/kyo/HttpException.scala:127-129`

### Suggested fix

Change the message to be client-friendly:
```scala
s"""${HttpException.showRequest(method, url)} returned ${status.code} (${status.name}).
   |
   |  Response: ${if body.isEmpty then "(empty)" else body}""".stripMargin
```

Or if keeping both concerns:
```scala
s"""${HttpException.showRequest(method, url)} returned non-success status ${status.code} (${status.name})."""
```

### Test scenario

**Test 9: Error message should not mention "route's expected type"**
```scala
"HttpStatusException has client-friendly message" in run {
    val handler = HttpRoute.getJson[User]("/msg-test")
        .handle { _ => HttpResponse.notFound }
    withServer(handler) { url =>
        Abort.run[HttpException](HttpClient.getJson[User](s"$url/msg-test")).map { result =>
            result.failure match
                case Present(e: HttpStatusException) =>
                    assert(!e.getMessage.contains("route's expected type"),
                        s"Message should be client-friendly, got: ${e.getMessage}")
                    assert(e.getMessage.contains("404"),
                        s"Message should include status code, got: ${e.getMessage}")
                case other =>
                    fail(s"Expected HttpStatusException but got $other")
        }
    }
}
```

---

## Issue 4: Unix socket connection errors show misleading host:port

**Severity: Medium — confusing error messages for unix socket users**

### Problem

When connecting to a Unix domain socket fails, `HttpConnectException` is created with `url.host` (`"localhost"`) and `url.port` (`80`):

```scala
Result.fail(HttpConnectException(url.host, url.port, new IOException(closed.getMessage)))
```

This produces:
```
Connection to localhost:80 failed.

  Verify the server is running and reachable.
```

But the actual connection was to `/var/run/docker.sock`. The user has no idea what went wrong or where to look.

### Where in the code

- `HttpClientBackend.connect` at `kyo-http/shared/src/main/scala/kyo/internal/client/HttpClientBackend.scala:74-77`
- The `connect` method already branches on `url.unixSocket` (line 49) but the error handling doesn't account for it.

### Suggested fix

Option A: Add a unix-socket-specific exception:
```scala
case class HttpUnixConnectException(socketPath: String, cause: Throwable)(using Frame)
    extends HttpConnectionException(
        s"""Connection to Unix socket $socketPath failed.
           |
           |  Verify the socket file exists and the process is running.""".stripMargin,
        cause
    )
```

Option B: Check `url.unixSocket` in the error path:
```scala
case Result.Failure(closed) =>
    val error = url.unixSocket match
        case Present(path) =>
            HttpConnectException(s"unix:$path", 0, new IOException(closed.getMessage))
        case Absent =>
            HttpConnectException(url.host, url.port, new IOException(closed.getMessage))
    resultPromise.completeDiscard(Result.fail(error))
```

Option A is better because it gives distinct exception type for unix socket failures.

### Test scenarios

**Test 10: Unix socket connection error should show socket path**
```scala
"unix socket connection failure shows socket path" in run {
    val url = "http+unix://%2Ftmp%2Fno-such-socket.sock/test"
    Abort.run[HttpException](HttpClient.getText(url)).map { result =>
        result.failure match
            case Present(e: HttpConnectException) =>
                assert(e.getMessage.contains("/tmp/no-such-socket.sock") ||
                       e.getMessage.contains("unix"),
                    s"Error should mention socket path, got: ${e.getMessage}")
            case Present(e) =>
                assert(e.getMessage.contains("/tmp/no-such-socket.sock") ||
                       e.getMessage.contains("unix"),
                    s"Error should mention socket path, got: ${e.getMessage}")
            case other =>
                fail(s"Expected connection failure but got $other")
    }
}
```

**Test 11: Unix socket connection error should NOT say "localhost:80"**
```scala
"unix socket connection failure does not show localhost:80" in run {
    val url = "http+unix://%2Ftmp%2Fno-such-socket-2.sock/test"
    Abort.run[HttpException](HttpClient.getText(url)).map { result =>
        result.failure match
            case Present(e) =>
                assert(!e.getMessage.contains("localhost:80"),
                    s"Error should not mention localhost:80 for unix socket, got: ${e.getMessage}")
            case other =>
                fail(s"Expected failure but got $other")
    }
}
```

---

## Issue 5: `HttpPoolExhaustedException` shows misleading info for unix sockets

**Severity: Low — same root cause as Issue 4**

### Problem

When pool is exhausted for a unix socket URL, it shows:
```
All 8 connections to localhost:80 are in use.
```

Should instead show:
```
All 8 connections to unix:/var/run/docker.sock are in use.
```

### Where in the code

- `HttpClientBackend.poolWithImpl` at line ~746-751
- Uses `url.host` and `url.port` which are `"localhost"` and `80` for unix socket URLs.

### Suggested fix

```scala
url.unixSocket match
    case Present(path) =>
        Abort.fail(HttpPoolExhaustedException(s"unix:$path", 0, maxConnectionsPerHost, clientFrame))
    case Absent =>
        Abort.fail(HttpPoolExhaustedException(url.host, url.port, maxConnectionsPerHost, clientFrame))
```

### Test scenario

**Test 12: Pool exhausted error for unix sockets shows socket path**
```scala
"pool exhausted error shows unix socket path" in run {
    // Use maxConnections=1 and make 2 concurrent requests
    HttpClient.initUnscoped(maxConnectionsPerHost = 1).map { client =>
        val socketUrl = "http+unix://%2Ftmp%2Ftest-pool.sock/test"
        // ... test that pool exhausted error mentions socket path, not localhost:80
    }
}
```

---

## Issue 6: No `delete` / `post` convenience methods that ignore response body

**Severity: Low — ergonomic improvement**

### Problem

Many REST APIs use `DELETE` and `POST` for side-effect operations where the response body is irrelevant (empty or just `"OK"`). Currently users must use `deleteTextResponse` or `deleteText` and discard the body.

For Docker API:
- `POST /containers/{id}/start` — response body is empty
- `POST /containers/{id}/stop` — response body is empty
- `DELETE /containers/{id}` — response body is empty
- `POST /containers/{id}/kill` — response body is empty

All return 204 No Content on success.

### Suggested addition

```scala
def postUnit(
    url: String | HttpUrl,
    headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
    query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
)(using Frame): Unit < (Async & Abort[HttpException])

def deleteUnit(
    url: String | HttpUrl,
    headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
    query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
)(using Frame): Unit < (Async & Abort[HttpException])
```

These would use HEAD-style decoding (skip body) and only check status code.

### Test scenario

**Test 13: `deleteUnit` returns Unit on 204**
```scala
"deleteUnit succeeds on 204" in run {
    val handler = HttpRoute.deleteText("/item")
        .handle { _ => HttpResponse(HttpStatus.NoContent) }
    withServer(handler) { url =>
        HttpClient.deleteUnit(s"$url/item").map { _ =>
            succeed
        }
    }
}
```

**Test 14: `postUnit` fails on non-2xx**
```scala
"postUnit fails on 404" in run {
    val handler = HttpRoute.postText("/missing")
        .handle { _ => HttpResponse.notFound }
    withServer(handler) { url =>
        Abort.run[HttpException](HttpClient.postUnit(s"$url/missing")).map { result =>
            assert(result.isFailure)
        }
    }
}
```

---

## Summary of priority

| # | Issue | Severity | Breaking? |
|---|-------|----------|-----------|
| 1 | Text methods silently succeed on non-2xx | Critical | Yes — behavior change |
| 2 | `HttpStatusException` discards response body | High | Additive |
| 3 | `HttpStatusException` message is misleading | Medium | Message change |
| 4 | Unix socket connection errors show localhost:80 | Medium | Message change |
| 5 | Pool exhausted shows misleading info for unix | Low | Message change |
| 6 | No `deleteUnit`/`postUnit` convenience methods | Low | Additive |

Issue 1 is the most critical and should be addressed first. It's a silent correctness bug that affects any consumer of text methods for non-trivial HTTP APIs.
