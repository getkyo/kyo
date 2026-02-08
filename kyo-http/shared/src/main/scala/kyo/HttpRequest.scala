package kyo

import java.net.URI
import java.nio.charset.StandardCharsets
import scala.annotation.tailrec

/** Immutable HTTP request with builder API.
  *
  * Requests are constructed via factory methods and can be modified using builder methods that return new instances.
  *
  * The type parameter B tracks the body type: `HttpBody.Bytes` for fully-buffered bodies, `HttpBody.Streamed` for streaming bodies.
  */
final class HttpRequest[+B <: HttpBody] private (
    val method: HttpRequest.Method,
    private val _originalUrl: URI,
    private val _headers: Seq[(String, String)],
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
        val rawPath  = _originalUrl.getRawPath
        val rawQuery = _originalUrl.getRawQuery
        val pathPart = if rawPath == null || rawPath.isEmpty then "/" else rawPath
        if rawQuery == null then pathPart else s"$pathPart?$rawQuery"
    end url

    /** Returns just the path without query string. */
    def path: String =
        val rawPath = _originalUrl.getRawPath
        if rawPath == null || rawPath.isEmpty then "/"
        else rawPath
    end path

    /** Returns the full URL including scheme and host. */
    def fullUrl: String =
        val urlStr = _originalUrl.toString
        // If original URL has scheme, return as-is; otherwise construct from Host header
        if _originalUrl.getScheme != null then urlStr
        else
            header("Host") match
                case Absent => urlStr
                case Present(hostHeader) =>
                    val scheme = _scheme.getOrElse("http")
                    s"$scheme://$hostHeader$urlStr"
        end if
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
                if _scheme.contains("https") then HttpClient.DefaultHttpsPort else HttpClient.DefaultHttpPort
            end if
        }.getOrElse(HttpClient.DefaultHttpPort)

    // --- Header accessors ---

    def contentType: Maybe[String] =
        // User-provided Content-Type header takes precedence over factory-set content type
        header("Content-Type").orElse(_contentType)

    def header(name: String): Maybe[String] =
        val lowerName = name.toLowerCase
        @tailrec def loop(remaining: Seq[(String, String)]): Maybe[String] =
            remaining match
                case Seq()                                         => Absent
                case Seq((n, v), _*) if n.toLowerCase == lowerName => Present(v)
                case Seq(_, tail*)                                 => loop(tail)
        loop(_headers)
    end header

    /** Returns all headers (excluding internal X-Kyo-* headers). */
    def headers: Seq[(String, String)] = _headers

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
        val queryString = _originalUrl.getRawQuery
        if queryString == null then Absent
        else parseQueryParam(queryString, name)
    end query

    def queryAll(name: String): Seq[String] =
        val queryString = _originalUrl.getRawQuery
        if queryString == null then Seq.empty
        else parseQueryParamAll(queryString, name)
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

    /** Parses the request body as type A. Only available for buffered requests. */
    def bodyAs[A: Schema](using ev: B <:< HttpBody.Bytes): A =
        Schema[A].decode(bodyText)

    /** Returns multipart parts from the request body. Only available for buffered requests. */
    def parts(using ev: B <:< HttpBody.Bytes): Span[HttpRequest.Part] =
        contentType match
            case Present(ct) if ct.startsWith("multipart/form-data") =>
                // Extract boundary from content type
                extractBoundary(ct) match
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
        body match
            case b: HttpBody.Bytes    => Stream.init(Chunk(b.span))
            case s: HttpBody.Streamed => s.stream

    // --- Builder methods (return new immutable instance, preserve body type) ---

    def addHeader(name: String, value: String): HttpRequest[B] =
        new HttpRequest(method, _originalUrl, _headers :+ (name -> value), body, _contentType, _scheme, _pathParams, _strictCookieParsing)

    def addHeaders(headers: (String, String)*): HttpRequest[B] =
        new HttpRequest(method, _originalUrl, _headers ++ headers, body, _contentType, _scheme, _pathParams, _strictCookieParsing)

    // --- Internal: for server to set path params after routing ---

    private[kyo] def withPathParam(name: String, value: String): HttpRequest[B] =
        new HttpRequest(method, _originalUrl, _headers, body, _contentType, _scheme, _pathParams + (name -> value), _strictCookieParsing)

    private[kyo] def withPathParams(params: Map[String, String]): HttpRequest[B] =
        new HttpRequest(method, _originalUrl, _headers, body, _contentType, _scheme, _pathParams ++ params, _strictCookieParsing)

    private[kyo] def withStrictCookieParsing(strict: Boolean): HttpRequest[B] =
        new HttpRequest(method, _originalUrl, _headers, body, _contentType, _scheme, _pathParams, strict)

    /** Update the URL (path) for redirect following. */
    private[kyo] def withUrl(url: String): HttpRequest[B] =
        new HttpRequest(method, new URI(url), _headers, body, _contentType, _scheme, _pathParams, _strictCookieParsing)

end HttpRequest

object HttpRequest:

    // --- Factory methods for client-side requests ---

    def get(url: String, headers: Seq[(String, String)] = Seq.empty): HttpRequest[HttpBody.Bytes] =
        create(Method.GET, url, Array.empty, headers, Absent)

    def post[A: Schema](url: String, body: A, headers: Seq[(String, String)] = Seq.empty): HttpRequest[HttpBody.Bytes] =
        val json = Schema[A].encode(body)
        create(Method.POST, url, json.getBytes(StandardCharsets.UTF_8), headers, Present("application/json"))

    def postText(url: String, body: String, headers: Seq[(String, String)] = Seq.empty): HttpRequest[HttpBody.Bytes] =
        create(Method.POST, url, body.getBytes(StandardCharsets.UTF_8), headers, Present("text/plain; charset=utf-8"))

    def postForm(url: String, fields: Seq[(String, String)], headers: Seq[(String, String)] = Seq.empty): HttpRequest[HttpBody.Bytes] =
        val body = fields.map { case (k, v) =>
            java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
        }.mkString("&")
        create(Method.POST, url, body.getBytes(StandardCharsets.UTF_8), headers, Present("application/x-www-form-urlencoded"))
    end postForm

    def put[A: Schema](url: String, body: A, headers: Seq[(String, String)] = Seq.empty): HttpRequest[HttpBody.Bytes] =
        val json = Schema[A].encode(body)
        create(Method.PUT, url, json.getBytes(StandardCharsets.UTF_8), headers, Present("application/json"))

    def putText(url: String, body: String, headers: Seq[(String, String)] = Seq.empty): HttpRequest[HttpBody.Bytes] =
        create(Method.PUT, url, body.getBytes(StandardCharsets.UTF_8), headers, Present("text/plain; charset=utf-8"))

    def patch[A: Schema](url: String, body: A, headers: Seq[(String, String)] = Seq.empty): HttpRequest[HttpBody.Bytes] =
        val json = Schema[A].encode(body)
        create(Method.PATCH, url, json.getBytes(StandardCharsets.UTF_8), headers, Present("application/json"))

    def patchText(url: String, body: String, headers: Seq[(String, String)] = Seq.empty): HttpRequest[HttpBody.Bytes] =
        create(Method.PATCH, url, body.getBytes(StandardCharsets.UTF_8), headers, Present("text/plain; charset=utf-8"))

    def delete(url: String, headers: Seq[(String, String)] = Seq.empty): HttpRequest[HttpBody.Bytes] =
        create(Method.DELETE, url, Array.empty, headers, Absent)

    def head(url: String, headers: Seq[(String, String)] = Seq.empty): HttpRequest[HttpBody.Bytes] =
        create(Method.HEAD, url, Array.empty, headers, Absent)

    def options(url: String, headers: Seq[(String, String)] = Seq.empty): HttpRequest[HttpBody.Bytes] =
        create(Method.OPTIONS, url, Array.empty, headers, Absent)

    def multipart(url: String, parts: Seq[Part], headers: Seq[(String, String)] = Seq.empty): HttpRequest[HttpBody.Bytes] =
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
        headers: Seq[(String, String)] = Seq.empty,
        contentType: Maybe[String] = Absent
    ): HttpRequest[HttpBody.Streamed] =
        require(url.nonEmpty, "URL cannot be empty")
        val uri    = new URI(url)
        val scheme = if uri.getScheme == null then Absent else Present(uri.getScheme)

        new HttpRequest(
            method = method,
            _originalUrl = uri,
            _headers = withHostHeader(uri, headers),
            body = HttpBody.stream(body),
            _contentType = contentType,
            _scheme = scheme,
            _pathParams = Map.empty,
            _strictCookieParsing = false
        )
    end stream

    // --- Generic factory methods ---

    def init[A: Schema](
        method: Method,
        url: String,
        body: A,
        headers: Seq[(String, String)] = Seq.empty
    ): HttpRequest[HttpBody.Bytes] =
        val json = Schema[A].encode(body)
        create(method, url, json.getBytes(StandardCharsets.UTF_8), headers, Present("application/json"))
    end init

    def init(
        method: Method,
        url: String,
        headers: Seq[(String, String)]
    ): HttpRequest[HttpBody.Bytes] =
        create(method, url, Array.empty, headers, Absent)

    def init(
        method: Method,
        url: String
    ): HttpRequest[HttpBody.Bytes] =
        create(method, url, Array.empty, Seq.empty, Absent)

    /** Create a request with raw bytes body. */
    def initBytes(
        method: Method,
        url: String,
        body: Array[Byte],
        headers: Seq[(String, String)],
        contentType: String
    ): HttpRequest[HttpBody.Bytes] =
        create(method, url, body, headers, if contentType.isEmpty then Absent else Present(contentType))

    // --- Internal: create from raw data (backend-agnostic) ---

    /** Create a buffered request from raw components. Used by backend implementations. */
    private[kyo] def fromRaw(
        method: Method,
        uri: String,
        headers: Seq[(String, String)],
        bodyData: Array[Byte],
        contentType: Maybe[String]
    ): HttpRequest[HttpBody.Bytes] =
        val parsedUri = new URI(uri)
        new HttpRequest(
            method = method,
            _originalUrl = parsedUri,
            _headers = headers,
            body = HttpBody(bodyData),
            _contentType = contentType,
            _scheme = Absent,
            _pathParams = Map.empty,
            _strictCookieParsing = false
        )
    end fromRaw

    /** Create a buffered request from raw headers and body. Used by backend server implementations. */
    private[kyo] def fromRawHeaders(
        method: Method,
        uri: String,
        headers: Seq[(String, String)],
        bodyData: Array[Byte]
    ): HttpRequest[HttpBody.Bytes] =
        val parsedUri   = new URI(uri)
        val contentType = findHeader(headers, "content-type")
        new HttpRequest(
            method = method,
            _originalUrl = parsedUri,
            _headers = headers,
            body = HttpBody(bodyData),
            _contentType = contentType,
            _scheme = Absent,
            _pathParams = Map.empty,
            _strictCookieParsing = false
        )
    end fromRawHeaders

    /** Create a streaming request from raw headers with a body stream. Used by backend server implementations. */
    private[kyo] def fromRawStreaming(
        method: Method,
        uri: String,
        headers: Seq[(String, String)],
        bodyStream: Stream[Span[Byte], Async]
    ): HttpRequest[HttpBody.Streamed] =
        val parsedUri   = new URI(uri)
        val contentType = findHeader(headers, "content-type")
        new HttpRequest(
            method = method,
            _originalUrl = parsedUri,
            _headers = headers,
            body = HttpBody.stream(bodyStream),
            _contentType = contentType,
            _scheme = Absent,
            _pathParams = Map.empty,
            _strictCookieParsing = false
        )
    end fromRawStreaming

    // --- Private: core create method ---

    private def create(
        method: Method,
        url: String,
        bodyData: Array[Byte],
        headers: Seq[(String, String)],
        contentType: Maybe[String]
    ): HttpRequest[HttpBody.Bytes] =
        require(url.nonEmpty, "URL cannot be empty")
        val uri    = new URI(url)
        val scheme = if uri.getScheme == null then Absent else Present(uri.getScheme)

        new HttpRequest(
            method = method,
            _originalUrl = uri,
            _headers = withHostHeader(uri, headers),
            body = HttpBody(bodyData),
            _contentType = contentType,
            _scheme = scheme,
            _pathParams = Map.empty,
            _strictCookieParsing = false
        )
    end create

    // --- Private helpers ---

    private def findHeader(headers: Seq[(String, String)], lowerName: String): Maybe[String] =
        @tailrec def loop(remaining: Seq[(String, String)]): Maybe[String] =
            remaining match
                case Seq()                                         => Absent
                case Seq((n, v), _*) if n.toLowerCase == lowerName => Present(v)
                case Seq(_, tail*)                                 => loop(tail)
        loop(headers)
    end findHeader

    private def withHostHeader(uri: URI, headers: Seq[(String, String)]): Seq[(String, String)] =
        val uriHost = uri.getHost
        if uriHost != null && !headers.exists(_._1.equalsIgnoreCase("Host")) then
            val port = uri.getPort
            val hostValue =
                if port > 0 && port != HttpClient.DefaultHttpPort && port != HttpClient.DefaultHttpsPort then
                    s"$uriHost:$port"
                else
                    uriHost
            headers :+ ("Host" -> hostValue)
        else headers
        end if
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
        java.net.URLDecoder.decode(s, StandardCharsets.UTF_8)

    // --- Multipart parsing (pure Scala, no Netty) ---

    private def extractBoundary(contentType: String): Maybe[String] =
        val boundaryPrefix = "boundary="
        val idx            = contentType.indexOf(boundaryPrefix)
        if idx < 0 then Absent
        else
            val start = idx + boundaryPrefix.length
            val value = contentType.substring(start).trim
            // Strip quotes if present
            if value.length >= 2 && value.charAt(0) == '"' && value.charAt(value.length - 1) == '"' then
                Present(value.substring(1, value.length - 1))
            else
                // Trim at semicolon if present
                val semiIdx = value.indexOf(';')
                Present(if semiIdx >= 0 then value.substring(0, semiIdx).trim else value)
            end if
        end if
    end extractBoundary

    private def parseMultipart(data: Array[Byte], boundary: String): Span[Part] =
        val boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.UTF_8)
        val crlfBytes     = "\r\n".getBytes(StandardCharsets.UTF_8)
        val result        = Seq.newBuilder[Part]

        // Find first boundary
        var pos = indexOf(data, boundaryBytes, 0)
        if pos < 0 then return Span.empty[Part]
        pos += boundaryBytes.length

        while pos < data.length do
            // Check for terminating boundary (--boundary--)
            if pos + 2 <= data.length && data(pos) == '-' && data(pos + 1) == '-' then
                // End of multipart
                return toSpan(result.result())

            // Skip CRLF after boundary
            if pos + 2 <= data.length && data(pos) == '\r' && data(pos + 1) == '\n' then
                pos += 2

            // Parse headers until empty line
            var name: String                   = ""
            var filename: Maybe[String]        = Absent
            var partContentType: Maybe[String] = Absent

            var headerEnd = indexOf(data, "\r\n\r\n".getBytes(StandardCharsets.UTF_8), pos)
            if headerEnd < 0 then return toSpan(result.result())

            val headerSection = new String(data, pos, headerEnd - pos, StandardCharsets.UTF_8)
            headerSection.split("\r\n").foreach { line =>
                val colonIdx = line.indexOf(':')
                if colonIdx > 0 then
                    val headerName  = line.substring(0, colonIdx).trim.toLowerCase
                    val headerValue = line.substring(colonIdx + 1).trim
                    if headerName == "content-disposition" then
                        // Parse name and filename from Content-Disposition
                        extractDispositionParam(headerValue, "name").foreach(n => name = n)
                        filename = extractDispositionParam(headerValue, "filename")
                    else if headerName == "content-type" then
                        partContentType = Present(headerValue)
                    end if
                end if
            }

            pos = headerEnd + 4 // Skip \r\n\r\n

            // Find next boundary
            val nextBoundary = indexOf(data, boundaryBytes, pos)
            if nextBoundary < 0 then return toSpan(result.result())

            // Content ends before \r\n--boundary
            val contentEnd = nextBoundary - 2 // Subtract \r\n before boundary
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

    private def extractDispositionParam(disposition: String, param: String): Maybe[String] =
        val search = param + "=\""
        val idx    = disposition.indexOf(search)
        if idx < 0 then Absent
        else
            val start  = idx + search.length
            val endIdx = disposition.indexOf('"', start)
            if endIdx < 0 then Absent
            else Present(disposition.substring(start, endIdx))
        end if
    end extractDispositionParam

    private def indexOf(data: Array[Byte], pattern: Array[Byte], from: Int): Int =
        val dataLen    = data.length
        val patternLen = pattern.length
        if patternLen == 0 || from + patternLen > dataLen then return -1

        var i = from
        while i <= dataLen - patternLen do
            var j     = 0
            var found = true
            while j < patternLen && found do
                if data(i + j) != pattern(j) then
                    found = false
                j += 1
            end while
            if found then return i
            i += 1
        end while
        -1
    end indexOf

    // --- Method type ---

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

    case class Cookie(name: String, value: String):
        def toResponse: HttpResponse.Cookie = HttpResponse.Cookie(name, value)
    end Cookie

    case class Part(
        name: String,
        filename: Maybe[String],
        contentType: Maybe[String],
        content: Array[Byte]
    ):
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
