package kyo

import java.nio.charset.StandardCharsets
import kyo.internal.MultipartUtil
import kyo.internal.UrlParser

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
  * IMPORTANT: `bodyAs[A]` on requests throws on decode failure (it's pure, no `Abort` effect). Compare with `HttpResponse.bodyAs[A]` which
  * wraps failure in `Abort[HttpError]`.
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
final class HttpRequest[+B <: HttpBody] private (
    val method: HttpRequest.Method,
    private val _rawPath: String,
    private val _rawQuery: Maybe[String],
    private val _headers: HttpHeaders,
    val body: B,
    private val _contentType: Maybe[String],
    private val _scheme: Maybe[String],
    private val _pathParams: Map[String, String],
    private val _strictCookieParsing: Boolean
):
    import HttpRequest.*

    // --- URL/Path accessors ---

    /** Returns the request URI (path and query string, without scheme/host). */
    def url: String =
        _rawQuery match
            case Present(q) => s"$_rawPath?$q"
            case Absent     => _rawPath
    end url

    /** Returns just the path without query string. */
    def path: String = _rawPath

    /** Returns the full URL including scheme and host. */
    def fullUrl: String =
        val pathAndQuery = url
        _scheme match
            case Present(s) =>
                header("Host") match
                    case Present(hostHeader) => s"$s://$hostHeader$pathAndQuery"
                    case Absent              => pathAndQuery
            case Absent =>
                header("Host") match
                    case Absent => pathAndQuery
                    case Present(hostHeader) =>
                        s"http://$hostHeader$pathAndQuery"
        end match
    end fullUrl

    def host: String =
        header("Host").map { h =>
            // Handle IPv6 addresses like [::1]:8080
            if h.startsWith("[") then
                val endBracket = h.indexOf(']')
                if endBracket >= 0 then h.substring(1, endBracket) else h
            else
                val idx = h.indexOf(':')
                if idx >= 0 then h.substring(0, idx) else h
        }.getOrElse("")

    def port: Int =
        header("Host").map { h =>
            // Handle IPv6 addresses like [::1]:8080
            val portIdx =
                if h.startsWith("[") then
                    val endBracket = h.indexOf(']')
                    if endBracket >= 0 && endBracket + 1 < h.length && h.charAt(endBracket + 1) == ':' then
                        endBracket + 1
                    else -1
                else
                    h.indexOf(':')
            if portIdx >= 0 then h.substring(portIdx + 1).toInt
            else
                // Use scheme to determine default port
                if _scheme.contains("https") then HttpRequest.DefaultHttpsPort else HttpRequest.DefaultHttpPort
            end if
        }.getOrElse(HttpRequest.DefaultHttpPort)

    // --- Header accessors ---

    def contentType: Maybe[String] =
        // User-provided Content-Type header takes precedence over factory-set content type
        header("Content-Type").orElse(_contentType)

    def header(name: String): Maybe[String] =
        _headers.get(name)

    def headers: HttpHeaders = _headers

    // --- Cookie accessors ---

    def cookie(name: String): Maybe[Cookie] =
        cookies.find(_.name == name)

    def cookie(name: String, strict: Boolean): Maybe[Cookie] =
        cookies(strict).find(_.name == name)

    /** Parse cookies using server's default mode (LAX unless configured for STRICT). */
    def cookies: Span[Cookie] =
        cookies(_strictCookieParsing)

    /** Parse cookies with explicit mode selection. */
    def cookies(strict: Boolean): Span[Cookie] =
        header("Cookie") match
            case Absent => Span.empty[Cookie]
            case Present(cookieHeader) =>
                parseCookies(cookieHeader, strict)

    // --- Query parameter accessors ---

    def query(name: String): Maybe[String] =
        _rawQuery match
            case Absent     => Absent
            case Present(q) => parseQueryParam(q, name)
    end query

    def queryAll(name: String): Seq[String] =
        _rawQuery match
            case Absent     => Seq.empty
            case Present(q) => parseQueryParamAll(q, name)
    end queryAll

    // --- Path parameter accessors ---

    def pathParam(name: String): Maybe[String] =
        _pathParams.get(name) match
            case Some(v) => Present(v)
            case None    => Absent

    def pathParams: Map[String, String] = _pathParams

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

    /** Returns the request body as a String. Only available for buffered requests. */
    def bodyText(using ev: B <:< HttpBody.Bytes): String =
        val data = ev(body).data
        if data.isEmpty then "" else new String(data, StandardCharsets.UTF_8)

    /** Returns the request body as a Span[Byte]. Only available for buffered requests. */
    def bodyBytes(using ev: B <:< HttpBody.Bytes): Span[Byte] = ev(body).span

    /** Parses the request body as type A via Schema. Throws on decode failure (no Abort effect). Only available for buffered requests. */
    def bodyAs[A: Schema](using ev: B <:< HttpBody.Bytes): A =
        Schema[A].decode(bodyText)

    /** Returns multipart parts from the request body. Only available for buffered requests. */
    def parts(using ev: B <:< HttpBody.Bytes): Span[HttpRequest.Part] =
        contentType match
            case Present(ct) if ct.startsWith("multipart/form-data") =>
                // Extract boundary from content type
                MultipartUtil.extractBoundary(ct) match
                    case Present(boundary) =>
                        parseMultipart(ev(body).data, boundary)
                    case Absent =>
                        Span.empty[HttpRequest.Part]
            case _ =>
                Span.empty[HttpRequest.Part]
    end parts

    /** Returns the body as a byte stream. Only available for streaming requests. */
    def bodyStream(using ev: B <:< HttpBody.Streamed)(using Frame): Stream[Span[Byte], Async] = ev(body).stream

    /** Returns the body as a byte stream. Works for both buffered and streaming requests. */
    def bodyStreamUniversal(using Frame): Stream[Span[Byte], Async] =
        body.use(b => Stream.init(Chunk(b.span)), _.stream)

    // --- Builder methods (return new immutable instance, preserve body type) ---

    /** Appends a header to the header list. Does NOT replace existing headers with the same name. */
    def addHeader(name: String, value: String): HttpRequest[B] =
        new HttpRequest(
            method,
            _rawPath,
            _rawQuery,
            _headers.add(name, value),
            body,
            _contentType,
            _scheme,
            _pathParams,
            _strictCookieParsing
        )

    def addHeaders(headers: HttpHeaders): HttpRequest[B] =
        new HttpRequest(
            method,
            _rawPath,
            _rawQuery,
            _headers.concat(headers),
            body,
            _contentType,
            _scheme,
            _pathParams,
            _strictCookieParsing
        )

    // --- Internal: for server to set path params after routing ---

    private[kyo] def withPathParam(name: String, value: String): HttpRequest[B] =
        new HttpRequest(
            method,
            _rawPath,
            _rawQuery,
            _headers,
            body,
            _contentType,
            _scheme,
            _pathParams + (name -> value),
            _strictCookieParsing
        )

    private[kyo] def withPathParams(params: Map[String, String]): HttpRequest[B] =
        new HttpRequest(method, _rawPath, _rawQuery, _headers, body, _contentType, _scheme, _pathParams ++ params, _strictCookieParsing)

    private[kyo] def withStrictCookieParsing(strict: Boolean): HttpRequest[B] =
        new HttpRequest(method, _rawPath, _rawQuery, _headers, body, _contentType, _scheme, _pathParams, strict)

    /** Update the URL (path+query) for redirect following. */
    private[kyo] def withUrl(url: String): HttpRequest[B] =
        UrlParser.splitPathQuery(url) { (p, q) =>
            new HttpRequest(method, p, q, _headers, body, _contentType, _scheme, _pathParams, _strictCookieParsing)
        }

    /** Update the URL from pre-parsed path and query components. Avoids re-parsing. */
    private[kyo] def withParsedUrl(rawPath: String, rawQuery: Maybe[String]): HttpRequest[B] =
        new HttpRequest(method, rawPath, rawQuery, _headers, body, _contentType, _scheme, _pathParams, _strictCookieParsing)

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
        body: Stream[Span[Byte], Async],
        headers: HttpHeaders = HttpHeaders.empty,
        contentType: Maybe[String] = Absent
    ): HttpRequest[HttpBody.Streamed] =
        require(url.nonEmpty, "URL cannot be empty")
        UrlParser.parseUrlParts(url) { (scheme, host, port, rawPath, rawQuery) =>
            new HttpRequest(
                method = method,
                _rawPath = rawPath,
                _rawQuery = rawQuery,
                _headers = withHostHeader(host, port, headers),
                body = HttpBody.stream(body),
                _contentType = contentType,
                _scheme = scheme,
                _pathParams = Map.empty,
                _strictCookieParsing = false
            )
        }
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
        UrlParser.splitPathQuery(uri) { (rawPath, rawQuery) =>
            new HttpRequest(
                method = method,
                _rawPath = rawPath,
                _rawQuery = rawQuery,
                _headers = headers,
                body = HttpBody(bodyData),
                _contentType = contentType,
                _scheme = Absent,
                _pathParams = Map.empty,
                _strictCookieParsing = false
            )
        }
    end fromRaw

    /** Create a buffered request from raw headers and body. Used by backend server implementations. */
    private[kyo] def fromRawHeaders(
        method: Method,
        uri: String,
        headers: HttpHeaders,
        bodyData: Array[Byte]
    ): HttpRequest[HttpBody.Bytes] =
        UrlParser.splitPathQuery(uri) { (rawPath, rawQuery) =>
            val contentType = headers.get("content-type")
            new HttpRequest(
                method = method,
                _rawPath = rawPath,
                _rawQuery = rawQuery,
                _headers = headers,
                body = HttpBody(bodyData),
                _contentType = contentType,
                _scheme = Absent,
                _pathParams = Map.empty,
                _strictCookieParsing = false
            )
        }
    end fromRawHeaders

    /** Create a streaming request from raw headers with a body stream. Used by backend server implementations. */
    private[kyo] def fromRawStreaming(
        method: Method,
        uri: String,
        headers: HttpHeaders,
        bodyStream: Stream[Span[Byte], Async]
    ): HttpRequest[HttpBody.Streamed] =
        UrlParser.splitPathQuery(uri) { (rawPath, rawQuery) =>
            val contentType = headers.get("content-type")
            new HttpRequest(
                method = method,
                _rawPath = rawPath,
                _rawQuery = rawQuery,
                _headers = headers,
                body = HttpBody.stream(bodyStream),
                _contentType = contentType,
                _scheme = Absent,
                _pathParams = Map.empty,
                _strictCookieParsing = false
            )
        }
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
        UrlParser.parseUrlParts(url) { (scheme, host, port, rawPath, rawQuery) =>
            new HttpRequest(
                method = method,
                _rawPath = rawPath,
                _rawQuery = rawQuery,
                _headers = withHostHeader(host, port, headers),
                body = HttpBody(bodyData),
                _contentType = contentType,
                _scheme = scheme,
                _pathParams = Map.empty,
                _strictCookieParsing = false
            )
        }
    end create

    // --- Private helpers ---

    private def withHostHeader(host: Maybe[String], port: Int, headers: HttpHeaders): HttpHeaders =
        host match
            case Present(h) if !headers.contains("Host") =>
                // Wrap IPv6 addresses in brackets for Host header (if not already wrapped)
                val hostPart = if h.startsWith("[") then h else if h.contains(':') then s"[$h]" else h
                val hostValue =
                    if port > 0 && port != HttpRequest.DefaultHttpPort && port != HttpRequest.DefaultHttpsPort then
                        s"$hostPart:$port"
                    else
                        hostPart
                headers.add("Host", hostValue)
            case _ => headers
    end withHostHeader

    // --- Cookie parsing (pure Scala, no Netty) ---

    private def parseCookies(cookieHeader: String, strict: Boolean): Span[Cookie] =
        val result = Seq.newBuilder[Cookie]
        var pos    = 0
        val len    = cookieHeader.length

        while pos < len do
            // Skip whitespace
            while pos < len && (cookieHeader.charAt(pos) == ' ' || cookieHeader.charAt(pos) == '\t') do
                pos += 1

            if pos < len then
                // Find '='
                val eqIdx = cookieHeader.indexOf('=', pos)
                if eqIdx < 0 then
                    pos = len // malformed, skip rest
                else
                    val name = cookieHeader.substring(pos, eqIdx).trim
                    pos = eqIdx + 1

                    // Find end of value (';' or end of string)
                    val semiIdx = cookieHeader.indexOf(';', pos)
                    val endIdx  = if semiIdx < 0 then len else semiIdx
                    val value   = cookieHeader.substring(pos, endIdx).trim
                    pos = if semiIdx < 0 then len else semiIdx + 1

                    if name.nonEmpty then
                        // Strip quotes from value if present (LAX mode)
                        val unquotedValue =
                            if value.length >= 2 && value.charAt(0) == '"' && value.charAt(value.length - 1) == '"' then
                                value.substring(1, value.length - 1)
                            else
                                value
                        result += Cookie(name, unquotedValue)
                    end if
                end if
            end if
        end while

        val arr = result.result()
        if arr.isEmpty then Span.empty[Cookie]
        else Span.fromUnsafe(arr.toArray)
    end parseCookies

    // --- Query string parsing (pure Scala, no Netty) ---

    private def parseQueryParam(queryString: String, name: String): Maybe[String] =
        var pos = 0
        val len = queryString.length
        while pos < len do
            val ampIdx = queryString.indexOf('&', pos)
            val end    = if ampIdx < 0 then len else ampIdx
            val eqIdx  = queryString.indexOf('=', pos)

            if eqIdx >= 0 && eqIdx < end then
                val key = decodeUrl(queryString.substring(pos, eqIdx))
                if key == name then
                    return Present(decodeUrl(queryString.substring(eqIdx + 1, end)))
            else
                // Flag-style parameter (no =)
                val key = decodeUrl(queryString.substring(pos, end))
                if key == name then
                    return Present("")
            end if
            pos = if ampIdx < 0 then len else ampIdx + 1
        end while
        Absent
    end parseQueryParam

    private def parseQueryParamAll(queryString: String, name: String): Seq[String] =
        val result = Seq.newBuilder[String]
        var pos    = 0
        val len    = queryString.length
        while pos < len do
            val ampIdx = queryString.indexOf('&', pos)
            val end    = if ampIdx < 0 then len else ampIdx
            val eqIdx  = queryString.indexOf('=', pos)

            if eqIdx >= 0 && eqIdx < end then
                val key = decodeUrl(queryString.substring(pos, eqIdx))
                if key == name then
                    result += decodeUrl(queryString.substring(eqIdx + 1, end))
            else
                val key = decodeUrl(queryString.substring(pos, end))
                if key == name then
                    result += ""
            end if
            pos = if ampIdx < 0 then len else ampIdx + 1
        end while
        result.result()
    end parseQueryParamAll

    private def decodeUrl(s: String): String =
        java.net.URLDecoder.decode(s, StandardCharsets.UTF_8.name())

    /** Parses multipart/form-data body. Format: --boundary\r\n headers \r\n\r\n content \r\n--boundary... --boundary-- */
    private def parseMultipart(data: Array[Byte], boundary: String): Span[Part] =
        val boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.UTF_8)
        val crlfBytes     = "\r\n".getBytes(StandardCharsets.UTF_8)
        val result        = Seq.newBuilder[Part]

        var pos = MultipartUtil.indexOf(data, boundaryBytes, 0)
        if pos < 0 then return Span.empty[Part]
        pos += boundaryBytes.length

        while pos < data.length do
            // "--" after boundary means end of multipart
            if pos + 2 <= data.length && data(pos) == '-' && data(pos + 1) == '-' then
                return toSpan(result.result())

            if pos + 2 <= data.length && data(pos) == '\r' && data(pos + 1) == '\n' then
                pos += 2

            // Each part has MIME-style headers terminated by \r\n\r\n
            var name: String                   = ""
            var filename: Maybe[String]        = Absent
            var partContentType: Maybe[String] = Absent

            var headerEnd = MultipartUtil.indexOf(data, "\r\n\r\n".getBytes(StandardCharsets.UTF_8), pos)
            if headerEnd < 0 then return toSpan(result.result())

            val headerSection = new String(data, pos, headerEnd - pos, StandardCharsets.UTF_8)
            headerSection.split("\r\n").foreach { line =>
                val colonIdx = line.indexOf(':')
                if colonIdx > 0 then
                    val headerName  = line.substring(0, colonIdx).trim.toLowerCase
                    val headerValue = line.substring(colonIdx + 1).trim
                    if headerName == "content-disposition" then
                        MultipartUtil.extractDispositionParam(headerValue, "name").foreach(n => name = n)
                        filename = MultipartUtil.extractDispositionParam(headerValue, "filename")
                    else if headerName == "content-type" then
                        partContentType = Present(headerValue)
                    end if
                end if
            }

            pos = headerEnd + 4

            // Content runs from here to \r\n before the next boundary marker
            val nextBoundary = MultipartUtil.indexOf(data, boundaryBytes, pos)
            if nextBoundary < 0 then return toSpan(result.result())

            val contentEnd = nextBoundary - 2
            val content =
                if contentEnd > pos then
                    val arr = new Array[Byte](contentEnd - pos)
                    java.lang.System.arraycopy(data, pos, arr, 0, contentEnd - pos)
                    arr
                else
                    Array.empty[Byte]

            if name.nonEmpty then
                result += Part(name, filename, partContentType, content)

            pos = nextBoundary + boundaryBytes.length
        end while

        toSpan(result.result())
    end parseMultipart

    private def toSpan(parts: Seq[Part]): Span[Part] =
        if parts.isEmpty then Span.empty[Part]
        else Span.fromUnsafe(parts.toArray)

    // --- Constants ---

    private[kyo] inline def DefaultHttpPort  = 80
    private[kyo] inline def DefaultHttpsPort = 443

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
        private[kyo] def apply(name: String): Method = name

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
        content: Array[Byte]
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
            out.write(part.content)
            out.write(crlfBytes)
        }
        out.write(boundaryPrefixBytes)
        out.write(boundaryBytes)
        out.write(boundarySuffixBytes)
        out.toByteArray
    end buildMultipartBody

end HttpRequest
