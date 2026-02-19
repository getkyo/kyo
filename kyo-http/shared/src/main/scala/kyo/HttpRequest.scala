package kyo

import java.nio.charset.StandardCharsets
import kyo.internal.MultipartUtil
import scala.annotation.tailrec

/** Immutable HTTP request used on both sides of the wire — client code builds and sends requests, server code receives and inspects them.
  *
  * The type parameter `B` tracks the body type at compile time: `HttpBody.Bytes` for fully-buffered bodies, `HttpBody.Streamed` for
  * streaming bodies. Body accessors like `bodyText`, `bodyAs`, and `bodyBytes` are only available when `B <:< HttpBody.Bytes`, enforced via
  * implicit evidence. Requests are constructed via factory methods on the companion and modified using builder methods that return new
  * immutable instances.
  *
  * For server-side use, path parameters are populated by the router after matching and are accessible via `pathParam`/`pathParams`. Query
  * parameters are parsed lazily from the raw query string. Cookies are parsed from the `Cookie` header with configurable strict/lax mode.
  *
  *   - Type-safe body access (`bodyText`, `bodyAs[A]`, `bodyBytes`, `bodyStream`) gated by compile-time evidence
  *   - JSON, text, form-encoded, and multipart body factories
  *   - Streaming request factory for chunked uploads
  *   - Query parameter, path parameter, header, and cookie accessors
  *   - Common header convenience accessors (`authorization`, `accept`, `userAgent`, etc.)
  *   - Immutable builder pattern for headers
  *
  * `bodyAs[A]` wraps decode failure in `Abort[HttpError]`, consistent with `HttpResponse.bodyAs[A]`.
  *
  * Note: `addHeader` appends to the header list (does NOT replace). Compare with `HttpResponse.addHeader` which replaces.
  *
  * Note: `header(name)` returns the first matching header. Compare with `HttpResponse.header(name)` which returns the last.
  *
  * Note: Header lookups are case-insensitive, following HTTP/1.1 spec.
  *
  * @tparam B
  *   The body type, either `HttpBody.Bytes` or `HttpBody.Streamed`
  *
  * @see
  *   [[kyo.HttpResponse]]
  * @see
  *   [[kyo.HttpBody]]
  * @see
  *   [[kyo.HttpHandler]]
  * @see
  *   [[kyo.Schema]]
  */
final case class HttpRequest[+B <: HttpBody] private (
    method: HttpRequest.Method,
    httpUrl: HttpUrl,
    headers: HttpHeaders,
    body: B,
    contentType: Maybe[String],
    pathParams: Map[String, String],
    strictCookieParsing: Boolean
):
    import HttpRequest.*

    // --- URL/Path accessors ---

    /** Returns the request URI (path and query string, without scheme/host). */
    def url: String =
        httpUrl.rawQuery match
            case Present(q) => s"${httpUrl.rawPath}?$q"
            case Absent     => httpUrl.rawPath
    end url

    /** Returns just the path without query string. */
    def path: String = httpUrl.rawPath

    /** Returns the full URL including scheme and host. */
    def fullUrl: String = httpUrl.full

    def host: String = httpUrl.host

    def port: Int = httpUrl.port

    // --- Header accessors ---

    def header(name: String): Maybe[String] =
        headers.get(name)

    // --- Cookie accessors ---

    def cookie(name: String): Maybe[Cookie] =
        cookies.find(_.name == name)

    def cookie(name: String, strict: Boolean): Maybe[Cookie] =
        cookies(strict).find(_.name == name)

    /** Parse cookies using server's default mode (LAX unless configured for STRICT). */
    def cookies: Span[Cookie] =
        cookies(strictCookieParsing)

    /** Parse cookies with explicit mode selection. */
    def cookies(strict: Boolean): Span[Cookie] =
        header("Cookie") match
            case Absent => Span.empty[Cookie]
            case Present(cookieHeader) =>
                parseCookies(cookieHeader, strict)

    // --- Query parameter accessors ---

    def query(name: String): Maybe[String] =
        httpUrl.rawQuery match
            case Absent     => Absent
            case Present(q) => parseQueryParam(q, name)
    end query

    def queryAll(name: String): Seq[String] =
        httpUrl.rawQuery match
            case Absent     => Seq.empty
            case Present(q) => parseQueryParamAll(q, name)
    end queryAll

    // --- Path parameter accessors ---

    def pathParam(name: String): Maybe[String] =
        pathParams.get(name) match
            case Some(v) => Present(v)
            case None    => Absent

    // --- Common header accessors ---

    def authorization: Maybe[String]   = header("Authorization")
    def accept: Maybe[String]          = header("Accept")
    def userAgent: Maybe[String]       = header("User-Agent")
    def acceptLanguage: Maybe[String]  = header("Accept-Language")
    def acceptEncoding: Maybe[String]  = header("Accept-Encoding")
    def cacheControl: Maybe[String]    = header("Cache-Control")
    def ifNoneMatch: Maybe[String]     = header("If-None-Match")
    def ifModifiedSince: Maybe[String] = header("If-Modified-Since")

    // --- Body accessors (type-safe via =:= evidence) ---

    def bodyText(using ev: B <:< HttpBody.Bytes): String =
        val data = ev(body).data
        if data.isEmpty then "" else new String(data, StandardCharsets.UTF_8)

    def bodyBytes(using ev: B <:< HttpBody.Bytes): Span[Byte] = ev(body).span

    def bodyAs[A: Schema](using ev: B <:< HttpBody.Bytes)(using Frame): A < Abort[HttpError] =
        Abort.get(Schema[A].decode(bodyText).mapFailure(HttpError.ParseError(_)))

    def parts(using ev: B <:< HttpBody.Bytes): Span[HttpRequest.Part] =
        contentType match
            case Present(ct) if ct.startsWith("multipart/form-data") =>
                MultipartUtil.extractBoundary(ct) match
                    case Present(boundary) =>
                        parseMultipart(ev(body).data, boundary)
                    case Absent =>
                        Span.empty[HttpRequest.Part]
            case _ =>
                Span.empty[HttpRequest.Part]
    end parts

    def bodyStream(using ev: B <:< HttpBody.Streamed)(using Frame): Stream[Span[Byte], Async & Scope] = ev(body).stream

    def bodyStreamUniversal(using Frame): Stream[Span[Byte], Async & Scope] =
        body.use(b => Stream.init(Chunk(b.span)), _.stream)

    // --- Builder methods ---

    def addHeader(name: String, value: String): HttpRequest[B] =
        copy(headers = headers.add(name, value))

    def setHeader(name: String, value: String): HttpRequest[B] =
        copy(headers = headers.set(name, value))

    def setHeaders(newHeaders: HttpHeaders): HttpRequest[B] =
        var h = headers
        newHeaders.foreach((name, value) => h = h.set(name, value))
        copy(headers = h)
    end setHeaders

    def addHeaders(newHeaders: HttpHeaders): HttpRequest[B] =
        copy(headers = headers.concat(newHeaders))

    def withHttpUrl(url: HttpUrl): HttpRequest[B] =
        copy(httpUrl = url)

    def withStrictCookieParsing(v: Boolean): HttpRequest[B] =
        copy(strictCookieParsing = v)

end HttpRequest

object HttpRequest:

    // --- Factory methods for client-side requests ---

    def get(url: String, headers: HttpHeaders = HttpHeaders.empty): HttpRequest[HttpBody.Bytes] =
        create(Method.GET, url, Array.empty, headers, Absent)

    def post[A: Schema](url: String, body: A, headers: HttpHeaders = HttpHeaders.empty): HttpRequest[HttpBody.Bytes] =
        val json = Schema[A].encode(body)
        create(Method.POST, url, json.getBytes(StandardCharsets.UTF_8), headers, Present("application/json"))

    def postText(url: String, body: String, headers: HttpHeaders = HttpHeaders.empty): HttpRequest[HttpBody.Bytes] =
        create(Method.POST, url, body.getBytes(StandardCharsets.UTF_8), headers, Present("text/plain; charset=utf-8"))

    def postForm(url: String, fields: Seq[(String, String)], headers: HttpHeaders = HttpHeaders.empty): HttpRequest[HttpBody.Bytes] =
        val body = fields.map { case (k, v) =>
            java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
        }.mkString("&")
        create(Method.POST, url, body.getBytes(StandardCharsets.UTF_8), headers, Present("application/x-www-form-urlencoded"))
    end postForm

    def put[A: Schema](url: String, body: A, headers: HttpHeaders = HttpHeaders.empty): HttpRequest[HttpBody.Bytes] =
        val json = Schema[A].encode(body)
        create(Method.PUT, url, json.getBytes(StandardCharsets.UTF_8), headers, Present("application/json"))

    def putText(url: String, body: String, headers: HttpHeaders = HttpHeaders.empty): HttpRequest[HttpBody.Bytes] =
        create(Method.PUT, url, body.getBytes(StandardCharsets.UTF_8), headers, Present("text/plain; charset=utf-8"))

    def patch[A: Schema](url: String, body: A, headers: HttpHeaders = HttpHeaders.empty): HttpRequest[HttpBody.Bytes] =
        val json = Schema[A].encode(body)
        create(Method.PATCH, url, json.getBytes(StandardCharsets.UTF_8), headers, Present("application/json"))

    def patchText(url: String, body: String, headers: HttpHeaders = HttpHeaders.empty): HttpRequest[HttpBody.Bytes] =
        create(Method.PATCH, url, body.getBytes(StandardCharsets.UTF_8), headers, Present("text/plain; charset=utf-8"))

    def delete(url: String, headers: HttpHeaders = HttpHeaders.empty): HttpRequest[HttpBody.Bytes] =
        create(Method.DELETE, url, Array.empty, headers, Absent)

    def head(url: String, headers: HttpHeaders = HttpHeaders.empty): HttpRequest[HttpBody.Bytes] =
        create(Method.HEAD, url, Array.empty, headers, Absent)

    def options(url: String, headers: HttpHeaders = HttpHeaders.empty): HttpRequest[HttpBody.Bytes] =
        create(Method.OPTIONS, url, Array.empty, headers, Absent)

    def multipart(url: String, parts: Seq[Part], headers: HttpHeaders = HttpHeaders.empty): HttpRequest[HttpBody.Bytes] =
        val boundary    = "----" + java.util.UUID.randomUUID().toString
        val contentType = s"multipart/form-data; boundary=$boundary"
        val body        = buildMultipartBody(parts, boundary)
        create(Method.POST, url, body, headers, Present(contentType))
    end multipart

    // --- Streaming factory method ---

    def stream(
        method: Method,
        url: String,
        body: Stream[Span[Byte], Async & Scope],
        headers: HttpHeaders = HttpHeaders.empty,
        contentType: Maybe[String] = Absent
    ): HttpRequest[HttpBody.Streamed] =
        require(url.nonEmpty, "URL cannot be empty")
        val parsedUrl       = HttpUrl(url)
        val resolvedHeaders = parsedUrl.ensureHostHeader(headers)
        val resolvedCt      = resolvedHeaders.get("Content-Type").orElse(contentType)
        HttpRequest(method, parsedUrl, resolvedHeaders, HttpBody.stream(body), resolvedCt, Map.empty, false)
    end stream

    // --- Generic factory methods ---

    def init[A: Schema](
        method: Method,
        url: String,
        body: A,
        headers: HttpHeaders = HttpHeaders.empty
    ): HttpRequest[HttpBody.Bytes] =
        val json = Schema[A].encode(body)
        create(method, url, json.getBytes(StandardCharsets.UTF_8), headers, Present("application/json"))
    end init

    def init(
        method: Method,
        url: String,
        headers: HttpHeaders
    ): HttpRequest[HttpBody.Bytes] =
        create(method, url, Array.empty, headers, Absent)

    def init(
        method: Method,
        url: String
    ): HttpRequest[HttpBody.Bytes] =
        create(method, url, Array.empty, HttpHeaders.empty, Absent)

    /** Create a request with raw bytes body. */
    def initBytes(
        method: Method,
        url: String,
        body: Span[Byte],
        headers: HttpHeaders,
        contentType: String
    ): HttpRequest[HttpBody.Bytes] =
        create(method, url, body.toArrayUnsafe, headers, if contentType.isEmpty then Absent else Present(contentType))

    /** Create a request with raw bytes body (Array overload). */
    @scala.annotation.targetName("initBytesArray")
    def initBytes(
        method: Method,
        url: String,
        body: Array[Byte],
        headers: HttpHeaders,
        contentType: String
    ): HttpRequest[HttpBody.Bytes] =
        create(method, url, body, headers, if contentType.isEmpty then Absent else Present(contentType))

    // --- Internal: create from raw data (backend-agnostic) ---

    /** Create a buffered request from raw components. Used by backend implementations. */
    private[kyo] def fromRaw(
        method: Method,
        uri: String,
        headers: HttpHeaders,
        bodyData: Array[Byte],
        contentType: Maybe[String]
    ): HttpRequest[HttpBody.Bytes] =
        val resolvedCt = headers.get("Content-Type").orElse(contentType)
        HttpRequest(method, HttpUrl.fromUri(uri), headers, HttpBody(bodyData), resolvedCt, Map.empty, false)
    end fromRaw

    /** Create a buffered request from raw headers and body. Used by backend server implementations. */
    private[kyo] def fromRawHeaders(
        method: Method,
        uri: String,
        headers: HttpHeaders,
        bodyData: Array[Byte]
    ): HttpRequest[HttpBody.Bytes] =
        val contentType = headers.get("content-type")
        HttpRequest(method, HttpUrl.fromUri(uri), headers, HttpBody(bodyData), contentType, Map.empty, false)
    end fromRawHeaders

    /** Create a streaming request from raw headers with a body stream. Used by backend server implementations. */
    private[kyo] def fromRawStreaming(
        method: Method,
        uri: String,
        headers: HttpHeaders,
        bodyStream: Stream[Span[Byte], Async & Scope]
    ): HttpRequest[HttpBody.Streamed] =
        val contentType = headers.get("content-type")
        HttpRequest(method, HttpUrl.fromUri(uri), headers, HttpBody.stream(bodyStream), contentType, Map.empty, false)
    end fromRawStreaming

    // --- Private: core create method ---

    private def create(
        method: Method,
        url: String,
        bodyData: Array[Byte],
        headers: HttpHeaders,
        contentType: Maybe[String]
    ): HttpRequest[HttpBody.Bytes] =
        require(url.nonEmpty, "URL cannot be empty")
        require(!url.exists(c => c == ' ' || c == '\t' || c == '\n' || c == '\r'), s"URL contains whitespace: $url")
        val parsedUrl       = HttpUrl(url)
        val resolvedHeaders = parsedUrl.ensureHostHeader(headers)
        val resolvedCt      = resolvedHeaders.get("Content-Type").orElse(contentType)
        HttpRequest(method, parsedUrl, resolvedHeaders, HttpBody(bodyData), resolvedCt, Map.empty, false)
    end create

    // --- Cookie parsing (pure Scala, no Netty) ---

    private def parseCookies(cookieHeader: String, strict: Boolean): Span[Cookie] =
        val len = cookieHeader.length

        @tailrec def skipWs(pos: Int): Int =
            if pos < len && (cookieHeader.charAt(pos) == ' ' || cookieHeader.charAt(pos) == '\t') then skipWs(pos + 1)
            else pos

        @tailrec def loop(pos: Int, acc: List[Cookie]): List[Cookie] =
            val start = skipWs(pos)
            if start >= len then acc.reverse
            else
                val eqIdx = cookieHeader.indexOf('=', start)
                if eqIdx < 0 then acc.reverse // malformed, stop
                else
                    val name    = cookieHeader.substring(start, eqIdx).trim
                    val semiIdx = cookieHeader.indexOf(';', eqIdx + 1)
                    val endIdx  = if semiIdx < 0 then len else semiIdx
                    val value   = cookieHeader.substring(eqIdx + 1, endIdx).trim
                    val next    = if semiIdx < 0 then len else semiIdx + 1

                    if name.nonEmpty then
                        val unquotedValue =
                            if value.length >= 2 && value.charAt(0) == '"' && value.charAt(value.length - 1) == '"' then
                                value.substring(1, value.length - 1)
                            else
                                value
                        loop(next, Cookie(name, unquotedValue) :: acc)
                    else
                        loop(next, acc)
                    end if
                end if
            end if
        end loop

        val cookies = loop(0, Nil)
        if cookies.isEmpty then Span.empty[Cookie]
        else Span.fromUnsafe(cookies.toArray)
    end parseCookies

    // --- Query string parsing (pure Scala, no Netty) ---

    private def parseQueryParam(queryString: String, name: String): Maybe[String] =
        val len = queryString.length
        @tailrec def loop(pos: Int): Maybe[String] =
            if pos >= len then Absent
            else
                val ampIdx = queryString.indexOf('&', pos)
                val end    = if ampIdx < 0 then len else ampIdx
                val eqIdx  = queryString.indexOf('=', pos)
                if eqIdx >= 0 && eqIdx < end then
                    val key = decodeUrl(queryString.substring(pos, eqIdx))
                    if key == name then Present(decodeUrl(queryString.substring(eqIdx + 1, end)))
                    else loop(if ampIdx < 0 then len else ampIdx + 1)
                else
                    val key = decodeUrl(queryString.substring(pos, end))
                    if key == name then Present("")
                    else loop(if ampIdx < 0 then len else ampIdx + 1)
                end if
        loop(0)
    end parseQueryParam

    private def parseQueryParamAll(queryString: String, name: String): Seq[String] =
        val len = queryString.length
        @tailrec def loop(pos: Int, acc: List[String]): Seq[String] =
            if pos >= len then acc.reverse
            else
                val ampIdx = queryString.indexOf('&', pos)
                val end    = if ampIdx < 0 then len else ampIdx
                val eqIdx  = queryString.indexOf('=', pos)
                val next   = if ampIdx < 0 then len else ampIdx + 1
                if eqIdx >= 0 && eqIdx < end then
                    val key = decodeUrl(queryString.substring(pos, eqIdx))
                    if key == name then loop(next, decodeUrl(queryString.substring(eqIdx + 1, end)) :: acc)
                    else loop(next, acc)
                else
                    val key = decodeUrl(queryString.substring(pos, end))
                    if key == name then loop(next, "" :: acc)
                    else loop(next, acc)
                end if
        loop(0, Nil)
    end parseQueryParamAll

    private def decodeUrl(s: String): String =
        java.net.URLDecoder.decode(s, StandardCharsets.UTF_8.name())

    /** Parses multipart/form-data body. Format: --boundary\r\n headers \r\n\r\n content \r\n--boundary... --boundary-- */
    private def parseMultipart(data: Array[Byte], boundary: String): Span[Part] =
        val boundaryBytes  = ("--" + boundary).getBytes(StandardCharsets.UTF_8)
        val headerEndBytes = "\r\n\r\n".getBytes(StandardCharsets.UTF_8)

        val firstBoundary = MultipartUtil.indexOf(data, boundaryBytes, 0)
        if firstBoundary < 0 then Span.empty[Part]
        else
            @tailrec def loop(pos: Int, acc: List[Part]): Span[Part] =
                if pos >= data.length then toSpan(acc.reverse)
                // "--" after boundary means end of multipart
                else if pos + 2 <= data.length && data(pos) == '-' && data(pos + 1) == '-' then toSpan(acc.reverse)
                else
                    val partStart =
                        if pos + 2 <= data.length && data(pos) == '\r' && data(pos + 1) == '\n' then pos + 2
                        else pos

                    val headerEnd = MultipartUtil.indexOf(data, headerEndBytes, partStart)
                    if headerEnd < 0 then toSpan(acc.reverse)
                    else
                        val headerSection                     = new String(data, partStart, headerEnd - partStart, StandardCharsets.UTF_8)
                        val (name, filename, partContentType) = parsePartHeaders(headerSection)

                        val contentStart = headerEnd + 4
                        val nextBoundary = MultipartUtil.indexOf(data, boundaryBytes, contentStart)
                        if nextBoundary < 0 then toSpan(acc.reverse)
                        else
                            val contentEnd = nextBoundary - 2
                            val content =
                                if contentEnd > contentStart then
                                    val arr = new Array[Byte](contentEnd - contentStart)
                                    java.lang.System.arraycopy(data, contentStart, arr, 0, contentEnd - contentStart)
                                    arr
                                else
                                    Array.empty[Byte]

                            val nextAcc =
                                if name.nonEmpty then Part(name, filename, partContentType, Span.fromUnsafe(content)) :: acc
                                else acc
                            loop(nextBoundary + boundaryBytes.length, nextAcc)
                        end if
                    end if
            loop(firstBoundary + boundaryBytes.length, Nil)
        end if
    end parseMultipart

    private def parsePartHeaders(headerSection: String): (String, Maybe[String], Maybe[String]) =
        val lines = headerSection.split("\r\n")
        @tailrec def loop(
            i: Int,
            name: String,
            filename: Maybe[String],
            contentType: Maybe[String]
        ): (String, Maybe[String], Maybe[String]) =
            if i >= lines.length then (name, filename, contentType)
            else
                val line     = lines(i)
                val colonIdx = line.indexOf(':')
                if colonIdx > 0 then
                    val headerName  = line.substring(0, colonIdx).trim.toLowerCase
                    val headerValue = line.substring(colonIdx + 1).trim
                    if headerName == "content-disposition" then
                        val n = MultipartUtil.extractDispositionParam(headerValue, "name").getOrElse(name)
                        val f = MultipartUtil.extractDispositionParam(headerValue, "filename") match
                            case Present(v) => Present(v)
                            case Absent     => filename
                        loop(i + 1, n, f, contentType)
                    else if headerName == "content-type" then
                        loop(i + 1, name, filename, Present(headerValue))
                    else
                        loop(i + 1, name, filename, contentType)
                    end if
                else
                    loop(i + 1, name, filename, contentType)
                end if
        loop(0, "", Absent, Absent)
    end parsePartHeaders

    private def toSpan(parts: Seq[Part]): Span[Part] =
        if parts.isEmpty then Span.empty[Part]
        else Span.fromUnsafe(parts.toArray)

    // --- Constants ---

    // --- Method type ---

    /** HTTP request method as a zero-cost opaque type over String.
      *
      * Standard method constants: GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS, TRACE, CONNECT.
      */
    opaque type Method = String

    object Method:
        given CanEqual[Method, Method] = CanEqual.derived

        val GET: Method     = "GET"
        val POST: Method    = "POST"
        val PUT: Method     = "PUT"
        val PATCH: Method   = "PATCH"
        val DELETE: Method  = "DELETE"
        val HEAD: Method    = "HEAD"
        val OPTIONS: Method = "OPTIONS"
        val TRACE: Method   = "TRACE"
        val CONNECT: Method = "CONNECT"

        /** Create a Method from a string name. */
        def apply(name: String): Method = name

        extension (m: Method)
            def name: String = m
    end Method

    // --- Auxiliary types ---

    /** Parsed cookie from a request's `Cookie` header. Use `toResponse` to convert to an `HttpResponse.Cookie` for setting. */
    case class Cookie(name: String, value: String) derives CanEqual:
        def toResponse: HttpResponse.Cookie = HttpResponse.Cookie(name, value)
    end Cookie

    /** A single part from a multipart form-data request body. */
    case class Part(
        name: String,
        filename: Maybe[String],
        contentType: Maybe[String],
        data: Span[Byte]
    ) derives CanEqual:
        require(name.nonEmpty, "Part name cannot be empty")
    end Part

    // --- Multipart body builder ---

    private val crlfBytes           = "\r\n".getBytes(StandardCharsets.UTF_8)
    private val contentTypeBytes    = "Content-Type: ".getBytes(StandardCharsets.UTF_8)
    private val contentDispBytes    = "Content-Disposition: form-data; name=\"".getBytes(StandardCharsets.UTF_8)
    private val filenameBytes       = "\"; filename=\"".getBytes(StandardCharsets.UTF_8)
    private val quoteBytes          = "\"".getBytes(StandardCharsets.UTF_8)
    private val boundaryPrefixBytes = "--".getBytes(StandardCharsets.UTF_8)
    private val boundarySuffixBytes = "--\r\n".getBytes(StandardCharsets.UTF_8)

    private def buildMultipartBody(parts: Seq[Part], boundary: String): Array[Byte] =
        val boundaryBytes = boundary.getBytes(StandardCharsets.UTF_8)
        val out           = new java.io.ByteArrayOutputStream()
        parts.foreach { part =>
            out.write(boundaryPrefixBytes)
            out.write(boundaryBytes)
            out.write(crlfBytes)
            out.write(contentDispBytes)
            out.write(part.name.getBytes(StandardCharsets.UTF_8))
            part.filename match
                case Present(fn) =>
                    out.write(filenameBytes)
                    out.write(fn.getBytes(StandardCharsets.UTF_8))
                    out.write(quoteBytes)
                case Absent =>
                    out.write(quoteBytes)
            end match
            out.write(crlfBytes)
            part.contentType match
                case Present(ct) =>
                    out.write(contentTypeBytes)
                    out.write(ct.getBytes(StandardCharsets.UTF_8))
                    out.write(crlfBytes)
                case Absent => ()
            end match
            out.write(crlfBytes)
            out.write(part.data.toArrayUnsafe)
            out.write(crlfBytes)
        }
        out.write(boundaryPrefixBytes)
        out.write(boundaryBytes)
        out.write(boundarySuffixBytes)
        out.toByteArray
    end buildMultipartBody

end HttpRequest
