package kyo

import kyo.*

/** Base class for all HTTP-related errors, organized into four sealed subcategories by failure mode.
  *
  * The four subcategories map to distinct failure modes:
  *   - [[kyo.HttpConnectionException]], transport-level failures before a response is received (connection refused, pool exhausted)
  *   - [[kyo.HttpRequestException]], protocol-level failures during request processing (timeout, redirect loop, non-success status)
  *   - [[kyo.HttpServerException]], server-side operational failures (bind error, unhandled handler error)
  *   - [[kyo.HttpDecodeException]], parsing and deserialization failures (URL parse, field decode, JSON decode)
  *
  * All exception constructors strip query parameters from URLs before storing them, so sensitive data embedded in query strings (API keys,
  * tokens, session identifiers) is never retained in exception messages or stack traces.
  *
  * Client operations fail with `Abort[HttpException]` as their error channel. Match on the specific subtype to distinguish recoverable
  * transport failures (e.g., retry on `HttpConnectionException`) from unrecoverable protocol errors.
  *
  * @see
  *   [[kyo.HttpClient]] Client operations that can abort with `HttpException`
  * @see
  *   [[kyo.HttpConnectionException]] Transport-level failures
  * @see
  *   [[kyo.HttpRequestException]] Protocol-level failures
  * @see
  *   [[kyo.HttpDecodeException]] Parsing and deserialization failures
  */
sealed abstract class HttpException(message: String, cause: String | Throwable = "")(using Frame)
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
  *   [[kyo.HttpDnsResolutionException]] Name resolution failed for the host
  * @see
  *   [[kyo.HttpUnixConnectException]] Connection to a Unix domain socket failed
  * @see
  *   [[kyo.HttpPoolExhaustedException]] All connections to a host are in use
  */
sealed abstract class HttpConnectionException(message: String, cause: String | Throwable = "")(using Frame)
    extends HttpException(message, cause)

/** Connection refused or unreachable host. */
case class HttpConnectException(host: String, port: Int, cause: Throwable)(using Frame)
    extends HttpConnectionException(
        s"""Connection to $host:$port failed.
           |
           |  Verify the server is running and reachable.""".stripMargin,
        cause
    )

/** Name resolution for the request host failed (no such host, no address, temporary resolver failure). */
case class HttpDnsResolutionException(host: String, cause: Throwable)(using Frame)
    extends HttpConnectionException(
        s"""DNS resolution failed for $host.
           |
           |  Verify the hostname is correct and resolvable.""".stripMargin,
        cause
    )

/** Connection to a Unix domain socket failed (no such file, connection refused, permission denied). */
case class HttpUnixConnectException(path: String, cause: Throwable)(using Frame)
    extends HttpConnectionException(
        s"""Connection to Unix socket $path failed.
           |
           |  Verify the socket path exists and the server is listening.""".stripMargin,
        cause
    )

/** TCP connect exceeded the configured timeout. */
case class HttpConnectTimeoutException(host: String, port: Int, timeout: Duration)(using Frame)
    extends HttpConnectionException(
        s"""Connection to $host:$port timed out after $timeout."""
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
  *   [[kyo.HttpNonAsciiException]] A request host or path cannot be encoded as ASCII
  * @see
  *   [[kyo.HttpInvalidFieldException]] A field name is not a token, or a field carries a control character
  * @see
  *   [[kyo.HttpStatusException]] Non-success status code when the response body can't be decoded
  */
sealed abstract class HttpRequestException(message: String, cause: String | Throwable = "")(using Frame)
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

/** The host or the path of a request carries a char above 0x7F, which kyo-http cannot put on a request line.
  *
  * A request line is ASCII: a path must be percent-encoded and an internationalized host punycode-encoded before a request can carry them.
  * kyo-http does neither, so it rejects the value rather than truncate each char to its low byte and send a corrupted octet. A peer can reach
  * this, because the host and path of a `Location` header are followed on a redirect.
  *
  * This applies to the request line only. A field *value* may carry obs-text (`%x80-FF`) per RFC 9110 section 5.5, so a non-ASCII header
  * value is legal HTTP and is sent as its UTF-8 octets rather than reported here. A field *name* is a token, which is a stronger rule than
  * ASCII and is enforced by [[kyo.HttpInvalidFieldException]].
  *
  * `field` names the offending element (for example "the request path" or "the redirect host") but never carries its value, which can hold a
  * credential.
  */
case class HttpNonAsciiException private[kyo] (field: String)(using Frame)
    extends HttpRequestException(
        s"""Cannot send $field: it contains a char above 0x7F.
           |
           |  An HTTP/1.1 request line is ASCII. Percent-encode a non-ASCII path and
           |  punycode-encode an internationalized host before sending, or drop the
           |  value.""".stripMargin
    )

/** A field name is not a token, or a field value or request-line element carries a control character, so kyo-http has no valid wire form for
  * it.
  *
  * A field name is a `token` (RFC 9110 section 5.6.2), which admits letters, digits and a fixed set of symbols but neither SP nor colon: a
  * name carrying either is read by a recipient as a different name and value than the sender meant. A field value admits SP, HTAB, visible
  * characters and obs-text, and no other control character. The rule that matters most is that a CR or an LF ends a header line or a request
  * line early, so a recipient reads one line as two: that is response splitting and request smuggling (RFC 9112 section 11), and it is why
  * an ASCII check is no substitute, CR and LF being ASCII themselves.
  *
  * kyo-http refuses such a field rather than rewriting it. RFC 9110 section 5.5 offers a recipient the choice of rejecting the message or
  * replacing each offending character with SP, but that remedy is addressed to recipients; silently rewriting an application's own data on
  * the way out is worse than refusing to send it.
  *
  * `field` names the offending element (for example "the value of header 'X-Trace'") but never carries its value, which can hold a
  * credential, and never quotes back a name that is not a token, which can carry a line break of its own.
  */
case class HttpInvalidFieldException private[kyo] (field: String)(using Frame)
    extends HttpRequestException(
        s"""Cannot send $field: it is not a valid HTTP/1.1 field.
           |
           |  A field name must be a token (RFC 9110 section 5.6.2): letters, digits,
           |  and the symbols !#$$%&'*+-.^_`|~ . A field value must carry no control
           |  character other than HTAB; a CR or an LF would let a recipient read the
           |  header line as two. Remove the offending characters before sending.""".stripMargin
    )

/** Non-success status code when the response body can't be decoded. */
case class HttpStatusException private (status: HttpStatus, method: String, url: String, body: Maybe[String])(using Frame)
    extends HttpRequestException(
        s"${HttpException.showRequest(method, url)} returned ${status.code} (${status.name})." +
            body.map(b => s" Body: ${if b.length > 500 then b.take(500) + "..." else b}").getOrElse("")
    )
object HttpStatusException:
    def apply(status: HttpStatus, method: String, url: String)(using Frame): HttpStatusException =
        new HttpStatusException(status, method, HttpException.stripQuery(url), Absent)
    def apply(status: HttpStatus, method: String, url: String, body: String)(using Frame): HttpStatusException =
        new HttpStatusException(status, method, HttpException.stripQuery(url), Present(body))
end HttpStatusException

// --- Server (server-side operational failures) ---

/** Server-side operational failures.
  *
  * @see
  *   [[kyo.HttpBindException]] Server failed to bind to a port
  * @see
  *   [[kyo.HttpHandlerException]] Unhandled error from a route handler
  */
sealed abstract class HttpServerException(message: String, cause: String | Throwable = "")(using Frame)
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
sealed abstract class HttpDecodeException(message: String, cause: String | Throwable = "")(using Frame)
    extends HttpException(message, cause)

/** Failed to parse a URL. */
case class HttpUrlParseException private (url: String, detail: String, cause: String | Throwable)(using Frame)
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

/** A message body could not be decoded because its transfer framing is malformed: a chunk-size line with an embedded
  * CR or LF, a bare-LF line ending, an invalid chunk size, or a missing CRLF after chunk data. Accepting such framing
  * lets a recipient disagree with an upstream about where the body ends, a request-smuggling desync (RFC 9112 section
  * 7.1.1; CVE-2025-22871, CVE-2026-2332, CVE-2026-33870).
  */
case class HttpMalformedBodyException private[kyo] (detail: String)(using Frame)
    extends HttpDecodeException(s"Malformed chunked body framing: $detail.")

/** Failed to decode a path capture, query parameter, header, or cookie field. */
case class HttpFieldDecodeException private (
    fieldName: String,
    fieldType: String,
    detail: String,
    method: String,
    url: String,
    cause: String | Throwable
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
case class HttpFormDecodeException private (detail: String, method: String, url: String, cause: String | Throwable)(using Frame)
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

// --- HttpWebSocket failures ---

/** HttpWebSocket-specific failures.
  *
  * @see
  *   [[kyo.HttpWebSocketHandshakeException]] Server rejected the HttpWebSocket upgrade
  */
sealed abstract class HttpWebSocketException(message: String, cause: String | Throwable = "")(using Frame)
    extends HttpException(message, cause)

/** HttpWebSocket handshake rejected by the server. */
case class HttpWebSocketHandshakeException private (url: String, status: Int)(using Frame)
    extends HttpWebSocketException(
        s"""HttpWebSocket handshake failed for $url.
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

/** A response body exceeds the client's configured `HttpClientConfig.maxResponseLength`. The client rejects an oversized response (by its
  * declared `Content-Length` or its accumulated bytes) rather than buffer it without bound (CWE-400); `bodySize` is the offending size, `maxSize`
  * the configured cap.
  */
case class HttpPayloadTooLargeException private[kyo] (bodySize: Int, maxSize: Int)(using Frame)
    extends HttpDecodeException(
        s"Response body size $bodySize exceeds the configured maximum $maxSize"
    )

/** Connection closed cleanly (EOF). Not an error, normal keep-alive termination. */
case class HttpConnectionClosedException private[kyo] ()(using Frame)
    extends HttpDecodeException("Connection closed")
