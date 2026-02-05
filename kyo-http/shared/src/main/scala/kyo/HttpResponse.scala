package kyo

import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.cookie.DefaultCookie
import io.netty.handler.codec.http.cookie.ServerCookieEncoder
import java.nio.charset.StandardCharsets
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kyo.internal.Utf8StreamDecoder
import scala.annotation.tailrec

final class HttpResponse private (
    val status: HttpResponse.Status,
    private val _headers: Seq[(String, String)],
    private val _cookies: Seq[HttpResponse.Cookie],
    private val _body: Either[Array[Byte], Stream[Chunk[Byte], Async]]
):
    import HttpResponse.*

    // --- Body accessors ---

    def bodyText(using Frame): String < Abort[HttpError] =
        _body match
            case Left(bytes) => new String(bytes, StandardCharsets.UTF_8)
            case Right(_) => Abort.fail(HttpError.StreamingBody("Cannot access bodyText on streaming response; use bodyAsStream instead"))

    def bodyBytes(using Frame): Span[Byte] < Abort[HttpError] =
        _body match
            case Left(bytes) => Span.fromUnsafe(bytes)
            case Right(_) => Abort.fail(HttpError.StreamingBody("Cannot access bodyBytes on streaming response; use bodyAsStream instead"))

    def bodyAs[A: Schema](using Frame): A < Abort[HttpError] =
        bodyText.map { text =>
            try Schema[A].decode(text)
            catch case e: Throwable => Abort.fail(HttpError.ParseError(s"Failed to parse response body", e))
        }

    // TODO it seems this is the only streaming API we have? How does the body become streaming or not? Don't we need streaming apis in client and server?
    def bodyAsStream[A: Schema: Tag](using Tag[Emit[Chunk[A]]], Frame): Stream[A, Async] =
        _body match
            case Left(bytes) =>
                val text = new String(bytes, StandardCharsets.UTF_8)
                Stream.init(Seq(Schema[A].decode(text)))
            case Right(byteStream) =>
                // Use stateful UTF-8 decoder to handle multi-byte characters split across chunks
                val decoder = Utf8StreamDecoder()
                byteStream.map { chunk =>
                    val text = decoder.decode(chunk)
                    Schema[A].decode(text)
                }

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

    def contentLength: Maybe[Long] =
        _body match
            case Left(bytes) => Present(bytes.length.toLong)
            case Right(_)    => Absent

    // --- Cookie accessors ---

    def cookie(name: String): Maybe[Cookie] =
        _cookies.find(_.name == name) match
            case Some(c) => Present(c)
            case None    => Absent

    def cookies: Seq[Cookie] = _cookies

    // --- Builders ---

    def addHeader(name: String, value: String): HttpResponse =
        val lowerName  = name.toLowerCase
        val newHeaders = _headers.filterNot(_._1.toLowerCase == lowerName) :+ (name -> value)
        new HttpResponse(status, newHeaders, _cookies, _body)
    end addHeader

    def addHeaders(headers: (String, String)*): HttpResponse =
        headers.foldLeft(this)((r, h) => r.addHeader(h._1, h._2))

    def addCookie(cookie: Cookie): HttpResponse =
        new HttpResponse(status, _headers, _cookies :+ cookie, _body)

    def addCookies(cookies: Cookie*): HttpResponse =
        cookies.foldLeft(this)((r, c) => r.addCookie(c))

    def contentDisposition(filename: String): HttpResponse =
        contentDisposition(filename, isInline = false)

    def contentDisposition(filename: String, isInline: Boolean): HttpResponse =
        val disposition = if isInline then "inline" else "attachment"
        addHeader("Content-Disposition", s"""$disposition; filename="$filename"""")

    def etag(value: String): HttpResponse =
        val quotedEtag = if value.startsWith("\"") then value else s""""$value""""
        addHeader("ETag", quotedEtag)

    def lastModified(time: java.time.Instant): HttpResponse =
        addHeader("Last-Modified", HttpResponse.httpDateFormatter.format(time))

    def cacheControl(directive: String): HttpResponse =
        addHeader("Cache-Control", directive)

    def noCache: HttpResponse = cacheControl("no-cache")

    def noStore: HttpResponse = cacheControl("no-store")

    def contentEncoding(encoding: String): HttpResponse =
        addHeader("Content-Encoding", encoding)

    // --- Private / Internal ---

    private[kyo] def toNetty: FullHttpResponse =
        val content = _body match
            case Left(bytes) =>
                if bytes.isEmpty then Unpooled.EMPTY_BUFFER
                else Unpooled.wrappedBuffer(bytes)
            case Right(_) =>
                Unpooled.EMPTY_BUFFER

        val nettyResponse = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.valueOf(status),
            content
        )

        _headers.foreach { case (name, value) =>
            discard(nettyResponse.headers().set(name, value))
        }

        _cookies.foreach { cookie =>
            val nettyCookie = new DefaultCookie(cookie.name, cookie.value)
            cookie.maxAge.foreach(d => nettyCookie.setMaxAge(d.toSeconds))
            cookie.domain.foreach(nettyCookie.setDomain)
            cookie.path.foreach(nettyCookie.setPath)
            nettyCookie.setSecure(cookie.secure)
            nettyCookie.setHttpOnly(cookie.httpOnly)
            cookie.sameSite.foreach { ss =>
                import io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite as NettySameSite
                val nettySameSite = ss match
                    case Cookie.SameSite.Strict => NettySameSite.Strict
                    case Cookie.SameSite.Lax    => NettySameSite.Lax
                    case Cookie.SameSite.None   => NettySameSite.None
                nettyCookie.setSameSite(nettySameSite)
            }
            discard(nettyResponse.headers().add(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.STRICT.encode(nettyCookie)))
        }

        if !nettyResponse.headers().contains(HttpHeaderNames.CONTENT_LENGTH) then
            discard(nettyResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes()))

        nettyResponse
    end toNetty

    private[kyo] def isStreaming: Boolean = _body.isRight

    private[kyo] def bodyStream: Maybe[Stream[Chunk[Byte], Async]] =
        _body match
            case Right(stream) => Present(stream)
            case Left(_)       => Absent

end HttpResponse

object HttpResponse:

    // --- Factory methods ---

    def apply(status: Status, body: String = ""): HttpResponse =
        if body.isEmpty then new HttpResponse(status, Seq.empty, Seq.empty, Left(Array.empty))
        else initText(status, body, "text/plain")

    def apply[A: Schema](status: Status, body: A): HttpResponse =
        initJson(status, body)

    // --- 2xx Success ---

    def ok: HttpResponse                     = apply(Status.OK)
    def ok(body: String): HttpResponse       = apply(Status.OK, body)
    def ok[A: Schema](body: A): HttpResponse = initJson(Status.OK, body)

    def json(body: String): HttpResponse                 = initText(Status.OK, body, "application/json")
    def json(status: Status, body: String): HttpResponse = initText(status, body, "application/json")

    def created[A: Schema](body: A): HttpResponse = initJson(Status.Created, body)
    def created[A: Schema](body: A, location: String): HttpResponse =
        initJson(Status.Created, body).addHeader("Location", location)

    def accepted: HttpResponse                     = apply(Status.Accepted)
    def accepted[A: Schema](body: A): HttpResponse = initJson(Status.Accepted, body)

    def noContent: HttpResponse = apply(Status.NoContent)

    // --- 3xx Redirection ---

    def redirect(url: String): HttpResponse = redirect(url, Status.Found)
    def redirect(url: String, status: Status): HttpResponse =
        require(url.nonEmpty, "Redirect URL cannot be empty")
        new HttpResponse(status, Seq("Location" -> url), Seq.empty, Left(Array.empty))

    def movedPermanently(url: String): HttpResponse = redirect(url, Status.MovedPermanently)
    def notModified: HttpResponse                   = apply(Status.NotModified)

    // --- 4xx Client Error ---

    def badRequest: HttpResponse                     = apply(Status.BadRequest)
    def badRequest(body: String): HttpResponse       = apply(Status.BadRequest, body)
    def badRequest[A: Schema](body: A): HttpResponse = initJson(Status.BadRequest, body)

    def unauthorized: HttpResponse                     = apply(Status.Unauthorized)
    def unauthorized(body: String): HttpResponse       = apply(Status.Unauthorized, body)
    def unauthorized[A: Schema](body: A): HttpResponse = initJson(Status.Unauthorized, body)

    def forbidden: HttpResponse                     = apply(Status.Forbidden)
    def forbidden(body: String): HttpResponse       = apply(Status.Forbidden, body)
    def forbidden[A: Schema](body: A): HttpResponse = initJson(Status.Forbidden, body)

    def notFound: HttpResponse                     = apply(Status.NotFound)
    def notFound(body: String): HttpResponse       = apply(Status.NotFound, body)
    def notFound[A: Schema](body: A): HttpResponse = initJson(Status.NotFound, body)

    def conflict: HttpResponse                     = apply(Status.Conflict)
    def conflict(body: String): HttpResponse       = apply(Status.Conflict, body)
    def conflict[A: Schema](body: A): HttpResponse = initJson(Status.Conflict, body)

    def unprocessableEntity: HttpResponse                     = apply(Status.UnprocessableEntity)
    def unprocessableEntity[A: Schema](body: A): HttpResponse = initJson(Status.UnprocessableEntity, body)

    def tooManyRequests: HttpResponse = apply(Status.TooManyRequests)
    def tooManyRequests(retryAfter: Duration): HttpResponse =
        new HttpResponse(Status.TooManyRequests, Seq("Retry-After" -> retryAfter.toSeconds.toString), Seq.empty, Left(Array.empty))

    // --- 5xx Server Error ---

    def serverError: HttpResponse                     = apply(Status.InternalServerError)
    def serverError(body: String): HttpResponse       = apply(Status.InternalServerError, body)
    def serverError[A: Schema](body: A): HttpResponse = initJson(Status.InternalServerError, body)

    def serviceUnavailable: HttpResponse = apply(Status.ServiceUnavailable)
    def serviceUnavailable(retryAfter: Duration): HttpResponse =
        new HttpResponse(Status.ServiceUnavailable, Seq("Retry-After" -> retryAfter.toSeconds.toString), Seq.empty, Left(Array.empty))

    // --- Streaming ---

    def stream[A: Schema: Tag](body: Stream[A, Async])(using Tag[Emit[Chunk[A]]], Frame): HttpResponse =
        stream(Status.OK, body)

    def stream[A: Schema: Tag](status: Status, body: Stream[A, Async])(using Tag[Emit[Chunk[A]]], Frame): HttpResponse =
        val byteStream = body.map { a =>
            Chunk.from(Schema[A].encode(a).getBytes(StandardCharsets.UTF_8))
        }
        new HttpResponse(status, Seq("Content-Type" -> "application/json"), Seq.empty, Right(byteStream))
    end stream

    def sse(events: Stream[String, Async])(using Frame): HttpResponse =
        val byteStream = events.map { event =>
            Chunk.from(s"data: $event\n\n".getBytes(StandardCharsets.UTF_8))
        }
        new HttpResponse(Status.OK, Seq("Content-Type" -> "text/event-stream"), Seq.empty, Right(byteStream))
    end sse

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

    private def initText(status: Status, body: String, contentType: String): HttpResponse =
        val bytes   = body.getBytes(StandardCharsets.UTF_8)
        val headers = Seq("Content-Type" -> contentType, "Content-Length" -> bytes.length.toString)
        new HttpResponse(status, headers, Seq.empty, Left(bytes))
    end initText

    private def initJson[A: Schema](status: Status, body: A): HttpResponse =
        initText(status, Schema[A].encode(body), "application/json")

end HttpResponse
