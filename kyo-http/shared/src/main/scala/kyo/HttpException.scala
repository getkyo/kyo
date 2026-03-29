package kyo

import kyo.*

/** Base class for all HTTP-related errors, organized into four categories by failure mode.
  *
  * All exception constructors strip query parameters from URLs before storing them, ensuring sensitive data (API keys, tokens) is never
  * retained in exception messages or stack traces.
  *
  * @see
  *   [[kyo.HttpConnectionException]] Transport-level failures
  * @see
  *   [[kyo.HttpRequestException]] Protocol-level failures
  * @see
  *   [[kyo.HttpServerException]] Server-side operational failures
  * @see
  *   [[kyo.HttpDecodeException]] Parsing and deserialization failures
  * @see
  *   [[kyo.HttpClient]] Client operations abort with `Abort[HttpException]`
  */
sealed abstract class HttpException(message: Text, cause: Text | Throwable = "")(using Frame)
    extends KyoException(message, cause)

object HttpException:
    /** Strips query params from a URL string to avoid leaking sensitive data. */
    private[kyo] def stripQuery(url: String): String = url.takeWhile(_ != '?')

    /** Formats method + url for display in exception messages. */
    private[kyo] def showRequest(method: String, url: String): String =
        s"$method $url"
end HttpException

// --- Connection (transport-level failures) ---

/** Transport-level failures before a response is received.
  *
  * @see
  *   [[kyo.HttpConnectException]] Connection refused or unreachable host
  * @see
  *   [[kyo.HttpPoolExhaustedException]] All connections to a host are in use
  */
sealed abstract class HttpConnectionException(message: Text, cause: Text | Throwable = "")(using Frame)
    extends HttpException(message, cause)

/** Connection refused or unreachable host. */
case class HttpConnectException(host: String, port: Int, cause: Throwable)(using Frame)
    extends HttpConnectionException(
        s"""Connection to $host:$port failed.
           |
           |  Verify the server is running and reachable.""".stripMargin,
        cause
    )

/** All connections to a host are in use. Includes the Frame where the client was created for debugging. */
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

/** Protocol-level failures during request processing.
  *
  * @see
  *   [[kyo.HttpTimeoutException]] Request exceeded the configured timeout
  * @see
  *   [[kyo.HttpRedirectLoopException]] Too many redirects
  * @see
  *   [[kyo.HttpStatusException]] Non-success status code when the response body can't be decoded
  */
sealed abstract class HttpRequestException(message: Text, cause: Text | Throwable = "")(using Frame)
    extends HttpException(message, cause)

/** Request exceeded the configured timeout. */
case class HttpTimeoutException private (duration: Duration, method: String, url: String)(using Frame)
    extends HttpRequestException(
        s"""${HttpException.showRequest(method, url)} timed out after ${duration.show}."""
    )
object HttpTimeoutException:
    def apply(duration: Duration, method: String, url: String)(using Frame): HttpTimeoutException =
        new HttpTimeoutException(duration, method, HttpException.stripQuery(url))
end HttpTimeoutException

/** Too many redirects detected (possible redirect loop). Shows the last 5 locations in the chain. */
case class HttpRedirectLoopException private (count: Int, method: String, url: String, chain: Chunk[String])(using Frame)
    extends HttpRequestException(
        s"""${HttpException.showRequest(method, url)} exceeded $count redirects (possible redirect loop).
           |
           |  Redirect chain (last ${chain.size}):
           |${chain.zipWithIndex.map((u, i) => s"    ${i + 1}. $u").mkString("\n")}
           |
           |  Increase maxRedirects or call followRedirects(false)
           |  in HttpClientConfig.""".stripMargin
    )
object HttpRedirectLoopException:
    def apply(count: Int, method: String, url: String, chain: Chunk[String])(using Frame): HttpRedirectLoopException =
        new HttpRedirectLoopException(
            count,
            method,
            HttpException.stripQuery(url),
            chain.takeRight(5).map(HttpException.stripQuery)
        )
end HttpRedirectLoopException

/** Non-success status code when the response body can't be decoded. */
case class HttpStatusException private (status: HttpStatus, method: String, url: String)(using Frame)
    extends HttpRequestException(
        s"""${HttpException.showRequest(method, url)} returned ${status.code} (${status.name}).
           |
           |  The response body could not be decoded into the route's expected type.""".stripMargin
    )
object HttpStatusException:
    def apply(status: HttpStatus, method: String, url: String)(using Frame): HttpStatusException =
        new HttpStatusException(status, method, HttpException.stripQuery(url))

// --- Server (server-side operational failures) ---

/** Server-side operational failures.
  *
  * @see
  *   [[kyo.HttpBindException]] Server failed to bind to a port
  * @see
  *   [[kyo.HttpHandlerException]] Unhandled error from a route handler
  */
sealed abstract class HttpServerException(message: Text, cause: Text | Throwable = "")(using Frame)
    extends HttpException(message, cause)

/** Server failed to bind to a port. */
case class HttpBindException(host: String, port: Int, cause: Throwable)(using Frame)
    extends HttpServerException(
        s"""Server failed to bind to $host:$port.
           |
           |  Is another process already using port $port?""".stripMargin,
        cause
    )

/** Unhandled error from a route handler. */
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

/** Parsing and deserialization failures.
  *
  * @see
  *   [[kyo.HttpUrlParseException]] Failed to parse a URL
  * @see
  *   [[kyo.HttpFieldDecodeException]] Failed to decode a path/query/header/cookie field
  * @see
  *   [[kyo.HttpMissingFieldException]] Required field missing from the request
  * @see
  *   [[kyo.HttpJsonDecodeException]] JSON body decode failed
  * @see
  *   [[kyo.HttpFormDecodeException]] Form body decode failed
  * @see
  *   [[kyo.HttpUnsupportedMediaTypeException]] Unexpected Content-Type
  * @see
  *   [[kyo.HttpStreamingDecodeException]] Cannot decode streaming content as buffered body
  * @see
  *   [[kyo.HttpMissingBoundaryException]] Missing boundary parameter for multipart request
  */
sealed abstract class HttpDecodeException(message: Text, cause: Text | Throwable = "")(using Frame)
    extends HttpException(message, cause)

/** Failed to parse a URL. */
case class HttpUrlParseException private (url: String, detail: String, cause: Text | Throwable)(using Frame)
    extends HttpDecodeException(
        s"""Failed to parse URL: $detail.
           |
           |  Input: $url""".stripMargin,
        cause
    )
object HttpUrlParseException:
    def apply(url: String, detail: String)(using Frame): HttpUrlParseException =
        new HttpUrlParseException(HttpException.stripQuery(url), detail, "")
    def apply(url: String, cause: Throwable)(using Frame): HttpUrlParseException =
        new HttpUrlParseException(HttpException.stripQuery(url), cause.getMessage, cause)
end HttpUrlParseException

/** Failed to decode a path capture, query parameter, header, or cookie field. */
case class HttpFieldDecodeException private (
    fieldName: String,
    fieldType: String,
    detail: String,
    method: String,
    url: String,
    cause: Text | Throwable
)(using Frame)
    extends HttpDecodeException(
        s"""Failed to decode $fieldType '$fieldName'.
           |
           |  Detail: $detail
           |  While processing: ${HttpException.showRequest(method, url)}""".stripMargin,
        cause
    )
object HttpFieldDecodeException:
    def apply(fieldName: String, fieldType: String, method: String, url: String, cause: Throwable)(using Frame): HttpFieldDecodeException =
        new HttpFieldDecodeException(fieldName, fieldType, cause.getMessage, method, HttpException.stripQuery(url), cause)

/** Required field missing from the request. */
case class HttpMissingFieldException private (fieldName: String, fieldType: String, method: String, url: String)(using Frame)
    extends HttpDecodeException(
        s"""Missing required $fieldType '$fieldName'.
           |
           |  While processing: ${HttpException.showRequest(method, url)}
           |
           |  Add a default value via the route definition, or use the optional
           |  variant (e.g. queryOpt, headerOpt, cookieOpt).""".stripMargin
    )
object HttpMissingFieldException:
    def apply(fieldName: String, fieldType: String, method: String, url: String)(using Frame): HttpMissingFieldException =
        new HttpMissingFieldException(fieldName, fieldType, method, HttpException.stripQuery(url))

/** JSON body decode failed. */
case class HttpJsonDecodeException private (detail: String, method: String, url: String)(using Frame)
    extends HttpDecodeException(
        s"""JSON decode failed: $detail.
           |
           |  While processing: ${HttpException.showRequest(method, url)}""".stripMargin
    )
object HttpJsonDecodeException:
    def apply(detail: String, method: String, url: String)(using Frame): HttpJsonDecodeException =
        new HttpJsonDecodeException(detail, method, HttpException.stripQuery(url))

/** Form body decode failed. */
case class HttpFormDecodeException private (detail: String, method: String, url: String, cause: Text | Throwable)(using Frame)
    extends HttpDecodeException(
        s"""Form decode failed: $detail.
           |
           |  While processing: ${HttpException.showRequest(method, url)}""".stripMargin,
        cause
    )
object HttpFormDecodeException:
    def apply(detail: String, method: String, url: String, cause: Throwable)(using Frame): HttpFormDecodeException =
        new HttpFormDecodeException(cause.getMessage, method, HttpException.stripQuery(url), cause)
    def apply(detail: String, method: String, url: String)(using Frame): HttpFormDecodeException =
        new HttpFormDecodeException(detail, method, HttpException.stripQuery(url), "")
end HttpFormDecodeException

/** Unexpected Content-Type header. */
case class HttpUnsupportedMediaTypeException private (expected: String, actual: Maybe[String], method: String, url: String)(using Frame)
    extends HttpDecodeException(
        s"""Unexpected Content-Type.
           |
           |  Expected: $expected
           |  Received: ${actual.getOrElse("(no Content-Type header)")}
           |  While processing: ${HttpException.showRequest(method, url)}
           |
           |  Ensure the request includes the correct Content-Type header.""".stripMargin
    )
object HttpUnsupportedMediaTypeException:
    def apply(expected: String, actual: Maybe[String], method: String, url: String)(using Frame): HttpUnsupportedMediaTypeException =
        new HttpUnsupportedMediaTypeException(expected, actual, method, HttpException.stripQuery(url))

/** Cannot decode streaming content type as a buffered body. */
case class HttpStreamingDecodeException private (contentType: String, method: String, url: String)(using Frame)
    extends HttpDecodeException(
        s"""Cannot decode streaming content type '$contentType' as a buffered body.
           |
           |  While processing: ${HttpException.showRequest(method, url)}
           |
           |  Use a streaming handler for SSE, NDJSON, or multipart stream content types.""".stripMargin
    )
object HttpStreamingDecodeException:
    def apply(contentType: String, method: String, url: String)(using Frame): HttpStreamingDecodeException =
        new HttpStreamingDecodeException(contentType, method, HttpException.stripQuery(url))

/** Missing boundary parameter in Content-Type header for multipart request. */
case class HttpMissingBoundaryException private (method: String, url: String)(using Frame)
    extends HttpDecodeException(
        s"""Missing 'boundary' parameter in Content-Type header for multipart request.
           |
           |  While processing: ${HttpException.showRequest(method, url)}
           |
           |  Ensure the client sends a Content-Type like
           |  'multipart/form-data; boundary=...'.""".stripMargin
    )
object HttpMissingBoundaryException:
    def apply(method: String, url: String)(using Frame): HttpMissingBoundaryException =
        new HttpMissingBoundaryException(method, HttpException.stripQuery(url))

// --- WebSocket failures ---

/** WebSocket-specific failures.
  *
  * @see
  *   [[kyo.HttpWebSocketHandshakeException]] Server rejected the WebSocket upgrade
  */
sealed abstract class HttpWebSocketException(message: Text, cause: Text | Throwable = "")(using Frame)
    extends HttpException(message, cause)

/** WebSocket handshake rejected by the server. */
case class HttpWebSocketHandshakeException private (url: String, status: Int)(using Frame)
    extends HttpWebSocketException(
        s"""WebSocket handshake failed for $url.
           |
           |  Server responded with status $status.""".stripMargin
    )
object HttpWebSocketHandshakeException:
    def apply(url: String, status: Int)(using Frame): HttpWebSocketHandshakeException =
        new HttpWebSocketHandshakeException(HttpException.stripQuery(url), status)

// --- Protocol wire-level failures ---

/** HTTP wire protocol parse error (malformed request line, status line, headers, or body framing). */
case class HttpProtocolException private[kyo] (detail: String)(using Frame)
    extends HttpDecodeException(
        s"HTTP protocol error: $detail"
    )
