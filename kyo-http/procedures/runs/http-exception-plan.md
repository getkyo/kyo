# HttpException Restructuring Plan

## Goals

1. **Rename** `HttpError` → `HttpException` (consistent with `KyoException` base class; `Exception` = recoverable, `Error` = unrecoverable)
2. **Categorize** via sealed intermediate types so users can catch by category (`HttpConnectionException`, `HttpRequestException`, `HttpServerException`, `HttpDecodeException`)
3. **Flat package structure** — all types in `kyo` package at the same level, named `Http*Exception` to avoid clashes when imported
4. **Structured params** on all case classes — no raw message strings; each takes the relevant data and builds a rich, actionable error message
5. **Fix Frame propagation** in native backends — replace `Frame.internal` with user-originating Frame so error messages show the user's code, not library internals

## Type Hierarchy

All types in `kyo` package, all in `HttpException.scala`:

**Important**: Messages must NOT contain ANSI escape codes directly. `KyoException.getMessage`
uses the raw `message` in production mode — ANSI codes would be garbage in logs. Instead,
override `getMessage` to apply colors only in development mode, or pass plain text to the
constructor and let `KyoException` handle presentation.

For this plan, messages use plain text. Colors are applied by `KyoException.getMessage` via
`frame.render(message)` in dev mode. The messages should still be well-structured with
multiline formatting.

```scala
sealed abstract class HttpException(message: Text, cause: Text | Throwable = "")(using Frame)
    extends KyoException(message, cause)

object HttpException:
    /** Strips query params from a URL string to avoid leaking sensitive data. */
    private[kyo] def stripQuery(url: String): String = url.takeWhile(_ != '?')

// --- Connection (transport-level failures) ---
// Call sites always have host:port strings (from URL, config, or Netty channel).

sealed abstract class HttpConnectionException(message: Text, cause: Text | Throwable = "")(using Frame)
    extends HttpException(message, cause)

case class HttpConnectException(host: String, port: Int, cause: Throwable)(using Frame)
    extends HttpConnectionException(
        s"""Connection to $host:$port failed.
           |
           |  Verify the server is running and reachable.""".stripMargin,
        cause
    )

case class HttpPoolExhaustedException(host: String, port: Int, maxConnections: Int, clientFrame: Frame)(using Frame)
    extends HttpConnectionException(
        s"""All $maxConnections connections to $host:$port are in use.
           |
           |  Each HttpClient instance maintains its own connection pool.
           |  This client was created at: ${clientFrame.position.show}
           |
           |  Increase maxConnectionsPerHost in HttpClient.init
           |  or reduce concurrent requests to this host.""".stripMargin
    )

// --- Request (protocol-level failures) ---
// Private constructors + companions to ensure URL query params are always stripped.
// Two overloads: string-based (for RouteUtil/CurlEventLoop) and HttpRequest-based (for HttpClient).

sealed abstract class HttpRequestException(message: Text, cause: Text | Throwable = "")(using Frame)
    extends HttpException(message, cause)

case class HttpTimeoutException private (duration: Duration, method: String, url: String)(using Frame)
    extends HttpRequestException(
        s"""$method $url timed out after ${duration.show}.""".stripMargin
    )
object HttpTimeoutException:
    def apply(duration: Duration, method: String, url: String)(using Frame): HttpTimeoutException =
        new HttpTimeoutException(duration, method, HttpException.stripQuery(url))
    def apply(duration: Duration, request: HttpRequest[?])(using Frame): HttpTimeoutException =
        new HttpTimeoutException(duration, request.method.name, request.url.baseUrl)

case class HttpRedirectLoopException private (count: Int, method: String, url: String, chain: List[String])(using Frame)
    extends HttpRequestException(
        s"""$method $url exceeded $count redirects (possible redirect loop).
           |
           |  Redirect chain (last ${chain.size}):
           |${chain.zipWithIndex.map((u, i) => s"    ${i + 1}. $u").mkString("\n")}
           |
           |  Increase maxRedirects or call followRedirects(false)
           |  in HttpClientConfig.""".stripMargin
    )
object HttpRedirectLoopException:
    def apply(count: Int, request: HttpRequest[?], chain: List[String])(using Frame): HttpRedirectLoopException =
        new HttpRedirectLoopException(count, request.method.name, request.url.baseUrl,
            chain.takeRight(5).map(HttpException.stripQuery))

case class HttpStatusException private (status: HttpStatus, method: String, url: String)(using Frame)
    extends HttpRequestException(
        s"""$method $url returned ${status.code} (${status.name}).
           |
           |  The response body could not be decoded into the route's expected type.""".stripMargin
    )
object HttpStatusException:
    def apply(status: HttpStatus, method: String, url: String)(using Frame): HttpStatusException =
        new HttpStatusException(status, method, HttpException.stripQuery(url))

// --- Server (server-side operational failures) ---

sealed abstract class HttpServerException(message: Text, cause: Text | Throwable = "")(using Frame)
    extends HttpException(message, cause)

case class HttpBindException(host: String, port: Int, cause: Throwable)(using Frame)
    extends HttpServerException(
        s"""Server failed to bind to $host:$port.
           |
           |  Is another process already using port $port?
           |${HttpBindException.describePortHolder(port)}""".stripMargin,
        cause
    )

object HttpBindException:
    /** Best-effort: uses lsof + ps to find which process holds the port.
      * JVM-only, silently returns empty on other platforms or if commands are unavailable.
      */
    private def describePortHolder(port: Int): String =
        try
            import scala.sys.process.*
            val pid = s"lsof -ti :$port".!!.trim.linesIterator.next()
            val name = s"ps -p $pid -o comm=".!!.trim
            if name.nonEmpty then s"\n  Held by: $name (PID $pid)"
            else s"\n  Held by PID: $pid"
        catch
            case _: Exception => ""

case class HttpHandlerException(error: Any)(using Frame)
    extends HttpServerException(
        s"""Route handler threw an unhandled error: $error
           |
           |  Use Abort.fail with a typed error mapped via .error[E](status)
           |  on the route, or catch exceptions within the handler.""".stripMargin,
        error match
            case e: Throwable => e
            case other        => String.valueOf(other)
    )

// --- Decode (parsing and deserialization failures) ---
// All decode exceptions (except HttpUrlParseException) take a `context: String` param
// that identifies the request/route being processed, e.g. "GET /users/:id" (server)
// or "GET https://example.com/users/123" (client). This is threaded from RouteUtil's
// 4 entry points: decodeBufferedRequest, decodeStreamingRequest, decodeBufferedResponse,
// decodeStreamingResponse.

sealed abstract class HttpDecodeException(message: Text, cause: Text | Throwable = "")(using Frame)
    extends HttpException(message, cause)

case class HttpUrlParseException private (url: String, detail: String)(using Frame)
    extends HttpDecodeException(
        s"""Failed to parse URL: $detail.
           |
           |  Input: $url""".stripMargin
    )
object HttpUrlParseException:
    def apply(url: String, detail: String)(using Frame): HttpUrlParseException =
        new HttpUrlParseException(url.takeWhile(_ != '?'), detail)

case class HttpFieldDecodeException(fieldName: String, fieldType: String, rawValue: String, detail: String, context: String)(using Frame)
    extends HttpDecodeException(
        s"""Failed to decode $fieldType '$fieldName' from value '$rawValue'.
           |
           |  Detail: $detail
           |  While processing: $context""".stripMargin
    )

case class HttpMissingFieldException(fieldName: String, fieldType: String, context: String)(using Frame)
    extends HttpDecodeException(
        s"""Missing required $fieldType '$fieldName'.
           |
           |  While processing: $context
           |
           |  Add a default value via the route definition, or use the optional
           |  variant (e.g. queryOpt, headerOpt, cookieOpt).""".stripMargin
    )

case class HttpJsonDecodeException(detail: String, context: String)(using Frame)
    extends HttpDecodeException(
        s"""JSON decode failed: $detail.
           |
           |  While processing: $context""".stripMargin
    )

case class HttpFormDecodeException(detail: String, context: String)(using Frame)
    extends HttpDecodeException(
        s"""Form decode failed: $detail.
           |
           |  While processing: $context""".stripMargin
    )

case class HttpUnsupportedMediaTypeException(expected: String, actual: Maybe[String], context: String)(using Frame)
    extends HttpDecodeException(
        s"""Unexpected Content-Type.
           |
           |  Expected: $expected
           |  Received: ${actual.getOrElse("(no Content-Type header)")}
           |  While processing: $context
           |
           |  Ensure the request includes the correct Content-Type header.""".stripMargin
    )

case class HttpStreamingDecodeException(contentType: String, context: String)(using Frame)
    extends HttpDecodeException(
        s"""Cannot decode streaming content type '$contentType' as a buffered body.
           |
           |  While processing: $context
           |
           |  Use a streaming handler for SSE, NDJSON, or multipart stream content types.""".stripMargin
    )

case class HttpMissingBoundaryException(context: String)(using Frame)
    extends HttpDecodeException(
        s"""Missing 'boundary' parameter in Content-Type header for multipart request.
           |
           |  While processing: $context
           |
           |  Ensure the client sends a Content-Type like
           |  'multipart/form-data; boundary=...'.""".stripMargin
    )
```

## Frame Propagation

Frame captures a source code snippet shown in error messages. Each exception should show the user's code that triggered it, not library internals.

| Exception | Frame should show | Current status |
|-----------|------------------|----------------|
| HttpTimeoutException | User's `client.send(...)` | ✅ Correct |
| HttpRedirectLoopException | User's `client.send(...)` | ✅ Correct |
| HttpPoolExhaustedException | User's `client.send(...)` | ✅ Correct |
| HttpConnectException | User's `client.send(...)` | ⚠️ Native uses Frame.internal |
| HttpStatusException | User's `client.send(...)` | ✅ Correct |
| HttpUrlParseException | User's parse/send call | ✅ Correct |
| HttpBindException | User's `HttpServer.init(...)` | ⚠️ Native uses Frame.internal |
| HttpHandlerException | User's `HttpServer.init(...)` | ⚠️ Native uses Frame.internal |
| All decode exceptions | Client: user's `client.send(...)`. Server: user's `HttpServer.init(...)` | ✅ Correct |

### Native backend fixes needed

**CurlEventLoop** — currently `private given Frame = Frame.internal` at class level:
- Store user's Frame in `CurlTransferState` when the transfer is created (Frame is available at request submission time in `CurlClientBackend.sendWith(using Frame)`)
- Use that stored Frame in `curlResultToError` and `failTransfer`
- Shutdown path (no user request) can keep Frame.internal

**H2oServerBackend** — currently `private given Frame = Frame.internal` at object level:
- `bind` already receives `(using Frame)` from user's `HttpServer.init(...)` — store it in `ServerState`
- Use stored Frame for HttpBindException in `startServer` and HttpHandlerException in `launchHandlerFiber`

## Implementation Steps

### Step 0: Add `baseUrl` to HttpUrl, `name` to HttpStatus, and `show` to HttpPath
- Add `baseUrl: String` method to `HttpUrl` — returns URL without query params (scheme + host + port + path). Used by exception messages to display URLs safely.
- Add `name: String` method to `HttpStatus` — converts camelCase enum name to readable form (e.g. `NotFound` → `"Not Found"`, `Custom(418)` → `"418"`). Used by `HttpStatusException`.
- Add `show: String` extension to `HttpPath` — renders the path pattern in human-readable form (e.g. `Concat(Literal("users"), Capture("id", ...))` → `"/users/:id"`). Used by RouteUtil to build decode exception context strings. Similar to `pathToHttpOpenApi` in `OpenApiGenerator` but uses `:name` instead of `{name}`.

### Step 1: Write HttpException.scala
Create `HttpException.scala` with all types above. Delete `HttpError.scala`.

### Step 2: Update shared code call sites
- `HttpClient.scala` — TimeoutError→HttpTimeoutException, TooManyRedirects→HttpRedirectLoopException, ConnectionPoolExhausted→HttpPoolExhaustedException, ParseError→HttpUrlParseException
- `HttpUrl.scala` — ParseError→HttpUrlParseException
- `RouteUtil.scala`:
  - Split `ParseError` into specific decode exception types per call site (see mapping below)
  - StatusError→HttpStatusException (drop body param)
  - The `Result.Error(_: HttpError.ParseError)` catch at line 159 becomes `Result.Error(_: HttpDecodeException)`
  - `UnsupportedMediaTypeError` call sites (lines 699, 705): currently pass only `expected` string. Extract `actual` content-type from `headers.get("Content-Type")` and pass as `Maybe[String]`
  - **Thread `context: String` through private decode methods**: add a `context` param to `decodeCaptures`, `decodeParam`, `decodeParamFields`, `decodeBufferedBodyValue`, `parseMultipartBody`. The 4 entry points build the context string:
    - Server (`decodeBufferedRequest`, `decodeStreamingRequest`): `s"${route.method.name} ${route.request.path.show}"` (shows route pattern like `GET /users/:id`)
    - Client (`decodeBufferedResponse`, `decodeStreamingResponse`): `s"$method ${HttpException.stripQuery(url)}"` (shows actual URL without query params)
  - **Streaming decode errors** (line 730): `decodeStreamBodyValue` throws `RuntimeException` for NDJSON/SSE failures — change to throw `HttpJsonDecodeException` instead. The `context` is captured via closure from the calling entry point.
  - **ParseError→new exception mapping** (all 10 sites):
    - Line 414: path capture decode → `HttpFieldDecodeException(wireName, "path", raw, e.getMessage, context)`
    - Line 416: missing path capture → `HttpMissingFieldException(wireName, "path", context)`
    - Line 554: param decode → `HttpFieldDecodeException(wireName, param.kind.toString, str, e.getMessage, context)`
    - Line 562: missing param → `HttpMissingFieldException(wireName, param.kind.toString, context)`
    - Line 699: unsupported media type (json) → `HttpUnsupportedMediaTypeException("application/json", headers.get("Content-Type"), context)`
    - Line 702: JSON decode → `HttpJsonDecodeException(msg, context)`
    - Line 705: unsupported media type (form) → `HttpUnsupportedMediaTypeException("application/x-www-form-urlencoded", headers.get("Content-Type"), context)`
    - Line 710: form decode → `HttpFormDecodeException(e.getMessage, context)`
    - Line 714: streaming as buffered → `HttpStreamingDecodeException(ct.toString, context)`
    - Line 960: missing boundary → `HttpMissingBoundaryException(context)`
- `HttpRoute.scala`, `HttpHandler.scala`, `HttpBackend.scala`, `HttpServer.scala`, `HttpRouter.scala` — type references `HttpError`→`HttpException`

### Step 3: Update JVM backend
- `NettyClientBackend.scala` — ConnectionError→HttpConnectException (host/port in scope)
- `NettyConnection.scala` — ConnectionError→HttpConnectException (host/port from class params)
- `NettyServerHandler.scala` — HandlerError→HttpHandlerException, pattern matches
- `NettyServerBackend.scala` — BindError→HttpBindException

### Step 4: Update JS backend
- `FetchClientBackend.scala` — ConnectionError→HttpConnectException (host/port from URL)
- `NodeServerBackend.scala` — BindError→HttpBindException, HandlerError→HttpHandlerException

### Step 5: Update Native backend + fix Frame propagation
- `CurlEventLoop.scala` — ConnectionError→HttpConnectException, TimeoutError→HttpTimeoutException, store user Frame in transfer state
- `CurlClientBackend.scala` — type references
- `H2oServerBackend.scala` — BindError→HttpBindException, HandlerError→HttpHandlerException, store user Frame in ServerState

### Step 6: Update tests and demos
- `HttpBackendTest.scala`, `HttpServerTest.scala`, `HttpClientTest.scala`, `RouteUtilTest.scala` — update error type references
- `CurlClientBackendTest.scala` — update error type references
- RFC test files — update pattern matches
- Demo files (e.g. `ApiGateway.scala`) — update error references

### Step 7: Update COMMIT_PLAN.md
Update commit 2 description to reflect the new exception hierarchy.
