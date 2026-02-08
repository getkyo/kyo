package kyo

import java.nio.charset.StandardCharsets
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import scala.annotation.tailrec

final class HttpResponse[+B <: HttpBody] private (
    val status: HttpResponse.Status,
    private val _headers: Seq[(String, String)],
    private val _cookies: Seq[HttpResponse.Cookie],
    private val _body: B
):
    def body: B = _body
    import HttpResponse.*

    // --- Header accessors ---

    def header(name: String): Maybe[String] =
        val lowerName = name.toLowerCase
        // Seq is correct: HTTP headers can have duplicates and order matters
        @tailrec def loop(remaining: Seq[(String, String)]): Maybe[String] =
            remaining match
                case Seq()                                         => Absent
                case Seq((n, v), _*) if n.toLowerCase == lowerName => Present(v)
                case Seq(_, tail*)                                 => loop(tail)
        loop(_headers.reverse) // reverse to get last value for duplicates
    end header

    def headers: Seq[(String, String)] = _headers

    def contentType: Maybe[String] = header("Content-Type")

    // --- Cookie accessors ---

    def cookie(name: String): Maybe[Cookie] =
        _cookies.find(_.name == name) match
            case Some(c) => Present(c)
            case None    => Absent

    def cookies: Seq[Cookie] = _cookies

    // --- Body accessors (type-safe via =:= evidence) ---

    /** Returns the response body as a String. Only available for buffered responses. */
    def bodyText(using ev: B <:< HttpBody.Bytes): String = ev(body).text

    /** Returns the response body as a Span[Byte]. Only available for buffered responses. */
    def bodyBytes(using ev: B <:< HttpBody.Bytes): Span[Byte] = ev(body).span

    /** Returns the Content-Length of the response body. Only available for buffered responses. */
    def contentLength(using ev: B <:< HttpBody.Bytes): Long = ev(body).data.length.toLong

    /** Parses the response body as type A. Only available for buffered responses. */
    def bodyAs[A: Schema](using ev: B <:< HttpBody.Bytes)(using Frame): A < Abort[HttpError] = ev(body).as[A]

    /** Returns the body as a byte stream. Works for both buffered and streaming responses. */
    def bodyStream(using Frame): Stream[Span[Byte], Async] =
        body match
            case b: HttpBody.Bytes    => Stream.init(Chunk(b.span))
            case s: HttpBody.Streamed => s.stream

    // --- Builders (preserve body type) ---

    def addHeader(name: String, value: String): HttpResponse[B] =
        val lowerName  = name.toLowerCase
        val newHeaders = _headers.filterNot(_._1.toLowerCase == lowerName) :+ (name -> value)
        new HttpResponse(status, newHeaders, _cookies, body)
    end addHeader

    def addHeaders(headers: (String, String)*): HttpResponse[B] =
        headers.foldLeft(this)((r, h) => r.addHeader(h._1, h._2))

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
        addHeader("Content-Disposition", s"""$disposition; filename="$filename"""")

    def etag(value: String): HttpResponse[B] =
        val quotedEtag = if value.startsWith("\"") then value else s""""$value""""
        addHeader("ETag", quotedEtag)

    def lastModified(time: java.time.Instant): HttpResponse[B] =
        addHeader("Last-Modified", HttpResponse.httpDateFormatter.format(time))

    def cacheControl(directive: String): HttpResponse[B] =
        addHeader("Cache-Control", directive)

    def noCache: HttpResponse[B] = cacheControl("no-cache")

    def noStore: HttpResponse[B] = cacheControl("no-store")

    def contentEncoding(encoding: String): HttpResponse[B] =
        addHeader("Content-Encoding", encoding)

    // --- Private / Internal ---

    /** Returns headers with Set-Cookie headers for all cookies (pure Scala encoding). Used by backend implementations to convert cookies to
      * wire format.
      */
    private[kyo] def resolvedHeaders: Seq[(String, String)] =
        if _cookies.isEmpty then _headers
        else _headers ++ _cookies.map(c => "Set-Cookie" -> encodeCookie(c))

    /** Materializes a streaming body into bytes, or returns self if already bytes. */
    private[kyo] def ensureBytes(using Frame): HttpResponse[HttpBody.Bytes] < Async =
        body match
            case _: HttpBody.Bytes =>
                this.asInstanceOf[HttpResponse[HttpBody.Bytes]]
            case s: HttpBody.Streamed =>
                s.stream.run.map { chunks =>
                    val totalSize = chunks.foldLeft(0)((acc, span) => acc + span.size)
                    val arr       = new Array[Byte](totalSize)
                    var pos       = 0
                    chunks.foreach { span =>
                        val bytes = span.toArrayUnsafe
                        java.lang.System.arraycopy(bytes, 0, arr, pos, bytes.length)
                        pos += bytes.length
                    }
                    withBody(HttpBody(arr))
                }

end HttpResponse

object HttpResponse:

    // --- Factory methods (all return Bytes) ---

    def apply(status: Status, body: String = ""): HttpResponse[HttpBody.Bytes] =
        if body.isEmpty then new HttpResponse(status, Seq.empty, Seq.empty, HttpBody.empty)
        else initText(status, body, "text/plain")

    def apply[A: Schema](status: Status, body: A): HttpResponse[HttpBody.Bytes] =
        initJson(status, body)

    // --- 2xx Success ---

    def ok: HttpResponse[HttpBody.Bytes]                     = apply(Status.OK)
    def ok(body: String): HttpResponse[HttpBody.Bytes]       = apply(Status.OK, body)
    def ok[A: Schema](body: A): HttpResponse[HttpBody.Bytes] = initJson(Status.OK, body)

    def json(body: String): HttpResponse[HttpBody.Bytes]                 = initText(Status.OK, body, "application/json")
    def json(status: Status, body: String): HttpResponse[HttpBody.Bytes] = initText(status, body, "application/json")

    def created[A: Schema](body: A): HttpResponse[HttpBody.Bytes] = initJson(Status.Created, body)
    def created[A: Schema](body: A, location: String): HttpResponse[HttpBody.Bytes] =
        initJson(Status.Created, body).addHeader("Location", location)

    def accepted: HttpResponse[HttpBody.Bytes]                     = apply(Status.Accepted)
    def accepted[A: Schema](body: A): HttpResponse[HttpBody.Bytes] = initJson(Status.Accepted, body)

    def noContent: HttpResponse[HttpBody.Bytes] = apply(Status.NoContent)

    // --- 3xx Redirection ---

    def redirect(url: String): HttpResponse[HttpBody.Bytes] = redirect(url, Status.Found)
    def redirect(url: String, status: Status): HttpResponse[HttpBody.Bytes] =
        require(url.nonEmpty, "Redirect URL cannot be empty")
        new HttpResponse(status, Seq("Location" -> url), Seq.empty, HttpBody.empty)

    def movedPermanently(url: String): HttpResponse[HttpBody.Bytes] = redirect(url, Status.MovedPermanently)
    def notModified: HttpResponse[HttpBody.Bytes]                   = apply(Status.NotModified)

    // --- 4xx Client Error ---

    def badRequest: HttpResponse[HttpBody.Bytes]                     = apply(Status.BadRequest)
    def badRequest(body: String): HttpResponse[HttpBody.Bytes]       = apply(Status.BadRequest, body)
    def badRequest[A: Schema](body: A): HttpResponse[HttpBody.Bytes] = initJson(Status.BadRequest, body)

    def unauthorized: HttpResponse[HttpBody.Bytes]                     = apply(Status.Unauthorized)
    def unauthorized(body: String): HttpResponse[HttpBody.Bytes]       = apply(Status.Unauthorized, body)
    def unauthorized[A: Schema](body: A): HttpResponse[HttpBody.Bytes] = initJson(Status.Unauthorized, body)

    def forbidden: HttpResponse[HttpBody.Bytes]                     = apply(Status.Forbidden)
    def forbidden(body: String): HttpResponse[HttpBody.Bytes]       = apply(Status.Forbidden, body)
    def forbidden[A: Schema](body: A): HttpResponse[HttpBody.Bytes] = initJson(Status.Forbidden, body)

    def notFound: HttpResponse[HttpBody.Bytes]                     = apply(Status.NotFound)
    def notFound(body: String): HttpResponse[HttpBody.Bytes]       = apply(Status.NotFound, body)
    def notFound[A: Schema](body: A): HttpResponse[HttpBody.Bytes] = initJson(Status.NotFound, body)

    def conflict: HttpResponse[HttpBody.Bytes]                     = apply(Status.Conflict)
    def conflict(body: String): HttpResponse[HttpBody.Bytes]       = apply(Status.Conflict, body)
    def conflict[A: Schema](body: A): HttpResponse[HttpBody.Bytes] = initJson(Status.Conflict, body)

    def unprocessableEntity: HttpResponse[HttpBody.Bytes]                     = apply(Status.UnprocessableEntity)
    def unprocessableEntity[A: Schema](body: A): HttpResponse[HttpBody.Bytes] = initJson(Status.UnprocessableEntity, body)

    def tooManyRequests: HttpResponse[HttpBody.Bytes] = apply(Status.TooManyRequests)
    def tooManyRequests(retryAfter: Duration): HttpResponse[HttpBody.Bytes] =
        new HttpResponse(Status.TooManyRequests, Seq("Retry-After" -> retryAfter.toSeconds.toString), Seq.empty, HttpBody.empty)

    // --- 5xx Server Error ---

    def serverError: HttpResponse[HttpBody.Bytes]                     = apply(Status.InternalServerError)
    def serverError(body: String): HttpResponse[HttpBody.Bytes]       = apply(Status.InternalServerError, body)
    def serverError[A: Schema](body: A): HttpResponse[HttpBody.Bytes] = initJson(Status.InternalServerError, body)

    def serviceUnavailable: HttpResponse[HttpBody.Bytes] = apply(Status.ServiceUnavailable)
    def serviceUnavailable(retryAfter: Duration): HttpResponse[HttpBody.Bytes] =
        new HttpResponse(Status.ServiceUnavailable, Seq("Retry-After" -> retryAfter.toSeconds.toString), Seq.empty, HttpBody.empty)

    // --- Streaming factory methods ---

    def stream(s: Stream[Span[Byte], Async], status: Status = Status.OK): HttpResponse[HttpBody.Streamed] =
        new HttpResponse(status, Seq.empty, Seq.empty, HttpBody.stream(s))

    def streamSse[V: Schema: Tag](events: Stream[ServerSentEvent[V], Async], status: Status = Status.OK)(using
        Frame,
        Tag[Emit[Chunk[ServerSentEvent[V]]]]
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
            Seq("Content-Type" -> "text/event-stream", "Cache-Control" -> "no-cache", "Connection" -> "keep-alive"),
            Seq.empty,
            HttpBody.stream(byteStream)
        )
    end streamSse

    def streamNdjson[V: Schema: Tag](values: Stream[V, Async], status: Status = Status.OK)(using
        Frame,
        Tag[Emit[Chunk[V]]]
    ): HttpResponse[HttpBody.Streamed] =
        val schema = Schema[V]
        val byteStream = values.map { value =>
            Span.fromUnsafe((schema.encode(value) + "\n").getBytes(StandardCharsets.UTF_8))
        }
        new HttpResponse(
            status,
            Seq("Content-Type" -> "application/x-ndjson"),
            Seq.empty,
            HttpBody.stream(byteStream)
        )
    end streamNdjson

    // --- Auxiliary types ---

    opaque type Status <: Int = Int

    object Status:

        inline given CanEqual[Status, Status] = CanEqual.derived

        def apply(code: Int): Status =
            require(code >= 100 && code <= 599, s"Invalid HTTP status code: $code")
            code

        extension (status: Status)
            def code: Int                = status
            def isInformational: Boolean = status >= 100 && status < 200
            def isSuccess: Boolean       = status >= 200 && status < 300
            def isRedirect: Boolean      = status >= 300 && status < 400
            def isClientError: Boolean   = status >= 400 && status < 500
            def isServerError: Boolean   = status >= 500 && status < 600
            def isError: Boolean         = status >= 400
        end extension

        // 1xx Informational
        val Continue: Status           = 100
        val SwitchingProtocols: Status = 101
        val Processing: Status         = 102
        val EarlyHints: Status         = 103

        // 2xx Success
        val OK: Status                   = 200
        val Created: Status              = 201
        val Accepted: Status             = 202
        val NonAuthoritativeInfo: Status = 203
        val NoContent: Status            = 204
        val ResetContent: Status         = 205
        val PartialContent: Status       = 206

        // 3xx Redirection
        val MultipleChoices: Status   = 300
        val MovedPermanently: Status  = 301
        val Found: Status             = 302
        val SeeOther: Status          = 303
        val NotModified: Status       = 304
        val UseProxy: Status          = 305
        val TemporaryRedirect: Status = 307
        val PermanentRedirect: Status = 308

        // 4xx Client Error
        val BadRequest: Status                  = 400
        val Unauthorized: Status                = 401
        val PaymentRequired: Status             = 402
        val Forbidden: Status                   = 403
        val NotFound: Status                    = 404
        val MethodNotAllowed: Status            = 405
        val NotAcceptable: Status               = 406
        val ProxyAuthRequired: Status           = 407
        val RequestTimeout: Status              = 408
        val Conflict: Status                    = 409
        val Gone: Status                        = 410
        val LengthRequired: Status              = 411
        val PreconditionFailed: Status          = 412
        val PayloadTooLarge: Status             = 413
        val URITooLong: Status                  = 414
        val UnsupportedMediaType: Status        = 415
        val RangeNotSatisfiable: Status         = 416
        val ExpectationFailed: Status           = 417
        val ImATeapot: Status                   = 418
        val MisdirectedRequest: Status          = 421
        val UnprocessableEntity: Status         = 422
        val Locked: Status                      = 423
        val FailedDependency: Status            = 424
        val TooEarly: Status                    = 425
        val UpgradeRequired: Status             = 426
        val PreconditionRequired: Status        = 428
        val TooManyRequests: Status             = 429
        val RequestHeaderFieldsTooLarge: Status = 431
        val UnavailableForLegalReasons: Status  = 451

        // 5xx Server Error
        val InternalServerError: Status     = 500
        val NotImplemented: Status          = 501
        val BadGateway: Status              = 502
        val ServiceUnavailable: Status      = 503
        val GatewayTimeout: Status          = 504
        val HTTPVersionNotSupported: Status = 505
        val VariantAlsoNegotiates: Status   = 506
        val InsufficientStorage: Status     = 507
        val LoopDetected: Status            = 508
        val NotExtended: Status             = 510
        val NetworkAuthRequired: Status     = 511

    end Status

    case class Cookie(
        name: String,
        value: String,
        maxAge: Maybe[Duration] = Absent,
        domain: Maybe[String] = Absent,
        path: Maybe[String] = Absent,
        secure: Boolean = false,
        httpOnly: Boolean = false,
        sameSite: Maybe[Cookie.SameSite] = Absent
    ):
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

    private def initText(status: Status, body: String, contentType: String): HttpResponse[HttpBody.Bytes] =
        val bytes   = body.getBytes(StandardCharsets.UTF_8)
        val headers = Seq("Content-Type" -> contentType, "Content-Length" -> bytes.length.toString)
        new HttpResponse(status, headers, Seq.empty, HttpBody(bytes))
    end initText

    private def initJson[A: Schema](status: Status, body: A): HttpResponse[HttpBody.Bytes] =
        initText(status, Schema[A].encode(body), "application/json")

    private[kyo] def initStreaming(
        status: Status,
        headers: Seq[(String, String)],
        stream: Stream[Span[Byte], Async]
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

end HttpResponse
