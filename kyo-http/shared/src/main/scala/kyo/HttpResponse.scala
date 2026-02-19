package kyo

import java.nio.charset.StandardCharsets
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Immutable HTTP response used on both sides of the wire — server code builds and sends responses, client code receives and inspects them.
  *
  * Like `HttpRequest`, the type parameter `B` tracks the body type. Body accessors (`bodyText`, `bodyAs[A]`, `bodyBytes`) require
  * `B <:< HttpBody.Bytes` evidence. `bodyStream` works for both body types by wrapping buffered content as a single-chunk stream.
  *
  * Responses are constructed via factory methods on the companion organized by status code category (2xx, 3xx, 4xx, 5xx). JSON responses
  * auto-serialize via `Schema`. Streaming responses are built with `stream`, `streamSse`, and `streamNdjson`. Builder methods return new
  * immutable instances. Cookie setting supports the full attribute set (SameSite, Secure, HttpOnly, MaxAge, Domain, Path).
  *
  *   - Status-named factory methods for common responses (`ok`, `notFound`, `badRequest`, `redirect`, etc.)
  *   - JSON body serialization via `Schema`
  *   - SSE and NDJSON streaming response factories
  *   - Cookie setting with full attribute support
  *   - Cache control, ETag, Content-Disposition, Content-Encoding, and Last-Modified builders
  *
  * `addHeader` appends without replacing (consistent with `HttpRequest.addHeader`). Use `setHeader` to replace existing headers with the
  * same name.
  *
  * `bodyAs[A]` wraps decode failure in `Abort[HttpError]`, consistent with `HttpRequest.bodyAs[A]`.
  *
  * Note: `header(name)` returns the last matching header (consistent with replace semantics). Compare with `HttpRequest.header(name)` which
  * returns the first.
  *
  * Note: Header lookups are case-insensitive, following HTTP/1.1 spec.
  *
  * @tparam B
  *   The body type, either `HttpBody.Bytes` or `HttpBody.Streamed`
  *
  * @see
  *   [[kyo.HttpRequest]]
  * @see
  *   [[kyo.HttpBody]]
  * @see
  *   [[kyo.HttpStatus]]
  * @see
  *   [[kyo.HttpResponse.Cookie]]
  * @see
  *   [[kyo.Schema]]
  */
final class HttpResponse[+B <: HttpBody] private (
    val status: HttpStatus,
    private val _headers: HttpHeaders,
    private val _cookies: Seq[HttpResponse.Cookie],
    private val _body: B
):
    def body: B = _body
    import HttpResponse.*

    // --- Header accessors ---

    def header(name: String): Maybe[String] =
        _headers.get(name)

    def headers: HttpHeaders = _headers

    def contentType: Maybe[String] = header("Content-Type")

    // --- Cookie accessors ---

    def cookie(name: String): Maybe[Cookie] =
        _cookies.find(_.name == name) match
            case Some(c) => Present(c)
            case None    => HttpResponse.parseCookieFromHeaders(_headers, name)

    def cookies: Seq[Cookie] =
        if _cookies.nonEmpty then _cookies
        else HttpResponse.parseCookiesFromHeaders(_headers)

    // --- Body accessors (type-safe via =:= evidence) ---

    /** Returns the response body as a String. Only available for buffered responses. */
    def bodyText(using ev: B <:< HttpBody.Bytes): String = ev(body).text

    /** Returns the response body as a Span[Byte]. Only available for buffered responses. */
    def bodyBytes(using ev: B <:< HttpBody.Bytes): Span[Byte] = ev(body).span

    /** Returns the Content-Length of the response body. Only available for buffered responses. */
    def contentLength(using ev: B <:< HttpBody.Bytes): Long = ev(body).data.length.toLong

    /** Parses the response body as type A via Schema. Fails with `Abort[HttpError.ParseError]` on decode failure. */
    def bodyAs[A: Schema](using ev: B <:< HttpBody.Bytes)(using Frame): A < Abort[HttpError] = ev(body).as[A]

    /** Returns the body as a byte stream. Works for both buffered and streaming responses. */
    def bodyStream(using Frame): Stream[Span[Byte], Async & Scope] =
        body.use(b => Stream.init(Chunk(b.span)), _.stream)

    // --- Builders (preserve body type) ---

    /** Appends a header to the header list. Does NOT replace existing headers with the same name. */
    def addHeader(name: String, value: String): HttpResponse[B] =
        new HttpResponse(status, _headers.add(name, value), _cookies, body)

    def addHeaders(headers: HttpHeaders): HttpResponse[B] =
        if headers.isEmpty then this
        else new HttpResponse(status, _headers.concat(headers), _cookies, body)

    /** Replaces any existing header with the same name (set semantics). */
    def setHeader(name: String, value: String): HttpResponse[B] =
        new HttpResponse(status, _headers.set(name, value), _cookies, body)

    def setHeaders(headers: HttpHeaders): HttpResponse[B] =
        if headers.isEmpty then this
        else
            new HttpResponse(status, headers.foldLeft(_headers)((h, k, v) => h.set(k, v)), _cookies, body)

    def addCookie(cookie: Cookie): HttpResponse[B] =
        new HttpResponse(status, _headers, _cookies :+ cookie, body)

    def addCookies(cookies: Cookie*): HttpResponse[B] =
        cookies.foldLeft(this)((r, c) => r.addCookie(c))

    def withBody[B2 <: HttpBody](newBody: B2): HttpResponse[B2] =
        new HttpResponse(status, _headers, _cookies, newBody)

    def contentDisposition(filename: String): HttpResponse[B] =
        contentDisposition(filename, isInline = false)

    def contentDisposition(filename: String, isInline: Boolean): HttpResponse[B] =
        val disposition = if isInline then "inline" else "attachment"
        setHeader("Content-Disposition", s"""$disposition; filename="$filename"""")

    def etag(value: String): HttpResponse[B] =
        val quotedEtag = if value.startsWith("\"") then value else s""""$value""""
        setHeader("ETag", quotedEtag)

    def lastModified(time: Instant): HttpResponse[B] =
        setHeader("Last-Modified", HttpResponse.httpDateFormatter.format(time.toJava))

    def cacheControl(directive: String): HttpResponse[B] =
        setHeader("Cache-Control", directive)

    def noCache: HttpResponse[B] = cacheControl("no-cache")

    def noStore: HttpResponse[B] = cacheControl("no-store")

    def contentEncoding(encoding: String): HttpResponse[B] =
        setHeader("Content-Encoding", encoding)

    // --- Private / Internal ---

    /** Returns headers with Set-Cookie headers for all cookies (pure Scala encoding). Used by backend implementations to convert cookies to
      * wire format.
      */
    private[kyo] def resolvedHeaders: HttpHeaders =
        if _cookies.isEmpty then _headers
        else _cookies.foldLeft(_headers)((h, c) => h.add("Set-Cookie", encodeCookie(c)))

    /** Returns a new response with cookies materialized as Set-Cookie headers. */
    private[kyo] def materializeCookies: HttpResponse[B] =
        if _cookies.isEmpty then this
        else new HttpResponse(status, resolvedHeaders, Seq.empty, _body)

    /** Materializes a streaming body into bytes, or returns self if already bytes. */
    private[kyo] def ensureBytes(using Frame): HttpResponse[HttpBody.Bytes] < Async =
        body.use(
            b => withBody(b),
            s =>
                Scope.run(s.stream.run).map { chunks =>
                    val totalSize = chunks.foldLeft(0)((acc, span) => acc + span.size)
                    val arr       = new Array[Byte](totalSize)
                    discard(chunks.foldLeft(0) { (pos, span) =>
                        val bytes = span.toArrayUnsafe
                        java.lang.System.arraycopy(bytes, 0, arr, pos, bytes.length)
                        pos + bytes.length
                    })
                    withBody(HttpBody(arr))
                }
        )

end HttpResponse

object HttpResponse:

    import HttpStatus.*

    // --- Factory methods (all return Bytes) ---

    def apply(status: HttpStatus, body: String = ""): HttpResponse[HttpBody.Bytes] =
        if body.isEmpty then new HttpResponse(status, HttpHeaders.empty, Seq.empty, HttpBody.empty)
        else initText(status, body, "text/plain")

    def apply[A: Schema](status: HttpStatus, body: A): HttpResponse[HttpBody.Bytes] =
        initJson(status, body)

    def apply(status: HttpStatus, body: Span[Byte]): HttpResponse[HttpBody.Bytes] =
        new HttpResponse(status, HttpHeaders.empty, Seq.empty, HttpBody(body.toArrayUnsafe))

    // --- 2xx Success ---

    def ok: HttpResponse[HttpBody.Bytes]                     = apply(OK)
    def ok(body: String): HttpResponse[HttpBody.Bytes]       = apply(OK, body)
    def ok(body: Span[Byte]): HttpResponse[HttpBody.Bytes]   = apply(OK, body)
    def ok[A: Schema](body: A): HttpResponse[HttpBody.Bytes] = initJson(OK, body)

    def json(body: String): HttpResponse[HttpBody.Bytes]                     = initText(OK, body, "application/json")
    def json(status: HttpStatus, body: String): HttpResponse[HttpBody.Bytes] = initText(status, body, "application/json")

    def created[A: Schema](body: A): HttpResponse[HttpBody.Bytes] = initJson(Created, body)
    def created[A: Schema](body: A, location: String): HttpResponse[HttpBody.Bytes] =
        initJson(Created, body).setHeader("Location", location)

    def accepted: HttpResponse[HttpBody.Bytes]                     = apply(Accepted)
    def accepted[A: Schema](body: A): HttpResponse[HttpBody.Bytes] = initJson(Accepted, body)

    def noContent: HttpResponse[HttpBody.Bytes] = apply(NoContent)

    // --- 3xx Redirection ---

    def redirect(url: String): HttpResponse[HttpBody.Bytes] = redirect(url, Found)
    def redirect(url: String, status: HttpStatus): HttpResponse[HttpBody.Bytes] =
        require(url.nonEmpty, "Redirect URL cannot be empty")
        new HttpResponse(status, HttpHeaders.empty.add("Location", url), Seq.empty, HttpBody.empty)

    def movedPermanently(url: String): HttpResponse[HttpBody.Bytes] = redirect(url, MovedPermanently)
    def notModified: HttpResponse[HttpBody.Bytes]                   = apply(NotModified)

    // --- 4xx Client Error ---

    def badRequest: HttpResponse[HttpBody.Bytes]                     = apply(BadRequest)
    def badRequest(body: String): HttpResponse[HttpBody.Bytes]       = apply(BadRequest, body)
    def badRequest[A: Schema](body: A): HttpResponse[HttpBody.Bytes] = initJson(BadRequest, body)

    def unauthorized: HttpResponse[HttpBody.Bytes]                     = apply(Unauthorized)
    def unauthorized(body: String): HttpResponse[HttpBody.Bytes]       = apply(Unauthorized, body)
    def unauthorized[A: Schema](body: A): HttpResponse[HttpBody.Bytes] = initJson(Unauthorized, body)

    def forbidden: HttpResponse[HttpBody.Bytes]                     = apply(Forbidden)
    def forbidden(body: String): HttpResponse[HttpBody.Bytes]       = apply(Forbidden, body)
    def forbidden[A: Schema](body: A): HttpResponse[HttpBody.Bytes] = initJson(Forbidden, body)

    def notFound: HttpResponse[HttpBody.Bytes]                     = apply(NotFound)
    def notFound(body: String): HttpResponse[HttpBody.Bytes]       = apply(NotFound, body)
    def notFound[A: Schema](body: A): HttpResponse[HttpBody.Bytes] = initJson(NotFound, body)

    def conflict: HttpResponse[HttpBody.Bytes]                     = apply(Conflict)
    def conflict(body: String): HttpResponse[HttpBody.Bytes]       = apply(Conflict, body)
    def conflict[A: Schema](body: A): HttpResponse[HttpBody.Bytes] = initJson(Conflict, body)

    def unprocessableEntity: HttpResponse[HttpBody.Bytes]                     = apply(UnprocessableEntity)
    def unprocessableEntity[A: Schema](body: A): HttpResponse[HttpBody.Bytes] = initJson(UnprocessableEntity, body)

    def tooManyRequests: HttpResponse[HttpBody.Bytes] = apply(TooManyRequests)
    def tooManyRequests(retryAfter: Duration): HttpResponse[HttpBody.Bytes] =
        new HttpResponse(
            TooManyRequests,
            HttpHeaders.empty.add("Retry-After", retryAfter.toSeconds.toString),
            Seq.empty,
            HttpBody.empty
        )

    // --- 5xx Server Error ---

    def serverError: HttpResponse[HttpBody.Bytes]                     = apply(InternalServerError)
    def serverError(body: String): HttpResponse[HttpBody.Bytes]       = apply(InternalServerError, body)
    def serverError[A: Schema](body: A): HttpResponse[HttpBody.Bytes] = initJson(InternalServerError, body)

    def serviceUnavailable: HttpResponse[HttpBody.Bytes] = apply(ServiceUnavailable)
    def serviceUnavailable(retryAfter: Duration): HttpResponse[HttpBody.Bytes] =
        new HttpResponse(
            ServiceUnavailable,
            HttpHeaders.empty.add("Retry-After", retryAfter.toSeconds.toString),
            Seq.empty,
            HttpBody.empty
        )

    // --- Streaming factory methods ---

    def stream(s: Stream[Span[Byte], Async & Scope], status: HttpStatus = OK): HttpResponse[HttpBody.Streamed] =
        new HttpResponse(status, HttpHeaders.empty, Seq.empty, HttpBody.stream(s))

    /** Creates a streaming response with a known Content-Length. The stream must produce exactly `contentLength` bytes total. When
      * Content-Length is set, the response is sent without chunked transfer encoding.
      */
    def stream(s: Stream[Span[Byte], Async & Scope], contentLength: Long, status: HttpStatus): HttpResponse[HttpBody.Streamed] =
        require(contentLength >= 0, s"contentLength must be non-negative: $contentLength")
        new HttpResponse(
            status,
            HttpHeaders.empty.add("Content-Length", contentLength.toString),
            Seq.empty,
            HttpBody.stream(s)
        )
    end stream

    /** Creates a streaming SSE response. Serializes events using Schema, sets appropriate SSE headers. */
    def streamSse[V: Schema: Tag](events: Stream[HttpEvent[V], Async], status: HttpStatus = OK)(using
        Frame,
        Tag[Emit[Chunk[HttpEvent[V]]]]
    ): HttpResponse[HttpBody.Streamed] =
        val schema = Schema[V]
        val byteStream = events.map { event =>
            val sb = new StringBuilder
            event.event.foreach(e => discard(sb.append("event: ").append(e).append('\n')))
            event.id.foreach(id => discard(sb.append("id: ").append(id).append('\n')))
            event.retry.foreach(d => discard(sb.append("retry: ").append(d.toMillis).append('\n')))
            discard(sb.append("data: ").append(schema.encode(event.data)).append('\n'))
            discard(sb.append('\n'))
            Span.fromUnsafe(sb.toString.getBytes(StandardCharsets.UTF_8))
        }
        new HttpResponse(
            status,
            HttpHeaders.empty.add("Content-Type", "text/event-stream").add("Cache-Control", "no-cache").add("Connection", "keep-alive"),
            Seq.empty,
            HttpBody.stream(byteStream)
        )
    end streamSse

    /** Creates a streaming NDJSON response. One JSON line per stream element. */
    def streamNdjson[V: Schema: Tag](values: Stream[V, Async], status: HttpStatus = OK)(using
        Frame,
        Tag[Emit[Chunk[V]]]
    ): HttpResponse[HttpBody.Streamed] =
        val schema = Schema[V]
        val byteStream = values.map { value =>
            Span.fromUnsafe((schema.encode(value) + "\n").getBytes(StandardCharsets.UTF_8))
        }
        new HttpResponse(
            status,
            HttpHeaders.empty.add("Content-Type", "application/x-ndjson"),
            Seq.empty,
            HttpBody.stream(byteStream)
        )
    end streamNdjson

    // --- Auxiliary types ---

    /** Response cookie with attributes for Set-Cookie header serialization. Builder methods for each attribute. */
    case class Cookie(
        name: String,
        value: String,
        maxAge: Maybe[Duration] = Absent,
        domain: Maybe[String] = Absent,
        path: Maybe[String] = Absent,
        secure: Boolean = false,
        httpOnly: Boolean = false,
        sameSite: Maybe[Cookie.SameSite] = Absent
    ) derives CanEqual:
        require(name.nonEmpty, "Cookie name cannot be empty")

        def maxAge(d: Duration): Cookie          = copy(maxAge = Present(d))
        def domain(d: String): Cookie            = copy(domain = Present(d))
        def path(p: String): Cookie              = copy(path = Present(p))
        def secure(b: Boolean): Cookie           = copy(secure = b)
        def httpOnly(b: Boolean): Cookie         = copy(httpOnly = b)
        def sameSite(s: Cookie.SameSite): Cookie = copy(sameSite = Present(s))
    end Cookie

    object Cookie:
        enum SameSite derives CanEqual:
            case Strict, Lax, None
        end SameSite
    end Cookie

    // --- Private helpers ---

    private val httpDateFormatter = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)

    private def initText(status: HttpStatus, body: String, contentType: String): HttpResponse[HttpBody.Bytes] =
        val bytes   = body.getBytes(StandardCharsets.UTF_8)
        val headers = HttpHeaders.empty.add("Content-Type", contentType)
        new HttpResponse(status, headers, Seq.empty, HttpBody(bytes))
    end initText

    private def initJson[A: Schema](status: HttpStatus, body: A): HttpResponse[HttpBody.Bytes] =
        initText(status, Schema[A].encode(body), "application/json")

    private[kyo] def initBytes(
        status: HttpStatus,
        headers: HttpHeaders,
        body: String
    ): HttpResponse[HttpBody.Bytes] =
        val bytes = body.getBytes(StandardCharsets.UTF_8)
        new HttpResponse(status, headers, Seq.empty, HttpBody(bytes))
    end initBytes

    private[kyo] def initBytes(
        status: HttpStatus,
        headers: HttpHeaders,
        body: Span[Byte]
    ): HttpResponse[HttpBody.Bytes] =
        new HttpResponse(status, headers, Seq.empty, HttpBody(body.toArrayUnsafe))
    end initBytes

    private[kyo] def initStreaming(
        status: HttpStatus,
        headers: HttpHeaders,
        stream: Stream[Span[Byte], Async & Scope]
    ): HttpResponse[HttpBody.Streamed] =
        new HttpResponse(status, headers, Seq.empty, HttpBody.stream(stream))

    /** Encode a Set-Cookie header value from a Cookie (pure Scala, no Netty). */
    private def encodeCookie(cookie: Cookie): String =
        val sb = new StringBuilder
        discard(sb.append(cookie.name).append('=').append(cookie.value))
        cookie.maxAge.foreach(d => discard(sb.append("; Max-Age=").append(d.toSeconds)))
        cookie.domain.foreach(d => discard(sb.append("; Domain=").append(d)))
        cookie.path.foreach(p => discard(sb.append("; Path=").append(p)))
        if cookie.secure then discard(sb.append("; Secure"))
        if cookie.httpOnly then discard(sb.append("; HttpOnly"))
        cookie.sameSite.foreach { ss =>
            val value = ss match
                case Cookie.SameSite.Strict => "Strict"
                case Cookie.SameSite.Lax    => "Lax"
                case Cookie.SameSite.None   => "None"
            discard(sb.append("; SameSite=").append(value))
        }
        sb.toString
    end encodeCookie

    /** Parse a single cookie by name from Set-Cookie headers. */
    private def parseCookieFromHeaders(headers: HttpHeaders, name: String): Maybe[Cookie] =
        var result: Maybe[Cookie] = Absent
        headers.foreach { (k, v) =>
            if result == Absent && k.equalsIgnoreCase("Set-Cookie") then
                parseSetCookieHeader(v) match
                    case Present(c) if c.name == name => result = Present(c)
                    case _                            =>
        }
        result
    end parseCookieFromHeaders

    /** Parse all cookies from Set-Cookie headers. */
    private def parseCookiesFromHeaders(headers: HttpHeaders): Seq[Cookie] =
        val builder = Seq.newBuilder[Cookie]
        headers.foreach { (k, v) =>
            if k.equalsIgnoreCase("Set-Cookie") then
                parseSetCookieHeader(v).foreach(c => builder += c)
        }
        builder.result()
    end parseCookiesFromHeaders

    /** Parse a Set-Cookie header value into a Cookie. Extracts name=value. */
    private def parseSetCookieHeader(header: String): Maybe[Cookie] =
        val semiIdx = header.indexOf(';')
        val nvPart  = if semiIdx >= 0 then header.substring(0, semiIdx).trim else header.trim
        val eqIdx   = nvPart.indexOf('=')
        if eqIdx <= 0 then Absent
        else
            val name  = nvPart.substring(0, eqIdx).trim
            val value = nvPart.substring(eqIdx + 1).trim
            if name.isEmpty then Absent
            else Present(Cookie(name, value))
        end if
    end parseSetCookieHeader

end HttpResponse
