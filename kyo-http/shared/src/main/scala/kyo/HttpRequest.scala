package kyo

import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod as NettyMethod
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import io.netty.handler.codec.http.multipart.*
import java.net.URI
import java.nio.charset.StandardCharsets
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*

opaque type HttpRequest = FullHttpRequest

object HttpRequest:

    def get(url: String, headers: Seq[(String, String)] = Seq.empty): HttpRequest =
        initBytes(Method.GET, url, Array.empty, headers, "")

    def post[A: Schema](url: String, body: A, headers: Seq[(String, String)] = Seq.empty): HttpRequest =
        init(Method.POST, url, body, headers)

    def put[A: Schema](url: String, body: A, headers: Seq[(String, String)] = Seq.empty): HttpRequest =
        init(Method.PUT, url, body, headers)

    def patch[A: Schema](url: String, body: A, headers: Seq[(String, String)] = Seq.empty): HttpRequest =
        init(Method.PATCH, url, body, headers)

    def delete(url: String, headers: Seq[(String, String)] = Seq.empty): HttpRequest =
        initBytes(Method.DELETE, url, Array.empty, headers, "")

    def head(url: String, headers: Seq[(String, String)] = Seq.empty): HttpRequest =
        initBytes(Method.HEAD, url, Array.empty, headers, "")

    def options(url: String, headers: Seq[(String, String)] = Seq.empty): HttpRequest =
        initBytes(Method.OPTIONS, url, Array.empty, headers, "")

    def multipart(url: String, parts: Seq[Part], headers: Seq[(String, String)] = Seq.empty): HttpRequest =
        val boundary    = "----" + java.util.UUID.randomUUID().toString
        val contentType = s"multipart/form-data; boundary=$boundary"
        val body        = buildMultipartBody(parts, boundary)
        initBytes(Method.POST, url, body, headers, contentType)
    end multipart

    def init[A: Schema](
        method: Method,
        url: String,
        body: A,
        headers: Seq[(String, String)] = Seq.empty
    ): HttpRequest =
        val json = Schema[A].encode(body)
        initBytes(method, url, json.getBytes(StandardCharsets.UTF_8), headers, "application/json")
    end init

    def init(
        method: Method,
        url: String,
        headers: Seq[(String, String)]
    ): HttpRequest =
        initBytes(method, url, Array.empty, headers, "")

    def init(
        method: Method,
        url: String
    ): HttpRequest =
        initBytes(method, url, Array.empty, Seq.empty, "")

    extension (request: HttpRequest)

        def method: Method = Method.fromNetty(request.method())

        def url: String = request.uri()

        def path: String =
            val uri = request.uri()
            val idx = uri.indexOf('?')
            if idx >= 0 then uri.substring(0, idx) else uri
        end path

        def host: String =
            header("Host").map { h =>
                val idx = h.indexOf(':')
                if idx >= 0 then h.substring(0, idx) else h
            }.getOrElse("")

        def port: Int =
            header("Host").map { h =>
                val idx = h.indexOf(':')
                if idx >= 0 then h.substring(idx + 1).toInt else 80
            }.getOrElse(80)

        def contentType: Maybe[String] = header("Content-Type")

        def header(name: String): Maybe[String] =
            val value = request.headers().get(name)
            if value == null then Absent else Present(value)

        def headers: Span[(String, String)] =
            val entries = request.headers().entries()
            val size    = entries.size()
            val arr     = new Array[(String, String)](size)
            val iter    = entries.iterator()
            @tailrec def loop(i: Int): Unit =
                if iter.hasNext then
                    val e = iter.next()
                    arr(i) = (e.getKey, e.getValue)
                    loop(i + 1)
            loop(0)
            Span.fromUnsafe(arr)
        end headers

        def cookie(name: String): Maybe[Cookie] =
            cookies.find(_.name == name)

        def cookies: Span[Cookie] =
            header("Cookie") match
                case Absent => Span.empty[Cookie]
                case Present(cookieHeader) =>
                    val len = cookieHeader.length

                    @tailrec def trimStart(partStart: Int, partEnd: Int): Int =
                        if partStart < partEnd && cookieHeader.charAt(partStart) == ' ' then trimStart(partStart + 1, partEnd)
                        else partStart

                    @tailrec def trimEnd(partStart: Int, partEnd: Int): Int =
                        if partEnd > partStart && cookieHeader.charAt(partEnd - 1) == ' ' then trimEnd(partStart, partEnd - 1)
                        else partEnd

                    // First pass: count valid cookies
                    @tailrec def countLoop(start: Int, count: Int): Int =
                        if start >= len then count
                        else
                            val end = cookieHeader.indexOf(';', start) match
                                case -1 => len;
                                case e  => e
                            val partStart = trimStart(start, end)
                            val partEnd   = trimEnd(partStart, end)
                            val eqIdx     = cookieHeader.indexOf('=', partStart)
                            val newCount  = if eqIdx > partStart && eqIdx < partEnd then count + 1 else count
                            countLoop(end + 1, newCount)

                    val count = countLoop(0, 0)
                    if count == 0 then Span.empty[Cookie]
                    else
                        val arr = new Array[Cookie](count)
                        // Second pass: collect cookies
                        @tailrec def collectLoop(start: Int, i: Int): Unit =
                            if start < len then
                                val end = cookieHeader.indexOf(';', start) match
                                    case -1 => len;
                                    case e  => e
                                val partStart = trimStart(start, end)
                                val partEnd   = trimEnd(partStart, end)
                                val eqIdx     = cookieHeader.indexOf('=', partStart)
                                if eqIdx > partStart && eqIdx < partEnd then
                                    arr(i) = Cookie(
                                        cookieHeader.substring(partStart, eqIdx),
                                        cookieHeader.substring(eqIdx + 1, partEnd)
                                    )
                                    collectLoop(end + 1, i + 1)
                                else
                                    collectLoop(end + 1, i)
                                end if
                        collectLoop(0, 0)
                        Span.fromUnsafe(arr)
                    end if

        def query(name: String): Maybe[String] =
            val uri      = request.uri()
            val queryIdx = uri.indexOf('?')
            if queryIdx < 0 then Absent
            else
                val queryString = uri.substring(queryIdx + 1)
                val decoder     = new QueryStringDecoder(queryString, false)
                val params      = decoder.parameters()
                if params.containsKey(name) then
                    val values = params.get(name)
                    if values.isEmpty then Absent else Present(values.get(0))
                else Absent
                end if
            end if
        end query

        def queryAll(name: String): Seq[String] =
            val uri      = request.uri()
            val queryIdx = uri.indexOf('?')
            if queryIdx < 0 then Seq.empty
            else
                val queryString = uri.substring(queryIdx + 1)
                val decoder     = new QueryStringDecoder(queryString, false)
                val params      = decoder.parameters()
                if params.containsKey(name) then params.get(name).asScala.toSeq
                else Seq.empty
            end if
        end queryAll

        def pathParam(name: String): Maybe[String] =
            // Path params are extracted by the router during request matching and stored as headers
            header(s"X-Path-Param-$name")

        def bodyText: String =
            val content = request.content()
            if content.readableBytes() == 0 then ""
            else content.toString(StandardCharsets.UTF_8)
        end bodyText

        def bodyBytes: Array[Byte] =
            val content = request.content()
            val bytes   = new Array[Byte](content.readableBytes())
            content.getBytes(content.readerIndex(), bytes)
            bytes
        end bodyBytes

        def bodyAs[A: Schema]: A =
            Schema[A].decode(bodyText)

        def bodyAsStream[A: Schema](using Tag[Emit[Chunk[A]]], Frame): Stream[A, Async] =
            // For a full request, we just have one body - wrap it in a single-element stream
            Stream.init(Seq(bodyAs[A]))
        end bodyAsStream

        def parts: Span[Part] =
            val factory = new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE)
            val decoder = new HttpPostRequestDecoder(factory, request)
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

        // Common header accessors
        def authorization: Maybe[String]   = header("Authorization")
        def accept: Maybe[String]          = header("Accept")
        def userAgent: Maybe[String]       = header("User-Agent")
        def acceptLanguage: Maybe[String]  = header("Accept-Language")
        def acceptEncoding: Maybe[String]  = header("Accept-Encoding")
        def cacheControl: Maybe[String]    = header("Cache-Control")
        def ifNoneMatch: Maybe[String]     = header("If-None-Match")
        def ifModifiedSince: Maybe[String] = header("If-Modified-Since")
    end extension

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

    case class Cookie(name: String, value: String):
        def toResponse: HttpResponse.Cookie = HttpResponse.Cookie(name, value)
    end Cookie

    case class Part(
        name: String,
        filename: Maybe[String],
        contentType: Maybe[String],
        content: Array[Byte]
    )

    private def initBytes(
        method: Method,
        url: String,
        body: Array[Byte],
        headers: Seq[(String, String)],
        contentType: String
    ): HttpRequest =
        import Method.toNetty
        require(url.nonEmpty, "URL cannot be empty")
        val uri      = new URI(url)
        val rawPath  = uri.getRawPath
        val rawQuery = uri.getRawQuery
        val path     = if rawPath == null || rawPath.isEmpty then "/" else rawPath
        val pathAndQuery =
            if rawQuery == null then path
            else path + "?" + rawQuery
        val content = if body.isEmpty then Unpooled.EMPTY_BUFFER else Unpooled.wrappedBuffer(body)
        val request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method.toNetty, pathAndQuery, content)
        val port    = uri.getPort
        val host =
            if port > 0 && port != 80 && port != 443 then
                uri.getHost + ":" + port
            else
                uri.getHost
        discard(request.headers().set(HttpHeaderNames.HOST, host))
        if body.nonEmpty then
            discard(request.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType))
            discard(request.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length))
        headers.foreach { case (name, value) =>
            discard(request.headers().set(name, value))
        }
        request
    end initBytes

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
