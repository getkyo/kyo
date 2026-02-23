package kyo.http2.internal

import java.nio.charset.StandardCharsets
import kyo.Absent
import kyo.Async
import kyo.Chunk
import kyo.ChunkBuilder
import kyo.Dict
import kyo.DictBuilder
import kyo.Emit
import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Present
import kyo.Record2
import kyo.Result
import kyo.Scope
import kyo.Span
import kyo.Stream
import kyo.Tag
import kyo.discard
import kyo.http2.HttpCodec
import kyo.http2.HttpCookie
import kyo.http2.HttpError
import kyo.http2.HttpFormCodec
import kyo.http2.HttpHeaders
import kyo.http2.HttpMethod
import kyo.http2.HttpPart
import kyo.http2.HttpPath
import kyo.http2.HttpRequest
import kyo.http2.HttpResponse
import kyo.http2.HttpRoute
import kyo.http2.HttpRoute.ContentType
import kyo.http2.HttpRoute.Field
import kyo.http2.HttpRoute.RequestDef
import kyo.http2.HttpRoute.ResponseDef
import kyo.http2.HttpStatus
import kyo.http2.HttpUrl
import kyo.http2.Schema
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

    def encodeRequest[In, Out, S, A](
        route: HttpRoute[In, Out, S],
        request: HttpRequest[In]
    )(
        onEmpty: ( /* url */ String, HttpHeaders) => A,
        onBuffered: ( /* url */ String, HttpHeaders, /* contentType */ String, Span[Byte]) => A,
        onStreaming: ( /* url */ String, HttpHeaders, /* contentType */ String, Stream[Span[Byte], Async & Scope]) => A
    )(using Frame): A =
        val fields    = route.request.fields
        val dict      = request.fields.dict
        val bodyField = findBodyField(fields)
        val hasParams = fields.size > (if bodyField.isDefined then 1 else 0)

        // Only allocate builders when params exist
        val headers =
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
                val path         = buildPath(route.request.path, dict)
                val url          = if queryBuilder.isEmpty then path else s"$path?$queryBuilder"
                val hdrs = if extraHeaders.isEmpty then request.headers
                else request.headers.concat(extraHeaders)
                encodeBody(bodyField, dict, url, hdrs)(onEmpty, onBuffered, onStreaming)
            else
                val url = buildPath(route.request.path, dict)
                encodeBody(bodyField, dict, url, request.headers)(onEmpty, onBuffered, onStreaming)
        headers
    end encodeRequest

    private def encodeBody[A](
        bodyField: Maybe[Field.Body[?, ?]],
        dict: Dict[String, Any],
        url: String,
        headers: HttpHeaders
    )(
        onEmpty: (String, HttpHeaders) => A,
        onBuffered: (String, HttpHeaders, String, Span[Byte]) => A,
        onStreaming: (String, HttpHeaders, String, Stream[Span[Byte], Async & Scope]) => A
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
                    decodeBufferedBodyValue(bf.contentType, body) match
                        case f: Result.Failure[?] => f.asInstanceOf[Result[HttpError, HttpResponse[Out]]]
                        case Result.Success(value) =>
                            val builder = DictBuilder.init[String, Any]
                            discard(builder.add(bf.fieldName, value))
                            Result.succeed(HttpResponse(status, headers, makeRecord(builder)))
                        case p: Result.Panic => p.asInstanceOf[Result[HttpError, HttpResponse[Out]]]
        else
            val builder = DictBuilder.init[String, Any]
            decodeParamFields(fields, headers, Absent, builder) match
                case f: Result.Failure[?] => f.asInstanceOf[Result[HttpError, HttpResponse[Out]]]
                case _ =>
                    bodyField match
                        case Absent =>
                            Result.succeed(HttpResponse(status, headers, makeRecord(builder)))
                        case Present(bf) =>
                            decodeBufferedBodyValue(bf.contentType, body) match
                                case f: Result.Failure[?] => f.asInstanceOf[Result[HttpError, HttpResponse[Out]]]
                                case Result.Success(value) =>
                                    discard(builder.add(bf.fieldName, value))
                                    Result.succeed(HttpResponse(status, headers, makeRecord(builder)))
                                case p: Result.Panic => p.asInstanceOf[Result[HttpError, HttpResponse[Out]]]
            end match
        end if
    end decodeBufferedResponse

    def decodeStreamingResponse[In, Out, S](
        route: HttpRoute[In, Out, S],
        status: HttpStatus,
        headers: HttpHeaders,
        stream: Stream[Span[Byte], Async & Scope]
    )(using Frame): Result[HttpError, HttpResponse[Out]] =
        val fields    = route.response.fields
        val bodyField = findBodyField(fields)
        val hasParams = fields.size > (if bodyField.isDefined then 1 else 0)

        val builder = DictBuilder.init[String, Any]

        if !hasParams then
            bodyField match
                case Present(bf) =>
                    discard(builder.add(bf.fieldName, decodeStreamBodyValue(bf.contentType, stream)))
                case Absent =>
            end match
            Result.succeed(HttpResponse(status, headers, makeRecord(builder)))
        else
            decodeParamFields(fields, headers, Absent, builder) match
                case f: Result.Failure[?] => f.asInstanceOf[Result[HttpError, HttpResponse[Out]]]
                case _ =>
                    bodyField match
                        case Present(bf) =>
                            discard(builder.add(bf.fieldName, decodeStreamBodyValue(bf.contentType, stream)))
                        case Absent =>
                    end match
                    Result.succeed(HttpResponse(status, headers, makeRecord(builder)))
            end match
        end if
    end decodeStreamingResponse

    // ==================== Server: decode request ====================

    def decodeBufferedRequest[In, Out, S](
        route: HttpRoute[In, Out, S],
        pathCaptures: Map[String, String],
        queryParam: String => Maybe[String],
        headers: HttpHeaders,
        body: Span[Byte]
    )(using Frame): Result[HttpError, HttpRequest[In]] =
        val fields    = route.request.fields
        val bodyField = findBodyField(fields)
        val hasParams = fields.size > (if bodyField.isDefined then 1 else 0)
        val builder   = DictBuilder.init[String, Any]

        decodeCaptures(route.request.path, pathCaptures, builder) match
            case f: Result.Failure[?] => f.asInstanceOf[Result[HttpError, HttpRequest[In]]]
            case _ =>
                val paramsResult =
                    if hasParams then decodeParamFields(fields, headers, Present(queryParam), builder)
                    else Result.unit
                paramsResult match
                    case f: Result.Failure[?] => f.asInstanceOf[Result[HttpError, HttpRequest[In]]]
                    case _ =>
                        bodyField match
                            case Absent =>
                                Result.succeed(buildRequest(route, headers, builder))
                            case Present(bf) =>
                                decodeBufferedBodyValue(bf.contentType, body) match
                                    case f: Result.Failure[?] => f.asInstanceOf[Result[HttpError, HttpRequest[In]]]
                                    case Result.Success(value) =>
                                        discard(builder.add(bf.fieldName, value))
                                        Result.succeed(buildRequest(route, headers, builder))
                                    case p: Result.Panic => p.asInstanceOf[Result[HttpError, HttpRequest[In]]]
                end match
        end match
    end decodeBufferedRequest

    def decodeStreamingRequest[In, Out, S](
        route: HttpRoute[In, Out, S],
        pathCaptures: Map[String, String],
        queryParam: String => Maybe[String],
        headers: HttpHeaders,
        stream: Stream[Span[Byte], Async & Scope]
    )(using Frame): Result[HttpError, HttpRequest[In]] =
        val fields    = route.request.fields
        val bodyField = findBodyField(fields)
        val hasParams = fields.size > (if bodyField.isDefined then 1 else 0)
        val builder   = DictBuilder.init[String, Any]

        decodeCaptures(route.request.path, pathCaptures, builder) match
            case f: Result.Failure[?] => f.asInstanceOf[Result[HttpError, HttpRequest[In]]]
            case _ =>
                val paramsResult =
                    if hasParams then decodeParamFields(fields, headers, Present(queryParam), builder)
                    else Result.unit
                paramsResult match
                    case f: Result.Failure[?] => f.asInstanceOf[Result[HttpError, HttpRequest[In]]]
                    case _ =>
                        bodyField match
                            case Absent =>
                                Result.succeed(buildRequest(route, headers, builder))
                            case Present(bf) =>
                                discard(builder.add(bf.fieldName, decodeStreamBodyValue(bf.contentType, stream)))
                                Result.succeed(buildRequest(route, headers, builder))
                end match
        end match
    end decodeStreamingRequest

    // ==================== Server: encode response ====================

    def encodeResponse[In, Out, S, A](
        route: HttpRoute[In, Out, S],
        response: HttpResponse[Out]
    )(
        onEmpty: (HttpStatus, HttpHeaders) => A,
        onBuffered: (HttpStatus, HttpHeaders, /* contentType */ String, Span[Byte]) => A,
        onStreaming: (HttpStatus, HttpHeaders, /* contentType */ String, Stream[Span[Byte], Async & Scope]) => A
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
        onStreaming: (HttpStatus, HttpHeaders, String, Stream[Span[Byte], Async & Scope]) => A
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
        else new String(bytes.toArrayUnsafe.asInstanceOf[Array[Byte]], utf8)

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
                discard(sb.append(java.net.URLEncoder.encode(encoded, utf8)))
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
        captures: Map[String, String],
        builder: DictBuilder[String, Any]
    )(using Frame): Result[HttpError, Unit] =
        path match
            case _: HttpPath.Literal => Result.unit
            case c: HttpPath.Capture[?, ?] =>
                val wireName = if c.wireName.isEmpty then c.fieldName else c.wireName
                captures.get(wireName) match
                    case Some(raw) =>
                        try
                            discard(builder.add(c.fieldName, c.codec.asInstanceOf[HttpCodec[Any]].decode(raw)))
                            Result.unit
                        catch
                            case e: Throwable =>
                                Result.fail(HttpError.ParseError(s"Failed to decode path capture '$wireName': ${e.getMessage}"))
                    case None =>
                        Result.fail(HttpError.ParseError(s"Missing path capture: $wireName"))
                end match
            case r: HttpPath.Rest[?] =>
                captures.get(r.fieldName) match
                    case Some(raw) =>
                        discard(builder.add(r.fieldName, raw))
                        Result.unit
                    case None =>
                        discard(builder.add(r.fieldName, ""))
                        Result.unit
                end match
            case c: HttpPath.Concat[?, ?] =>
                decodeCaptures(c.left, captures, builder) match
                    case f: Result.Failure[?] => f.asInstanceOf[Result[HttpError, Unit]]
                    case _                    => decodeCaptures(c.right, captures, builder)
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
                                                    .append(java.net.URLEncoder.encode(wireName, utf8))
                                                    .append('=')
                                                    .append(java.net.URLEncoder.encode(encoded, utf8)))
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
        queryParam: Maybe[String => Maybe[String]]
    )(using Frame): Result[HttpError, Any] =
        val wireName = if param.wireName.isEmpty then param.fieldName else param.wireName
        val raw: Maybe[String] = param.kind match
            case Field.Param.Location.Query =>
                queryParam match
                    case Present(qp) => qp(wireName)
                    case Absent      => Absent
            case Field.Param.Location.Header =>
                headers.get(wireName)
            case Field.Param.Location.Cookie =>
                headers.cookie(wireName)

        raw match
            case Present(str) =>
                try
                    val decoded = param.codec.asInstanceOf[HttpCodec[Any]].decode(str)
                    if param.optional then Result.succeed(Present(decoded))
                    else Result.succeed(decoded)
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
        queryParam: Maybe[String => Maybe[String]],
        builder: DictBuilder[String, Any]
    )(using Frame): Result[HttpError, Unit] =
        @tailrec def loop(i: Int): Result[HttpError, Unit] =
            if i >= fields.size then Result.unit
            else
                fields(i) match
                    case param: Field.Param[?, ?, ?] =>
                        decodeParam(param, headers, queryParam) match
                            case f: Result.Failure[?] => f.asInstanceOf[Result[HttpError, Unit]]
                            case Result.Success(value) =>
                                discard(builder.add(param.fieldName, value))
                                loop(i + 1)
                            case p: Result.Panic => p.asInstanceOf[Result[HttpError, Unit]]
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
        f: (String, Stream[Span[Byte], Async & Scope]) => A
    )(using Frame): A =
        ct match
            case _: ContentType.ByteStream =>
                f("application/octet-stream", value.asInstanceOf[Stream[Span[Byte], Async & Scope]])
            case ndjson: ContentType.Ndjson[?] =>
                val stream = value.asInstanceOf[Stream[Any, Async]]
                val schema = ndjson.schema.asInstanceOf[Schema[Any]]
                val byteStream = stream.mapPure { v =>
                    stringToSpan(schema.encode(v) + "\n")
                }(using ndjson.emitTag.asInstanceOf[Tag[Emit[Chunk[Any]]]], Tag[Emit[Chunk[Span[Byte]]]])
                f("application/x-ndjson", byteStream.asInstanceOf[Stream[Span[Byte], Async & Scope]])
            case _: ContentType.MultipartStream =>
                val stream   = value.asInstanceOf[Stream[HttpPart, Async]]
                val boundary = java.util.UUID.randomUUID().toString
                val byteStream = stream.mapPure { part =>
                    encodeMultipartPart(part, boundary)
                }(using Tag[Emit[Chunk[HttpPart]]], Tag[Emit[Chunk[Span[Byte]]]])
                // TODO: append closing boundary as final chunk
                f(s"multipart/form-data; boundary=$boundary", byteStream.asInstanceOf[Stream[Span[Byte], Async & Scope]])
            case _ =>
                throw new IllegalStateException(s"Cannot encode non-streaming ContentType as stream: $ct")
    end encodeStreamBodyValue

    // ==================== Internal: body decoding ====================

    private def decodeBufferedBodyValue(
        ct: ContentType[?],
        bytes: Span[Byte]
    )(using Frame): Result[HttpError, Any] =
        ct match
            case _: ContentType.Text =>
                Result.succeed(spanToString(bytes))
            case _: ContentType.Binary =>
                Result.succeed(bytes)
            case json: ContentType.Json[?] =>
                json.schema.decode(spanToString(bytes)) match
                    case Result.Success(v) => Result.succeed(v)
                    case Result.Failure(msg) =>
                        Result.fail(HttpError.ParseError(s"JSON decode failed: $msg"))
                    case p: Result.Panic => p.asInstanceOf[Result[HttpError, Any]]
            case form: ContentType.Form[?] =>
                try Result.succeed(form.codec.asInstanceOf[HttpFormCodec[Any]].decode(spanToString(bytes)))
                catch
                    case e: Throwable =>
                        Result.fail(HttpError.ParseError(s"Form decode failed: ${e.getMessage}"))
            case _: ContentType.Multipart =>
                // TODO: implement multipart boundary parsing
                Result.fail(HttpError.ParseError("Multipart decoding not yet implemented"))
            case _ =>
                Result.fail(HttpError.ParseError(s"Cannot decode streaming ContentType as buffered: $ct"))
    end decodeBufferedBodyValue

    private def decodeStreamBodyValue(
        ct: ContentType[?],
        stream: Stream[Span[Byte], Async & Scope]
    )(using Frame): Any =
        ct match
            case _: ContentType.ByteStream =>
                stream
            case ndjson: ContentType.Ndjson[?] =>
                val schema = ndjson.schema.asInstanceOf[Schema[Any]]
                // TODO: proper line splitting — for now assumes each chunk is one line
                stream.mapPure { chunk =>
                    val line = spanToString(chunk).trim
                    schema.decode(line) match
                        case Result.Success(v)   => v
                        case Result.Failure(msg) => throw new RuntimeException(s"Ndjson decode failed: $msg")
                        case p: Result.Panic     => throw p.exception
                    end match
                }(using Tag[Emit[Chunk[Span[Byte]]]], ndjson.emitTag.asInstanceOf[Tag[Emit[Chunk[Any]]]])
            case _: ContentType.Sse[?] =>
                // TODO: implement SSE frame parsing
                stream
            case _: ContentType.MultipartStream =>
                // TODO: implement streaming multipart parsing
                stream
            case _ =>
                throw new IllegalStateException(s"Cannot decode non-streaming ContentType as stream: $ct")
    end decodeStreamBodyValue

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
                _: ContentType.Sse[?] | _: ContentType.MultipartStream => true
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

    private def makeRecord[F](builder: DictBuilder[String, Any]): Record2[F] =
        new Record2[F](builder.result())

    private def buildRequest[In, Out, S](
        route: HttpRoute[In, Out, S],
        headers: HttpHeaders,
        builder: DictBuilder[String, Any]
    ): HttpRequest[In] =
        HttpRequest(route.method, HttpUrl(Absent, "", 0, "", Absent), headers, makeRecord(builder))

end RouteUtil
