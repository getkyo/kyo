package kyo

import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod as NettyMethod
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import io.netty.handler.codec.http.cookie.ServerCookieDecoder
import io.netty.handler.codec.http.multipart.*
import java.net.URI
import java.nio.charset.StandardCharsets
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*

/** Immutable HTTP request with builder API.
  *
  * Requests are constructed via factory methods and can be modified using builder methods that return new instances. The underlying Netty
  * request is created lazily when needed (e.g., when sending via HttpClient).
  *
  * The type parameter B tracks the body type: `HttpBody.Bytes` for fully-buffered bodies, `HttpBody.Streamed` for streaming bodies.
  */
final class HttpRequest[+B <: HttpBody] private (
    val method: HttpRequest.Method,
    private val _originalUrl: String,
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
        val uri      = new URI(_originalUrl) // TODO if we need to keep converting, maybe originalUrl should be URI?
        val rawPath  = uri.getRawPath
        val rawQuery = uri.getRawQuery
        val pathPart = if rawPath == null || rawPath.isEmpty then "/" else rawPath
        if rawQuery == null then pathPart else s"$pathPart?$rawQuery"
    end url

    /** Returns just the path without query string. */
    def path: String =
        val uri     = new URI(_originalUrl)
        val rawPath = uri.getRawPath
        if rawPath == null || rawPath.isEmpty then "/"
        else rawPath
    end path

    /** Returns the full URL including scheme and host. */
    def fullUrl: String =
        // If original URL has scheme, return as-is; otherwise construct from Host header
        if _originalUrl.startsWith("http://") || _originalUrl.startsWith("https://") then _originalUrl
        else
            header("Host") match
                case Absent => _originalUrl
                case Present(hostHeader) =>
                    val scheme = _scheme.getOrElse("http")
                    s"$scheme://$hostHeader$_originalUrl"

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
                val decoder = if strict then ServerCookieDecoder.STRICT else ServerCookieDecoder.LAX
                val decoded = decoder.decode(cookieHeader)
                val arr     = new Array[Cookie](decoded.size())
                val iter    = decoded.iterator()
                @tailrec def loop(i: Int): Unit =
                    if iter.hasNext then
                        val c = iter.next()
                        arr(i) = Cookie(c.name(), c.value())
                        loop(i + 1)
                loop(0)
                Span.fromUnsafe(arr)

    // --- Query parameter accessors ---

    def query(name: String): Maybe[String] =
        val uri         = new URI(_originalUrl)
        val queryString = uri.getRawQuery
        if queryString == null then Absent
        else
            val decoder = new QueryStringDecoder(queryString, false)
            val params  = decoder.parameters()
            if params.containsKey(name) then
                val values = params.get(name)
                if values.isEmpty then Absent else Present(values.get(0))
            else Absent
            end if
        end if
    end query

    def queryAll(name: String): Seq[String] =
        val uri         = new URI(_originalUrl)
        val queryString = uri.getRawQuery
        if queryString == null then Seq.empty
        else
            val decoder = new QueryStringDecoder(queryString, false)
            val params  = decoder.parameters()
            if params.containsKey(name) then params.get(name).asScala.toSeq
            else Seq.empty
        end if
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

end HttpRequest

object HttpRequest:

    // --- Extension methods for Bytes requests ---

    // TODO instead of using extension methods to provide the methods depending on the http body, let's put them as class members and take an evidence =:=. This way you can add @implicitNotFound and have a better error message on why the method is not available. With extension methods, the method is just not found. Please fix this for ALL files in kyo-http.
    extension (r: HttpRequest[HttpBody.Bytes])

        def bodyText: String =
            if r.body.data.isEmpty then "" else new String(r.body.data, StandardCharsets.UTF_8)

        def bodyBytes: Span[Byte] = r.body.span

        def bodyAs[A: Schema]: A =
            Schema[A].decode(r.bodyText)

        def parts: Span[Part] =
            // Need to convert to Netty request for multipart parsing
            val nettyReq = r.toNetty
            val factory  = new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE)
            val decoder  = new HttpPostRequestDecoder(factory, nettyReq)
            try
                @tailrec def loop(arr: Array[Part], i: Int): (Array[Part], Int) =
                    if !decoder.hasNext then (arr, i)
                    else
                        val data = decoder.next()
                        data match
                            case fileUpload: FileUpload =>
                                val newArr =
                                    if i == arr.length then
                                        val expanded = new Array[Part](arr.length * 2)
                                        java.lang.System.arraycopy(arr, 0, expanded, 0, arr.length)
                                        expanded
                                    else arr
                                newArr(i) = Part(
                                    name = fileUpload.getName,
                                    filename = if fileUpload.getFilename.isEmpty then Absent else Present(fileUpload.getFilename),
                                    contentType =
                                        if fileUpload.getContentType == null then Absent else Present(fileUpload.getContentType),
                                    content = fileUpload.get()
                                )
                                loop(newArr, i + 1)
                            case attribute: Attribute =>
                                val newArr =
                                    if i == arr.length then
                                        val expanded = new Array[Part](arr.length * 2)
                                        java.lang.System.arraycopy(arr, 0, expanded, 0, arr.length)
                                        expanded
                                    else arr
                                newArr(i) = Part(
                                    name = attribute.getName,
                                    filename = Absent,
                                    contentType = Absent,
                                    content = attribute.get()
                                )
                                loop(newArr, i + 1)
                            case _ =>
                                loop(arr, i)
                        end match

                val (arr, i) = loop(new Array[Part](4), 0)
                if i == 0 then Span.empty[Part]
                else if i == arr.length then Span.fromUnsafe(arr)
                else
                    val result = new Array[Part](i)
                    java.lang.System.arraycopy(arr, 0, result, 0, i)
                    Span.fromUnsafe(result)
                end if
            catch
                case _: HttpPostRequestDecoder.EndOfDataDecoderException =>
                    Span.empty[Part]
            finally
                decoder.destroy()
            end try
        end parts

        private[kyo] def toNetty: FullHttpRequest =
            import Method.toNetty
            val uri      = new URI(r.fullUrl)
            val rawPath  = uri.getRawPath
            val rawQuery = uri.getRawQuery
            val pathStr  = if rawPath == null || rawPath.isEmpty then "/" else rawPath
            val pathAndQuery =
                if rawQuery == null then pathStr
                else pathStr + "?" + rawQuery

            val bodyData   = r.body.data
            val content    = if bodyData.isEmpty then Unpooled.EMPTY_BUFFER else Unpooled.wrappedBuffer(bodyData)
            val nettyReq   = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, r.method.toNetty, pathAndQuery, content)
            val nettyHdrs  = nettyReq.headers()
            val headerSize = r.headers.size

            // Add all user headers
            @tailrec def addHeaders(i: Int): Unit =
                if i < headerSize then
                    val (name, value) = r.headers(i)
                    discard(nettyHdrs.add(name, value))
                    addHeaders(i + 1)
            addHeaders(0)

            // Set Content-Type if body present and not already set
            if bodyData.nonEmpty then
                r.contentType.foreach { ct =>
                    if !nettyHdrs.contains(HttpHeaderNames.CONTENT_TYPE) then
                        discard(nettyHdrs.set(HttpHeaderNames.CONTENT_TYPE, ct))
                }
                if !nettyHdrs.contains(HttpHeaderNames.CONTENT_LENGTH) then
                    discard(nettyHdrs.set(HttpHeaderNames.CONTENT_LENGTH, bodyData.length))
            end if

            nettyReq
        end toNetty

    end extension

    // --- Extension methods for Streamed requests ---

    // TODO move to class with =:= evidence + @implicitNotFound
    extension (r: HttpRequest[HttpBody.Streamed])
        def bodyStream(using Frame): Stream[Span[Byte], Async] = r.body.stream
    end extension

    // --- Universal body stream (works for both types) ---

    extension (r: HttpRequest[?])
        // TODO move to class method
        def bodyStreamUniversal(using Frame): Stream[Span[Byte], Async] =
            r.body match
                case b: HttpBody.Bytes    => Stream.init(Chunk(b.span))
                case s: HttpBody.Streamed => s.stream
    end extension

    // --- Factory methods for client-side requests ---

    // TODO common-to-use APIs should come first in the soruce file
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

        // Build headers with Host if from URL and not already present
        // TODO I think this code is duplicated?
        val headersWithHost =
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
        end headersWithHost

        new HttpRequest(
            method = method,
            _originalUrl = url,
            _headers = headersWithHost,
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

    // --- Internal: create from Netty request (server-side incoming requests) ---

    private[kyo] def fromNetty(nettyRequest: FullHttpRequest): HttpRequest[HttpBody.Bytes] =
        val method = Method.fromNetty(nettyRequest.method())
        val url    = nettyRequest.uri()

        // Extract headers
        val nettyHeaders = nettyRequest.headers()
        val headerCount  = nettyHeaders.size()
        val headers      = new Array[(String, String)](headerCount)
        val iter         = nettyHeaders.iteratorAsString()
        @tailrec def fillHeaders(i: Int): Unit =
            if i < headerCount && iter.hasNext then
                val entry = iter.next()
                headers(i) = (entry.getKey, entry.getValue)
                fillHeaders(i + 1)
        fillHeaders(0)

        // Extract body
        val content = nettyRequest.content()
        val bodyData =
            if content.readableBytes() == 0 then Array.empty[Byte]
            else
                val bytes = new Array[Byte](content.readableBytes())
                content.getBytes(content.readerIndex(), bytes)
                bytes

        // Extract content type
        val contentType =
            val ct = nettyHeaders.get(HttpHeaderNames.CONTENT_TYPE)
            if ct == null then Absent else Present(ct)

        new HttpRequest(
            method = method,
            _originalUrl = url,
            _headers = headers.toSeq,
            body = HttpBody(bodyData),
            _contentType = contentType,
            _scheme = Absent,
            _pathParams = Map.empty,
            _strictCookieParsing = false
        )
    end fromNetty

    /** Create from Netty headers (without aggregator) with provided body bytes. */
    private[kyo] def fromNettyHeaders(
        nettyRequest: io.netty.handler.codec.http.HttpRequest,
        bodyData: Array[Byte]
    ): HttpRequest[HttpBody.Bytes] =
        val method = Method.fromNetty(nettyRequest.method())
        val url    = nettyRequest.uri()

        val nettyHeaders = nettyRequest.headers()
        val headerCount  = nettyHeaders.size()
        val headers      = new Array[(String, String)](headerCount)
        val iter         = nettyHeaders.iteratorAsString()
        @tailrec def fillHeaders(i: Int): Unit =
            if i < headerCount && iter.hasNext then
                val entry = iter.next()
                headers(i) = (entry.getKey, entry.getValue)
                fillHeaders(i + 1)
        fillHeaders(0)

        val contentType =
            val ct = nettyHeaders.get(HttpHeaderNames.CONTENT_TYPE)
            if ct == null then Absent else Present(ct)

        new HttpRequest(
            method = method,
            _originalUrl = url,
            _headers = headers.toSeq,
            body = HttpBody(bodyData),
            _contentType = contentType,
            _scheme = Absent,
            _pathParams = Map.empty,
            _strictCookieParsing = false
        )
    end fromNettyHeaders

    /** Create a streaming request from Netty headers with a body stream. */
    private[kyo] def fromNettyStreaming(
        nettyRequest: io.netty.handler.codec.http.HttpRequest,
        bodyStream: Stream[Span[Byte], Async]
    ): HttpRequest[HttpBody.Streamed] =
        val method = Method.fromNetty(nettyRequest.method())
        val url    = nettyRequest.uri()

        val nettyHeaders = nettyRequest.headers()
        val headerCount  = nettyHeaders.size()
        val headers      = new Array[(String, String)](headerCount)
        val iter         = nettyHeaders.iteratorAsString()
        @tailrec def fillHeaders(i: Int): Unit =
            if i < headerCount && iter.hasNext then
                val entry = iter.next()
                headers(i) = (entry.getKey, entry.getValue)
                fillHeaders(i + 1)
        fillHeaders(0)

        val contentType =
            val ct = nettyHeaders.get(HttpHeaderNames.CONTENT_TYPE)
            if ct == null then Absent else Present(ct)

        new HttpRequest(
            method = method,
            _originalUrl = url,
            _headers = headers.toSeq,
            body = HttpBody.stream(bodyStream),
            _contentType = contentType,
            _scheme = Absent,
            _pathParams = Map.empty,
            _strictCookieParsing = false
        )
    end fromNettyStreaming

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

        // Build headers with Host if from URL and not already present
        // TODO this code also seems duplicated
        val headersWithHost =
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
        end headersWithHost

        new HttpRequest(
            method = method,
            _originalUrl = url,
            _headers = headersWithHost,
            body = HttpBody(bodyData),
            _contentType = contentType,
            _scheme = scheme,
            _pathParams = Map.empty,
            _strictCookieParsing = false
        )
    end create

    // --- Method type ---

    opaque type Method = NettyMethod

    object Method:
        given CanEqual[Method, Method] = CanEqual.derived

        val GET: Method     = NettyMethod.GET
        val POST: Method    = NettyMethod.POST
        val PUT: Method     = NettyMethod.PUT
        val PATCH: Method   = NettyMethod.PATCH
        val DELETE: Method  = NettyMethod.DELETE
        val HEAD: Method    = NettyMethod.HEAD
        val OPTIONS: Method = NettyMethod.OPTIONS
        val TRACE: Method   = NettyMethod.TRACE
        val CONNECT: Method = NettyMethod.CONNECT

        private[kyo] def fromNetty(m: NettyMethod): Method = m

        extension (m: Method)
            private[kyo] def toNetty: NettyMethod = m
            def name: String                      = m.name()
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
