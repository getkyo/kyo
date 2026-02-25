package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.HttpRoute.ContentType
import kyo.HttpRoute.Field
import kyo.HttpRoute.RequestDef
import kyo.HttpRoute.ResponseDef
import kyo.discard
import scala.annotation.tailrec

/** Shared codec toolkit for translating between typed route definitions (Record2-based) and wire-level HTTP primitives. Used internally by
  * backend implementations to avoid duplicating marshalling logic across platforms (Netty, curl, fetch).
  *
  * Encode methods use continuations to avoid intermediate tuple allocations. Decode methods return typed HttpRequest/HttpResponse directly
  * — all unsafe Dict[String, Any] access is encapsulated here.
  */
object RouteUtil:

    private val utf8 = StandardCharsets.UTF_8

    // ==================== Route inspection ====================

    /** Whether the route's request body requires streaming transport. */
    def isStreamingRequest[In, Out, S](route: HttpRoute[In, Out, S]): Boolean =
        findBodyField(route.request.fields) match
            case Present(body) => isStreamingContentType(body.contentType)
            case Absent        => false

    /** Whether the route's response body requires streaming transport. */
    def isStreamingResponse[In, Out, S](route: HttpRoute[In, Out, S]): Boolean =
        findBodyField(route.response.fields) match
            case Present(body) => isStreamingContentType(body.contentType)
            case Absent        => false

    // ==================== Client: encode request ====================

    inline def encodeRequest[In, Out, S, A](
        route: HttpRoute[In, Out, S],
        request: HttpRequest[In]
    )(
        inline onEmpty: ( /* url */ String, HttpHeaders) => A,
        inline onBuffered: ( /* url */ String, HttpHeaders, /* contentType */ String, Span[Byte]) => A,
        inline onStreaming: ( /* url */ String, HttpHeaders, /* contentType */ String, Stream[Span[Byte], Async]) => A
    )(using Frame): A =
        val fields    = route.request.fields
        val dict      = request.fields.dict
        val bodyField = findBodyField(fields)
        val hasParams = fields.size > (if bodyField.isDefined then 1 else 0)

        // Use request.url.path when explicitly set (e.g., redirects), otherwise build from route captures
        val basePath =
            if request.url.path.nonEmpty then request.url.path
            else buildPath(route.request.path, dict)

        // Compute url and headers, then encode body once
        val (url, hdrs) =
            if hasParams then
                val queryBuilder  = new StringBuilder
                val headerBuilder = ChunkBuilder.init[String]
                val cookieBuilder = encodeRequestParams(fields, dict, queryBuilder, headerBuilder)
                cookieBuilder match
                    case Present(cb) =>
                        discard(headerBuilder += "Cookie")
                        discard(headerBuilder += cb.toString)
                    case Absent =>
                end match
                val extraHeaders = HttpHeaders.fromChunk(headerBuilder.result())
                // Preserve query string from request URL (e.g., from redirects) and merge with route query params
                val url = (request.url.rawQuery, queryBuilder.nonEmpty) match
                    case (Present(rq), true)  => s"$basePath?$rq&$queryBuilder"
                    case (Present(rq), false) => s"$basePath?$rq"
                    case (_, true)            => s"$basePath?$queryBuilder"
                    case _                    => basePath
                val hdrs = if extraHeaders.isEmpty then request.headers
                else request.headers.concat(extraHeaders)
                (url, hdrs)
            else
                // Preserve query string from request URL (e.g., from redirects)
                val url = request.url.rawQuery match
                    case Present(rq) => s"$basePath?$rq"
                    case _           => basePath
                (url, request.headers)
        encodeBody(bodyField, dict, url, hdrs)(onEmpty, onBuffered, onStreaming)
    end encodeRequest

    private inline def encodeBody[A](
        bodyField: Maybe[Field.Body[?, ?]],
        dict: Dict[String, Any],
        url: String,
        headers: HttpHeaders
    )(
        inline onEmpty: (String, HttpHeaders) => A,
        inline onBuffered: (String, HttpHeaders, String, Span[Byte]) => A,
        inline onStreaming: (String, HttpHeaders, String, Stream[Span[Byte], Async]) => A
    )(using Frame): A =
        bodyField match
            case Absent => onEmpty(url, headers)
            case Present(body) =>
                val value = dict(body.fieldName)
                if isStreamingContentType(body.contentType) then
                    encodeStreamBodyValue(body.contentType, value) { (ct, stream) =>
                        onStreaming(url, headers, ct, stream)
                    }
                else
                    encodeBufferedBodyValue(body.contentType, value) { (ct, bytes) =>
                        onBuffered(url, headers, ct, bytes)
                    }
                end if

    // ==================== Client: decode response ====================

    def decodeBufferedResponse[In, Out, S](
        route: HttpRoute[In, Out, S],
        status: HttpStatus,
        headers: HttpHeaders,
        body: Span[Byte]
    )(using Frame): Result[HttpError, HttpResponse[Out]] =
        val fields    = route.response.fields
        val bodyField = findBodyField(fields)
        val hasParams = fields.size > (if bodyField.isDefined then 1 else 0)

        // Fast path: no params to decode
        if !hasParams then
            bodyField match
                case Absent =>
                    Result.succeed(HttpResponse(status, headers, Record2.empty.asInstanceOf[Record2[Out]]))
                case Present(bf) =>
                    decodeBufferedBodyValue(bf.contentType, body, headers).map { value =>
                        val builder = DictBuilder.init[String, Any]
                        discard(builder.add(bf.fieldName, value))
                        HttpResponse(status, headers, Record2(builder.result()))
                    }
        else
            val builder = DictBuilder.init[String, Any]
            decodeParamFields(fields, headers, Absent, builder, isResponse = true).flatMap { _ =>
                bodyField match
                    case Absent =>
                        Result.succeed(HttpResponse(status, headers, Record2(builder.result())))
                    case Present(bf) =>
                        decodeBufferedBodyValue(bf.contentType, body, headers).map { value =>
                            discard(builder.add(bf.fieldName, value))
                            HttpResponse(status, headers, Record2(builder.result()))
                        }
            }
        end if
    end decodeBufferedResponse

    def decodeStreamingResponse[In, Out, S](
        route: HttpRoute[In, Out, S],
        status: HttpStatus,
        headers: HttpHeaders,
        stream: Stream[Span[Byte], Async]
    )(using Frame): Result[HttpError, HttpResponse[Out]] =
        val fields    = route.response.fields
        val bodyField = findBodyField(fields)
        val hasParams = fields.size > (if bodyField.isDefined then 1 else 0)

        val builder = DictBuilder.init[String, Any]

        if !hasParams then
            bodyField.foreach(bf => discard(builder.add(bf.fieldName, decodeStreamBodyValue(bf.contentType, stream, headers))))
            Result.succeed(HttpResponse(status, headers, Record2(builder.result())))
        else
            decodeParamFields(fields, headers, Absent, builder, isResponse = true).map { _ =>
                bodyField.foreach(bf => discard(builder.add(bf.fieldName, decodeStreamBodyValue(bf.contentType, stream, headers))))
                HttpResponse(status, headers, Record2(builder.result()))
            }
        end if
    end decodeStreamingResponse

    // ==================== Server: decode request ====================

    def decodeBufferedRequest[In, Out, S](
        route: HttpRoute[In, Out, S],
        pathCaptures: Dict[String, String],
        queryParam: Maybe[HttpUrl],
        headers: HttpHeaders,
        body: Span[Byte],
        path: String = ""
    )(using Frame): Result[HttpError, HttpRequest[In]] =
        val fields    = route.request.fields
        val bodyField = findBodyField(fields)
        val hasParams = fields.size > (if bodyField.isDefined then 1 else 0)
        val builder   = DictBuilder.init[String, Any]

        decodeCaptures(route.request.path, pathCaptures, builder).flatMap { _ =>
            val paramsResult =
                if hasParams then decodeParamFields(fields, headers, queryParam, builder)
                else Result.unit
            paramsResult.flatMap { _ =>
                bodyField match
                    case Absent =>
                        Result.succeed(buildRequest(route, headers, builder, path))
                    case Present(bf) =>
                        decodeBufferedBodyValue(bf.contentType, body, headers).map { value =>
                            discard(builder.add(bf.fieldName, value))
                            buildRequest(route, headers, builder, path)
                        }
            }
        }
    end decodeBufferedRequest

    def decodeStreamingRequest[In, Out, S](
        route: HttpRoute[In, Out, S],
        pathCaptures: Dict[String, String],
        queryParam: Maybe[HttpUrl],
        headers: HttpHeaders,
        stream: Stream[Span[Byte], Async],
        path: String = ""
    )(using Frame): Result[HttpError, HttpRequest[In]] =
        val fields    = route.request.fields
        val bodyField = findBodyField(fields)
        val hasParams = fields.size > (if bodyField.isDefined then 1 else 0)
        val builder   = DictBuilder.init[String, Any]

        decodeCaptures(route.request.path, pathCaptures, builder).flatMap { _ =>
            val paramsResult =
                if hasParams then decodeParamFields(fields, headers, queryParam, builder)
                else Result.unit
            paramsResult.map { _ =>
                bodyField.foreach(bf => discard(builder.add(bf.fieldName, decodeStreamBodyValue(bf.contentType, stream, headers))))
                buildRequest(route, headers, builder, path)
            }
        }
    end decodeStreamingRequest

    // ==================== Server: encode response ====================

    def encodeResponse[In, Out, S, A](
        route: HttpRoute[In, Out, S],
        response: HttpResponse[Out]
    )(
        onEmpty: (HttpStatus, HttpHeaders) => A,
        onBuffered: (HttpStatus, HttpHeaders, /* contentType */ String, Span[Byte]) => A,
        onStreaming: (HttpStatus, HttpHeaders, /* contentType */ String, Stream[Span[Byte], Async]) => A
    )(using Frame): A =
        val fields    = route.response.fields
        val status    = response.status
        val bodyField = findBodyField(fields)
        val hasParams = fields.size > (if bodyField.isDefined then 1 else 0)

        // Fast path: no param headers to encode
        if !hasParams then
            encodeResponseBody(bodyField, response.fields.dict, status, response.headers)(onEmpty, onBuffered, onStreaming)
        else
            val dict          = response.fields.dict
            val headerBuilder = ChunkBuilder.init[String]
            encodeResponseParams(fields, dict, headerBuilder)
            val extraHeaders = HttpHeaders.fromChunk(headerBuilder.result())
            val headers = if extraHeaders.isEmpty then response.headers
            else response.headers.concat(extraHeaders)
            encodeResponseBody(bodyField, dict, status, headers)(onEmpty, onBuffered, onStreaming)
        end if
    end encodeResponse

    private def encodeResponseBody[A](
        bodyField: Maybe[Field.Body[?, ?]],
        dict: Dict[String, Any],
        status: HttpStatus,
        headers: HttpHeaders
    )(
        onEmpty: (HttpStatus, HttpHeaders) => A,
        onBuffered: (HttpStatus, HttpHeaders, String, Span[Byte]) => A,
        onStreaming: (HttpStatus, HttpHeaders, String, Stream[Span[Byte], Async]) => A
    )(using Frame): A =
        bodyField match
            case Absent => onEmpty(status, headers)
            case Present(body) =>
                val value = dict(body.fieldName)
                if isStreamingContentType(body.contentType) then
                    encodeStreamBodyValue(body.contentType, value) { (ct, stream) =>
                        onStreaming(status, headers, ct, stream)
                    }
                else
                    encodeBufferedBodyValue(body.contentType, value) { (ct, bytes) =>
                        onBuffered(status, headers, ct, bytes)
                    }
                end if

    // ==================== Error mapping ====================

    def matchError[In, Out, S](
        route: HttpRoute[In, Out, S],
        status: HttpStatus,
        body: Span[Byte]
    )(using Frame): Maybe[Any] =
        val errors = route.response.errors
        if errors.isEmpty then Absent
        else
            @tailrec def loop(i: Int, bodyStr: Maybe[String]): Maybe[Any] =
                if i >= errors.size then Absent
                else
                    val mapping = errors(i)
                    if mapping.status == status then
                        val str = bodyStr match
                            case Present(s) => s
                            case Absent     => spanToString(body)
                        mapping.schema.asInstanceOf[Schema[Any]].decode(str) match
                            case Result.Success(err) => Present(err)
                            case _                   => loop(i + 1, Present(str))
                    else loop(i + 1, bodyStr)
                    end if
            loop(0, Absent)
        end if
    end matchError

    // ==================== Internal: Span[Byte] <-> String ====================

    private def spanToString(bytes: Span[Byte]): String =
        if bytes.isEmpty then ""
        else new String(bytes.toArrayUnsafe, utf8)

    private def stringToSpan(s: String): Span[Byte] =
        Span.fromUnsafe(s.getBytes(utf8))

    // ==================== Internal: path building ====================

    private def buildPath(path: HttpPath[?], dict: Dict[String, Any]): String =
        val sb = new StringBuilder
        discard(sb.append('/'))
        appendPath(path, dict, sb)
        sb.toString
    end buildPath

    private def appendPath(path: HttpPath[?], dict: Dict[String, Any], sb: StringBuilder): Unit =
        path match
            case HttpPath.Literal(value) =>
                if value.nonEmpty then
                    if sb.length > 1 && !value.startsWith("/") then discard(sb.append('/'))
                    discard(sb.append(value))
            case c: HttpPath.Capture[?, ?] =>
                val value   = dict(c.fieldName)
                val encoded = c.codec.asInstanceOf[HttpCodec[Any]].encode(value)
                if sb.length > 1 then discard(sb.append('/'))
                discard(sb.append(java.net.URLEncoder.encode(encoded, "UTF-8")))
            case r: HttpPath.Rest[?] =>
                val value = dict(r.fieldName).asInstanceOf[String]
                if value.nonEmpty then
                    if sb.length > 1 && !value.startsWith("/") then discard(sb.append('/'))
                    discard(sb.append(value))
            case c: HttpPath.Concat[?, ?] =>
                appendPath(c.left, dict, sb)
                appendPath(c.right, dict, sb)
    end appendPath

    // ==================== Internal: path capture decoding ====================

    private def decodeCaptures(
        path: HttpPath[?],
        captures: Dict[String, String],
        builder: DictBuilder[String, Any]
    )(using Frame): Result[HttpError, Unit] =
        path match
            case _: HttpPath.Literal => Result.unit
            case c: HttpPath.Capture[?, ?] =>
                val wireName = if c.wireName.isEmpty then c.fieldName else c.wireName
                captures.get(wireName) match
                    case Present(raw) =>
                        try
                            discard(builder.add(c.fieldName, c.codec.decode(raw)))
                            Result.unit
                        catch
                            case e: Throwable =>
                                Result.fail(HttpError.ParseError(s"Failed to decode path capture '$wireName': ${e.getMessage}"))
                    case Absent =>
                        Result.fail(HttpError.ParseError(s"Missing path capture: $wireName"))
                end match
            case r: HttpPath.Rest[?] =>
                captures.get(r.fieldName) match
                    case Present(raw) =>
                        discard(builder.add(r.fieldName, raw))
                        Result.unit
                    case Absent =>
                        discard(builder.add(r.fieldName, ""))
                        Result.unit
                end match
            case c: HttpPath.Concat[?, ?] =>
                decodeCaptures(c.left, captures, builder).flatMap(_ => decodeCaptures(c.right, captures, builder))
    end decodeCaptures

    // ==================== Internal: param encoding ====================

    private def encodeRequestParams(
        fields: Chunk[Field[?]],
        dict: Dict[String, Any],
        queryBuilder: StringBuilder,
        headerBuilder: ChunkBuilder[String]
    ): Maybe[StringBuilder] =
        @tailrec def loop(i: Int, cookieBuilder: Maybe[StringBuilder]): Maybe[StringBuilder] =
            if i >= fields.size then cookieBuilder
            else
                fields(i) match
                    case param: Field.Param[?, ?, ?] =>
                        val wireName = if param.wireName.isEmpty then param.fieldName else param.wireName
                        dict.get(param.fieldName) match
                            case Present(rawValue) =>
                                unwrapOptional(param.optional, rawValue) match
                                    case Present(v) =>
                                        val encoded = param.codec.asInstanceOf[HttpCodec[Any]].encode(v)
                                        param.kind match
                                            case Field.Param.Location.Query =>
                                                if queryBuilder.nonEmpty then discard(queryBuilder.append('&'))
                                                discard(queryBuilder
                                                    .append(java.net.URLEncoder.encode(wireName, "UTF-8"))
                                                    .append('=')
                                                    .append(java.net.URLEncoder.encode(encoded, "UTF-8")))
                                                loop(i + 1, cookieBuilder)
                                            case Field.Param.Location.Header =>
                                                discard(headerBuilder += wireName)
                                                discard(headerBuilder += encoded)
                                                loop(i + 1, cookieBuilder)
                                            case Field.Param.Location.Cookie =>
                                                val cb = cookieBuilder match
                                                    case Present(sb) =>
                                                        discard(sb.append("; "))
                                                        sb
                                                    case Absent =>
                                                        new StringBuilder
                                                discard(cb.append(wireName).append('=').append(encoded))
                                                loop(i + 1, Present(cb))
                                        end match
                                    case _ => loop(i + 1, cookieBuilder)
                                end match
                            case Absent => loop(i + 1, cookieBuilder)
                        end match
                    case _: Field.Body[?, ?] => loop(i + 1, cookieBuilder)
                end match
        loop(0, Absent)
    end encodeRequestParams

    private def encodeResponseParams(
        fields: Chunk[Field[?]],
        dict: Dict[String, Any],
        headerBuilder: ChunkBuilder[String]
    ): Unit =
        @tailrec def loop(i: Int): Unit =
            if i < fields.size then
                fields(i) match
                    case param: Field.Param[?, ?, ?] =>
                        val wireName = if param.wireName.isEmpty then param.fieldName else param.wireName
                        dict.get(param.fieldName) match
                            case Present(rawValue) =>
                                param.kind match
                                    case Field.Param.Location.Header =>
                                        unwrapOptional(param.optional, rawValue) match
                                            case Present(v) =>
                                                discard(headerBuilder += wireName)
                                                discard(headerBuilder += param.codec.asInstanceOf[HttpCodec[Any]].encode(v))
                                            case _ =>
                                        end match
                                    case Field.Param.Location.Cookie =>
                                        unwrapOptional(param.optional, rawValue) match
                                            case Present(cookie: HttpCookie[?]) =>
                                                discard(headerBuilder += "Set-Cookie")
                                                discard(headerBuilder += HttpHeaders.serializeCookie(wireName, cookie))
                                            case _ =>
                                        end match
                                    case _ =>
                                end match
                            case Absent =>
                        end match
                    case _: Field.Body[?, ?] =>
                end match
                loop(i + 1)
        loop(0)
    end encodeResponseParams

    // ==================== Internal: param decoding ====================

    private def decodeParam(
        param: Field.Param[?, ?, ?],
        headers: HttpHeaders,
        queryParam: Maybe[HttpUrl],
        isResponse: Boolean = false
    )(using Frame): Result[HttpError, Any] =
        val wireName = if param.wireName.isEmpty then param.fieldName else param.wireName
        val raw: Maybe[String] = param.kind match
            case Field.Param.Location.Query =>
                queryParam match
                    case Present(url) => url.query(wireName)
                    case Absent       => Absent
            case Field.Param.Location.Header =>
                headers.get(wireName)
            case Field.Param.Location.Cookie =>
                if isResponse then headers.responseCookie(wireName)
                else headers.cookie(wireName)

        raw match
            case Present(str) =>
                try
                    val decoded = param.codec.decode(str)
                    val value =
                        if isResponse && param.kind == Field.Param.Location.Cookie then
                            HttpCookie(decoded)(using param.codec.asInstanceOf[HttpCodec[Any]])
                        else decoded
                    if param.optional then Result.succeed(Present(value))
                    else Result.succeed(value)
                catch
                    case e: Throwable =>
                        Result.fail(HttpError.ParseError(s"Failed to decode param '$wireName': ${e.getMessage}"))
            case Absent =>
                param.default match
                    case Present(d) =>
                        if param.optional then Result.succeed(Present(d))
                        else Result.succeed(d)
                    case Absent =>
                        if param.optional then Result.succeed(Absent)
                        else Result.fail(HttpError.ParseError(s"Missing required param: $wireName"))
        end match
    end decodeParam

    private def decodeParamFields(
        fields: Chunk[Field[?]],
        headers: HttpHeaders,
        queryParam: Maybe[HttpUrl],
        builder: DictBuilder[String, Any],
        isResponse: Boolean = false
    )(using Frame): Result[HttpError, Unit] =
        def loop(i: Int): Result[HttpError, Unit] =
            if i >= fields.size then Result.unit
            else
                fields(i) match
                    case param: Field.Param[?, ?, ?] =>
                        decodeParam(param, headers, queryParam, isResponse).flatMap { value =>
                            discard(builder.add(param.fieldName, value))
                            loop(i + 1)
                        }
                    case _: Field.Body[?, ?] => loop(i + 1)
                end match
        loop(0)
    end decodeParamFields

    // ==================== Internal: optional unwrapping ====================

    private def unwrapOptional(optional: Boolean, rawValue: Any): Maybe[Any] =
        if optional then
            rawValue.asInstanceOf[Maybe[Any]]
        else
            Present(rawValue)

    // ==================== Internal: body encoding ====================

    private def encodeBufferedBodyValue[A](ct: ContentType[?], value: Any)(f: (String, Span[Byte]) => A): A =
        ct match
            case _: ContentType.Text =>
                f("text/plain; charset=utf-8", stringToSpan(value.asInstanceOf[String]))
            case _: ContentType.Binary =>
                f("application/octet-stream", value.asInstanceOf[Span[Byte]])
            case json: ContentType.Json[?] =>
                val str = json.schema.asInstanceOf[Schema[Any]].encode(value)
                f("application/json", stringToSpan(str))
            case form: ContentType.Form[?] =>
                val str = form.codec.asInstanceOf[HttpFormCodec[Any]].encode(value)
                f("application/x-www-form-urlencoded", stringToSpan(str))
            case _: ContentType.Multipart =>
                val parts    = value.asInstanceOf[Seq[HttpPart]]
                val boundary = java.util.UUID.randomUUID().toString
                val bytes    = encodeMultipartParts(parts, boundary)
                f(s"multipart/form-data; boundary=$boundary", bytes)
            case _ =>
                throw new IllegalStateException(s"Cannot encode streaming ContentType as buffered: $ct")
    end encodeBufferedBodyValue

    private def encodeStreamBodyValue[A](ct: ContentType[?], value: Any)(
        f: (String, Stream[Span[Byte], Async]) => A
    )(using Frame): A =
        ct match
            case _: ContentType.ByteStream =>
                f("application/octet-stream", value.asInstanceOf[Stream[Span[Byte], Async]])
            case ndjson: ContentType.Ndjson[?] =>
                val stream = value.asInstanceOf[Stream[Any, Async]]
                val schema = ndjson.schema.asInstanceOf[Schema[Any]]
                val byteStream = stream.mapPure { v =>
                    stringToSpan(schema.encode(v) + "\n")
                }(using ndjson.emitTag.asInstanceOf[Tag[Emit[Chunk[Any]]]], Tag[Emit[Chunk[Span[Byte]]]])
                f("application/x-ndjson", byteStream)
            case sse: ContentType.Sse[?] =>
                val stream = value.asInstanceOf[Stream[HttpEvent[Any], Async]]
                val schema = sse.schema.asInstanceOf[Schema[Any]]
                val byteStream = stream.mapPure { event =>
                    val sb = new StringBuilder
                    event.event match
                        case Present(e) => discard(sb.append("event: ").append(e).append('\n'))
                        case Absent     =>
                    event.id match
                        case Present(id) => discard(sb.append("id: ").append(id).append('\n'))
                        case Absent      =>
                    event.retry match
                        case Present(r) => discard(sb.append("retry: ").append(r.toMillis).append('\n'))
                        case Absent     =>
                    discard(sb.append("data: ").append(schema.encode(event.data)).append("\n\n"))
                    stringToSpan(sb.toString)
                }(using sse.emitTag.asInstanceOf[Tag[Emit[Chunk[HttpEvent[Any]]]]], Tag[Emit[Chunk[Span[Byte]]]])
                f("text/event-stream", byteStream)
            case sseText: ContentType.SseText =>
                val stream = value.asInstanceOf[Stream[HttpEvent[String], Async]]
                val byteStream = stream.mapPure { event =>
                    val sb = new StringBuilder
                    event.event match
                        case Present(e) => discard(sb.append("event: ").append(e).append('\n'))
                        case Absent     =>
                    event.id match
                        case Present(id) => discard(sb.append("id: ").append(id).append('\n'))
                        case Absent      =>
                    event.retry match
                        case Present(r) => discard(sb.append("retry: ").append(r.toMillis).append('\n'))
                        case Absent     =>
                    // Per SSE spec, split multiline data into multiple data: lines
                    val dataLines = event.data.split('\n')
                    @tailrec def appendDataLines(i: Int): Unit =
                        if i < dataLines.length then
                            discard(sb.append("data: ").append(dataLines(i)).append('\n'))
                            appendDataLines(i + 1)
                    appendDataLines(0)
                    discard(sb.append('\n'))
                    stringToSpan(sb.toString)
                }(using sseText.emitTag, Tag[Emit[Chunk[Span[Byte]]]])
                f("text/event-stream", byteStream)
            case _: ContentType.MultipartStream =>
                val stream   = value.asInstanceOf[Stream[HttpPart, Async]]
                val boundary = java.util.UUID.randomUUID().toString
                val byteStream = stream.mapPure { part =>
                    encodeMultipartPart(part, boundary)
                }(using Tag[Emit[Chunk[HttpPart]]], Tag[Emit[Chunk[Span[Byte]]]])
                val closingBoundary = Stream.init(Seq(stringToSpan(s"--$boundary--\r\n")))
                f(s"multipart/form-data; boundary=$boundary", byteStream.concat(closingBoundary))
            case _ =>
                throw new IllegalStateException(s"Cannot encode non-streaming ContentType as stream: $ct")
    end encodeStreamBodyValue

    // ==================== Internal: body decoding ====================

    private def decodeBufferedBodyValue(
        ct: ContentType[?],
        bytes: Span[Byte],
        headers: HttpHeaders
    )(using Frame): Result[HttpError, Any] =
        ct match
            case _: ContentType.Text =>
                Result.succeed(spanToString(bytes))
            case _: ContentType.Binary =>
                Result.succeed(bytes)
            case json: ContentType.Json[?] =>
                json.schema.decode(spanToString(bytes))
                    .mapFailure(msg => HttpError.ParseError(s"JSON decode failed: $msg"))
            case form: ContentType.Form[?] =>
                try Result.succeed(form.codec.decode(spanToString(bytes)))
                catch
                    case e: Throwable =>
                        Result.fail(HttpError.ParseError(s"Form decode failed: ${e.getMessage}"))
            case _: ContentType.Multipart =>
                parseMultipartBody(spanToString(bytes), headers)
            case _ =>
                Result.fail(HttpError.ParseError(s"Cannot decode streaming ContentType as buffered: $ct"))
    end decodeBufferedBodyValue

    private def decodeStreamBodyValue(
        ct: ContentType[?],
        stream: Stream[Span[Byte], Async],
        headers: HttpHeaders
    )(using Frame): Any =
        ct match
            case _: ContentType.ByteStream =>
                stream
            case ndjson: ContentType.Ndjson[?] =>
                val schema = ndjson.schema
                splitLines(stream, "\n").mapPure { line =>
                    schema.decode(line) match
                        case Result.Success(v)   => v
                        case Result.Failure(msg) => throw new RuntimeException(s"Ndjson decode failed: $msg")
                        case p: Result.Panic     => throw p.exception
                    end match
                }(using Tag[Emit[Chunk[String]]], ndjson.emitTag)
            case sse: ContentType.Sse[?] =>
                val schema = sse.schema.asInstanceOf[Schema[Any]]
                splitLines(stream, "\n\n").mapPure { frame =>
                    parseSseFrame(schema, frame)
                }(using Tag[Emit[Chunk[String]]], sse.emitTag.asInstanceOf[Tag[Emit[Chunk[HttpEvent[Any]]]]])
            case sseText: ContentType.SseText =>
                splitLines(stream, "\n\n").mapPure { frame =>
                    parseSseFrameText(frame)
                }(using Tag[Emit[Chunk[String]]], sseText.emitTag)
            case _: ContentType.MultipartStream =>
                parseMultipartStream(stream, headers)
            case _ =>
                throw new IllegalStateException(s"Cannot decode non-streaming ContentType as stream: $ct")
    end decodeStreamBodyValue

    /** Splits a byte stream into string segments by delimiter, handling cross-chunk boundaries. */
    private def splitLines(
        stream: Stream[Span[Byte], Async],
        delimiter: String
    )(using Frame): Stream[String, Async] =
        // Accumulate bytes, split on delimiter, carry leftover to next chunk
        val byteTag  = Tag[Emit[Chunk[Span[Byte]]]]
        val strTag   = Tag[Emit[Chunk[String]]]
        var leftover = ""
        stream.mapChunkPure[Span[Byte], String] { chunk =>
            val sb = new StringBuilder(leftover)
            chunk.foreach(span => discard(sb.append(spanToString(span))))
            val combined = sb.toString
            val parts    = combined.split(java.util.regex.Pattern.quote(delimiter), -1)
            if parts.length <= 1 then
                leftover = combined
                Seq.empty
            else
                leftover = parts.last
                val result = ChunkBuilder.init[String]
                @tailrec def loop(i: Int): Unit =
                    if i < parts.length - 1 then
                        val part = parts(i).trim
                        if part.nonEmpty then discard(result += part)
                        loop(i + 1)
                loop(0)
                result.result()
            end if
        }(using byteTag, strTag)
    end splitLines

    /** Parses SSE frame fields shared by both JSON and text SSE decoders. Vars justified for performance. */
    private inline def parseSseFields[A](frame: String)(inline decodeData: String => A): HttpEvent[A] =
        var eventName: Maybe[String] = Absent
        var id: Maybe[String]        = Absent
        var retry: Maybe[Duration]   = Absent
        val dataBuilder              = new StringBuilder
        var hasData                  = false
        val lines                    = frame.split('\n')
        @tailrec def loop(i: Int): Unit =
            if i < lines.length then
                val line = lines(i)
                if line.startsWith("event:") then
                    eventName = Present(line.substring(6).trim)
                else if line.startsWith("id:") then
                    id = Present(line.substring(3).trim)
                else if line.startsWith("retry:") then
                    val ms = line.substring(6).trim.toLong
                    retry = Present(Duration.fromNanos(ms * 1000000))
                else if line.startsWith("data:") then
                    if hasData then discard(dataBuilder.append('\n'))
                    discard(dataBuilder.append(line.substring(5).trim))
                    hasData = true
                end if
                loop(i + 1)
        loop(0)
        HttpEvent(decodeData(dataBuilder.toString), eventName, id, retry)
    end parseSseFields

    /** Parses a single SSE frame string into an HttpEvent. */
    private def parseSseFrame(schema: Schema[Any], frame: String): HttpEvent[Any] =
        parseSseFields(frame) { data =>
            schema.decode(data) match
                case Result.Success(v)   => v
                case Result.Failure(msg) => throw new RuntimeException(s"SSE data decode failed: $msg")
                case p: Result.Panic     => throw p.exception
        }
    end parseSseFrame

    /** Parses a single SSE frame string into an HttpEvent with plain text data (no JSON decode). */
    private def parseSseFrameText(frame: String): HttpEvent[String] =
        parseSseFields(frame)(identity)
    end parseSseFrameText

    /** Parses a multipart byte stream into a stream of HttpPart. */
    private def parseMultipartStream(
        stream: Stream[Span[Byte], Async],
        headers: HttpHeaders
    )(using Frame): Stream[HttpPart, Async] =
        val boundary = headers.get("Content-Type").flatMap { ct =>
            val idx = ct.indexOf("boundary=")
            if idx >= 0 then Present(ct.substring(idx + 9).trim)
            else Absent
        }
        boundary match
            case Absent =>
                Stream.empty[HttpPart]
            case Present(b) =>
                val delimiter = s"--$b"
                splitLines(stream, delimiter).mapPure { section =>
                    parseMultipartSection(section)
                }(using Tag[Emit[Chunk[String]]], Tag[Emit[Chunk[HttpPart]]])
        end match
    end parseMultipartStream

    private def parseMultipartSection(section: String): HttpPart =
        val headerBodySep = section.indexOf("\r\n\r\n")
        if headerBodySep < 0 then
            // Fallback: treat entire section as body with empty name
            HttpPart("", Absent, Absent, Span.fromUnsafe(section.getBytes(StandardCharsets.UTF_8)))
        else
            val headerSection = section.substring(0, headerBodySep).trim
            val bodySection   = section.substring(headerBodySep + 4)
            val cleanBody =
                if bodySection.endsWith("\r\n") then bodySection.substring(0, bodySection.length - 2)
                else bodySection

            val headerLines = headerSection.split("\r\n")

            @tailrec def parseHeaders(
                i: Int,
                name: String,
                filename: Maybe[String],
                partCt: Maybe[String]
            ): HttpPart =
                if i >= headerLines.length then
                    HttpPart(name, filename, partCt, Span.fromUnsafe(cleanBody.getBytes(StandardCharsets.UTF_8)))
                else
                    val line = headerLines(i)
                    if line.toLowerCase.startsWith("content-disposition:") then
                        val disp    = line.substring(20).trim
                        val nameIdx = disp.indexOf("name=\"")
                        val parsedName =
                            if nameIdx >= 0 then
                                val nameEnd = disp.indexOf('"', nameIdx + 6)
                                if nameEnd >= 0 then disp.substring(nameIdx + 6, nameEnd) else name
                            else name
                        val fnIdx = disp.indexOf("filename=\"")
                        val parsedFilename =
                            if fnIdx >= 0 then
                                val fnEnd = disp.indexOf('"', fnIdx + 10)
                                if fnEnd >= 0 then Present(disp.substring(fnIdx + 10, fnEnd)) else filename
                            else filename
                        parseHeaders(i + 1, parsedName, parsedFilename, partCt)
                    else if line.toLowerCase.startsWith("content-type:") then
                        parseHeaders(i + 1, name, filename, Present(line.substring(13).trim))
                    else
                        parseHeaders(i + 1, name, filename, partCt)
                    end if
            parseHeaders(0, "", Absent, Absent)
        end if
    end parseMultipartSection

    /** Parses a buffered multipart body into a sequence of HttpPart. */
    private def parseMultipartBody(body: String, headers: HttpHeaders)(using Frame): Result[HttpError, Seq[HttpPart]] =
        // Extract boundary from Content-Type header
        val boundaryOpt = headers.get("Content-Type").flatMap { ct =>
            val idx = ct.indexOf("boundary=")
            if idx >= 0 then Present(ct.substring(idx + 9).trim)
            else Absent
        }
        boundaryOpt match
            case Absent =>
                Result.fail(HttpError.ParseError("Missing boundary in Content-Type header for multipart body"))
            case Present(boundary) =>
                val delimiter = s"--$boundary"
                val parts     = ChunkBuilder.init[HttpPart]
                val sections  = body.split(java.util.regex.Pattern.quote(delimiter))

                @tailrec def loop(i: Int): Unit =
                    if i < sections.length then
                        val section = sections(i)
                        if !section.startsWith("--") then // skip closing boundary
                            val part = parseMultipartSection(section)
                            if part.name.nonEmpty then
                                discard(parts += part)
                        end if
                        loop(i + 1)
                loop(1) // skip preamble (before first boundary)

                Result.succeed(parts.result())
        end match
    end parseMultipartBody

    // ==================== Internal: multipart encoding ====================

    private def encodeMultipartParts(parts: Seq[HttpPart], boundary: String): Span[Byte] =
        val sb = new StringBuilder
        parts.foreach { part =>
            appendMultipartPart(sb, part, boundary)
        }
        discard(sb.append("--").append(boundary).append("--\r\n"))
        stringToSpan(sb.toString)
    end encodeMultipartParts

    private def encodeMultipartPart(part: HttpPart, boundary: String): Span[Byte] =
        val sb = new StringBuilder
        appendMultipartPart(sb, part, boundary)
        stringToSpan(sb.toString)
    end encodeMultipartPart

    private def appendMultipartPart(sb: StringBuilder, part: HttpPart, boundary: String): Unit =
        discard(sb.append("--").append(boundary).append("\r\n"))
        discard(sb.append("Content-Disposition: form-data; name=\"").append(part.name).append('"'))
        part.filename match
            case Present(fn) => discard(sb.append("; filename=\"").append(fn).append('"'))
            case Absent      =>
        discard(sb.append("\r\n"))
        part.contentType match
            case Present(ct) => discard(sb.append("Content-Type: ").append(ct).append("\r\n"))
            case Absent      =>
        discard(sb.append("\r\n"))
        discard(sb.append(spanToString(part.data)))
        discard(sb.append("\r\n"))
    end appendMultipartPart

    // ==================== Internal: helpers ====================

    private def isStreamingContentType(ct: ContentType[?]): Boolean =
        ct match
            case _: ContentType.ByteStream | _: ContentType.Ndjson[?] |
                _: ContentType.Sse[?] | _: ContentType.SseText | _: ContentType.MultipartStream => true
            case _ => false

    private def findBodyField(fields: Chunk[Field[?]]): Maybe[Field.Body[?, ?]] =
        @tailrec def loop(i: Int): Maybe[Field.Body[?, ?]] =
            if i >= fields.size then Absent
            else
                fields(i) match
                    case body: Field.Body[?, ?] => Present(body)
                    case _                      => loop(i + 1)
        loop(0)
    end findBodyField

    private def buildRequest[In, Out, S](
        route: HttpRoute[In, Out, S],
        headers: HttpHeaders,
        builder: DictBuilder[String, Any],
        path: String = ""
    ): HttpRequest[In] =
        val url =
            if path.isEmpty then HttpUrl(Absent, "", 0, "", Absent)
            else HttpUrl(Absent, "", 0, path, Absent)
        HttpRequest(route.method, url, headers, Record2(builder.result()))
    end buildRequest

end RouteUtil
