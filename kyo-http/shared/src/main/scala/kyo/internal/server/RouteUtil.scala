package kyo.internal.server

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.kernel.ArrowEffect
import scala.annotation.tailrec

/** Shared codec toolkit for translating between typed route definitions (Record-based) and wire-level HTTP primitives. Used internally by
  * backend implementations to avoid duplicating marshalling logic across platforms (Netty, curl, fetch).
  *
  * Encode methods use continuations to avoid intermediate tuple allocations. Decode methods return typed HttpRequest/HttpResponse directly
  * — all unsafe Dict[String, Any] access is encapsulated here.
  */
private[kyo] object RouteUtil:

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
        inline onBuffered: ( /* url */ String, HttpHeaders, Span[Byte]) => A,
        inline onStreaming: ( /* url */ String, HttpHeaders, Stream[Span[Byte], Async]) => A
    )(using Frame): A =
        val fields    = route.request.fields
        val dict      = request.fields.dict
        val bodyField = findBodyField(fields)
        val hasParams = fields.size > (if bodyField.isDefined then 1 else 0)

        // Use request.url.path when explicitly set (e.g., redirects), otherwise build from route captures
        val basePath =
            if request.url.path.nonEmpty then request.url.path
            else buildPath(route.request.path, dict)

        // RFC 9110 §15.4.4: After 303 redirect, method changes to GET but the route still
        // has a body field. Skip body encoding for GET/HEAD to avoid sending a body with these methods.
        val effectiveBodyField =
            bodyField.filter(_ => request.method != HttpMethod.GET && request.method != HttpMethod.HEAD)

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
            val url = request.url.rawQuery match
                case Present(rq) =>
                    if queryBuilder.nonEmpty then s"$basePath?$rq&$queryBuilder"
                    else s"$basePath?$rq"
                case _ =>
                    if queryBuilder.nonEmpty then s"$basePath?$queryBuilder"
                    else basePath
            val hdrs = if extraHeaders.isEmpty then request.headers
            else request.headers.concat(extraHeaders)
            encodeBody(effectiveBodyField, dict, url, hdrs)(onEmpty, onBuffered, onStreaming)
        else
            val url = request.url.rawQuery match
                case Present(rq) => s"$basePath?$rq"
                case _           => basePath
            encodeBody(effectiveBodyField, dict, url, request.headers)(onEmpty, onBuffered, onStreaming)
        end if
    end encodeRequest

    private inline def encodeBody[A](
        bodyField: Maybe[HttpRoute.Field.Body[?, ?]],
        dict: Dict[String, Any],
        url: String,
        headers: HttpHeaders
    )(
        inline onEmpty: (String, HttpHeaders) => A,
        inline onBuffered: (String, HttpHeaders, Span[Byte]) => A,
        inline onStreaming: (String, HttpHeaders, Stream[Span[Byte], Async]) => A
    )(using Frame): A =
        bodyField match
            case Absent => onEmpty(url, headers)
            case Present(body) =>
                val value = dict(body.fieldName)
                val hasCt = headers.contains("Content-Type")
                if isStreamingContentType(body.contentType) then
                    encodeStreamBodyValueWith(body.contentType, value) { (ct, stream) =>
                        val hdrs = if hasCt then headers else headers.add("Content-Type", ct)
                        onStreaming(url, hdrs, stream)
                    }
                else
                    encodeBufferedBodyValueWith(body.contentType, value) { (ct, bytes) =>
                        val hdrs = if hasCt then headers else headers.add("Content-Type", ct)
                        onBuffered(url, hdrs, bytes)
                    }
                end if

    // ==================== Client: decode response ====================

    def decodeBufferedResponse[In, Out, S](
        route: HttpRoute[In, Out, S],
        status: HttpStatus,
        headers: HttpHeaders,
        body: Span[Byte],
        method: String,
        url: HttpUrl
    )(using Frame): Result[HttpException, HttpResponse[Out]] =
        val fields    = route.response.fields
        val bodyField = findBodyField(fields)
        val hasParams = fields.size > (if bodyField.isDefined then 1 else 0)

        def decodeBody(): Result[HttpException, HttpResponse[Out]] =
            // Fast path: no params to decode
            if !hasParams then
                bodyField match
                    case Absent =>
                        Result.succeed(HttpResponse(status, headers, Record.empty.asInstanceOf[Record[Out]]))
                    case Present(bf) =>
                        decodeBufferedBodyValue(bf.contentType, body, headers, method, url).map { value =>
                            HttpResponse(status, headers, Record(Dict.empty[String, Any].update(bf.fieldName, value)))
                        }
            else
                val builder = DictBuilder.init[String, Any]
                decodeParamFields(fields, headers, Absent, builder, method, url, isResponse = true).flatMap { _ =>
                    bodyField match
                        case Absent =>
                            Result.succeed(HttpResponse(status, headers, Record(builder.result())))
                        case Present(bf) =>
                            decodeBufferedBodyValue(bf.contentType, body, headers, method, url).map { value =>
                                discard(builder.add(bf.fieldName, value))
                                HttpResponse(status, headers, Record(builder.result()))
                            }
                }
            end if
        end decodeBody

        val result = decodeBody()
        // When the body decoding fails on a non-2xx response, return an HttpStatusException
        // with the original status code instead of a confusing decode error. Capture the raw
        // body as text so callers can classify errors (e.g. "container name in use") even
        // though the body didn't fit the route's success type.
        result match
            case Result.Error(_: HttpDecodeException) if !status.isSuccess =>
                if body.isEmpty then Result.fail(HttpStatusException(status, method, url.toString))
                else Result.fail(HttpStatusException(status, method, url.toString, spanToString(body)))
            case other => other
        end match
    end decodeBufferedResponse

    def decodeBufferedResponseWith[In, Out, S, A, S2](
        route: HttpRoute[In, Out, S],
        status: HttpStatus,
        headers: HttpHeaders,
        body: Span[Byte],
        method: String,
        url: HttpUrl
    )(f: HttpResponse[Out] => A < S2)(using Frame): A < (S2 & Abort[HttpException]) =
        decodeBufferedResponse(route, status, headers, body, method, url) match
            case Result.Success(r) => f(r)
            case Result.Failure(e) => Abort.fail(e)
            case Result.Panic(e)   => throw e
    end decodeBufferedResponseWith

    def decodeStreamingResponse[In, Out, S](
        route: HttpRoute[In, Out, S],
        status: HttpStatus,
        headers: HttpHeaders,
        stream: Stream[Span[Byte], Async],
        method: String,
        url: HttpUrl
    )(using Frame): Result[HttpException, HttpResponse[Out]] =
        val fields    = route.response.fields
        val bodyField = findBodyField(fields)
        val hasParams = fields.size > (if bodyField.isDefined then 1 else 0)

        val builder = DictBuilder.init[String, Any]

        if !hasParams then
            bodyField.foreach(bf =>
                discard(builder.add(bf.fieldName, decodeStreamBodyValue(bf.contentType, stream, headers, method, url)))
            )
            Result.succeed(HttpResponse(status, headers, Record(builder.result())))
        else
            decodeParamFields(fields, headers, Absent, builder, method, url, isResponse = true).map { _ =>
                bodyField.foreach(bf =>
                    discard(builder.add(bf.fieldName, decodeStreamBodyValue(bf.contentType, stream, headers, method, url)))
                )
                HttpResponse(status, headers, Record(builder.result()))
            }
        end if
    end decodeStreamingResponse

    def decodeStreamingResponseWith[In, Out, S, A, S2](
        route: HttpRoute[In, Out, S],
        status: HttpStatus,
        headers: HttpHeaders,
        stream: Stream[Span[Byte], Async],
        method: String,
        url: HttpUrl
    )(f: HttpResponse[Out] => A < S2)(using Frame): A < (S2 & Abort[HttpException]) =
        Abort.get(decodeStreamingResponse(route, status, headers, stream, method, url)).flatMap(f)
    end decodeStreamingResponseWith

    // ==================== Server: decode request ====================

    def decodeBufferedRequest[In, Out, S](
        route: HttpRoute[In, Out, S],
        pathCaptures: Dict[String, String],
        queryParam: Maybe[HttpUrl],
        headers: HttpHeaders,
        body: Span[Byte],
        path: String = "",
        methodOverride: Maybe[HttpMethod] = Absent
    )(using Frame): Result[HttpException, HttpRequest[In]] =
        val ctxMethod = route.method.name
        val ctxUrl    = HttpUrl.fromUri(route.request.path.show)
        val fields    = route.request.fields
        val bodyField = findBodyField(fields)
        val hasParams = fields.size > (if bodyField.isDefined then 1 else 0)
        val builder   = DictBuilder.init[String, Any]

        decodeCaptures(route.request.path, pathCaptures, builder, ctxMethod, ctxUrl).flatMap { _ =>
            val paramsResult =
                if hasParams then decodeParamFields(fields, headers, queryParam, builder, ctxMethod, ctxUrl)
                else Result.unit
            paramsResult.flatMap { _ =>
                bodyField match
                    case Absent =>
                        Result.succeed(buildRequest(route, headers, builder, path, methodOverride))
                    case Present(bf) =>
                        decodeBufferedBodyValue(bf.contentType, body, headers, ctxMethod, ctxUrl).map { value =>
                            discard(builder.add(bf.fieldName, value))
                            buildRequest(route, headers, builder, path, methodOverride)
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
        path: String = "",
        methodOverride: Maybe[HttpMethod] = Absent
    )(using Frame): Result[HttpException, HttpRequest[In]] =
        val ctxMethod = route.method.name
        val ctxUrl    = HttpUrl.fromUri(route.request.path.show)
        val fields    = route.request.fields
        val bodyField = findBodyField(fields)
        val hasParams = fields.size > (if bodyField.isDefined then 1 else 0)
        val builder   = DictBuilder.init[String, Any]

        decodeCaptures(route.request.path, pathCaptures, builder, ctxMethod, ctxUrl).flatMap { _ =>
            val paramsResult =
                if hasParams then decodeParamFields(fields, headers, queryParam, builder, ctxMethod, ctxUrl)
                else Result.unit
            paramsResult.map { _ =>
                bodyField.foreach(bf =>
                    discard(builder.add(bf.fieldName, decodeStreamBodyValue(bf.contentType, stream, headers, ctxMethod, ctxUrl)))
                )
                buildRequest(route, headers, builder, path, methodOverride)
            }
        }
    end decodeStreamingRequest

    // ==================== Server: encode response ====================

    def encodeResponse[In, Out, S, A](
        route: HttpRoute[In, Out, S],
        response: HttpResponse[Out]
    )(
        onEmpty: (HttpStatus, HttpHeaders) => A,
        onBuffered: (HttpStatus, HttpHeaders, Span[Byte]) => A,
        onStreaming: (HttpStatus, HttpHeaders, Stream[Span[Byte], Async]) => A
    )(using Frame): A =
        val fields      = route.response.fields
        val routeStatus = route.response.status
        val status      = if routeStatus != HttpStatus.OK then routeStatus else response.status
        val bodyField   = findBodyField(fields)
        val hasParams   = fields.size > (if bodyField.isDefined then 1 else 0)

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
        bodyField: Maybe[HttpRoute.Field.Body[?, ?]],
        dict: Dict[String, Any],
        status: HttpStatus,
        headers: HttpHeaders
    )(
        onEmpty: (HttpStatus, HttpHeaders) => A,
        onBuffered: (HttpStatus, HttpHeaders, Span[Byte]) => A,
        onStreaming: (HttpStatus, HttpHeaders, Stream[Span[Byte], Async]) => A
    )(using Frame): A =
        bodyField match
            case Absent => onEmpty(status, headers)
            case Present(body) =>
                val value = dict(body.fieldName)
                val hasCt = headers.contains("Content-Type")
                if isStreamingContentType(body.contentType) then
                    encodeStreamBodyValueWith(body.contentType, value) { (ct, stream) =>
                        val hdrs = if hasCt then headers else headers.add("Content-Type", ct)
                        onStreaming(status, hdrs, stream)
                    }
                else
                    encodeBufferedBodyValueWith(body.contentType, value) { (ct, bytes) =>
                        val hdrs = if hasCt then headers else headers.add("Content-Type", ct)
                        onBuffered(status, hdrs, bytes)
                    }
                end if

    // ==================== Server: encode error ====================

    /** Try to match an error value against the route's declared error mappings. Returns the mapped status, Content-Type header, and
      * serialized body if matched.
      */
    def encodeError[In, Out, S](
        route: HttpRoute[In, Out, S],
        error: Any
    )(using Frame): Maybe[(HttpStatus, HttpHeaders, Span[Byte])] =
        val errors = route.response.errors
        if errors.isEmpty then Absent
        else
            @tailrec def loop(i: Int): Maybe[(HttpStatus, HttpHeaders, Span[Byte])] =
                if i >= errors.size then Absent
                else
                    val mapping = errors(i)
                    if mapping.tag.accepts(error) then
                        val jsonStr = Json.encode(error)(using mapping.schema.asInstanceOf[Schema[Any]])
                        val body    = stringToSpan(jsonStr)
                        val headers = HttpHeaders.empty.add("Content-Type", "application/json")
                        Present((mapping.status, headers, body))
                    else loop(i + 1)
                    end if
            loop(0)
        end if
    end encodeError

    // ==================== Internal: Span[Byte] <-> String ====================

    private def spanToString(bytes: Span[Byte]): String =
        if bytes.isEmpty then ""
        else new String(bytes.toArrayUnsafe, utf8)

    private def stringToSpan(s: String): Span[Byte] =
        if s.isEmpty then Span.empty
        else Span.fromUnsafe(s.getBytes(utf8))

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
        builder: DictBuilder[String, Any],
        method: String,
        url: HttpUrl
    )(using Frame): Result[HttpException, Unit] =
        path match
            case _: HttpPath.Literal => Result.unit
            case c: HttpPath.Capture[?, ?] =>
                val wireName = if c.wireName.isEmpty then c.fieldName else c.wireName
                captures.get(wireName) match
                    case Present(raw) =>
                        c.codec.decode(raw)
                            .map(decoded => discard(builder.add(c.fieldName, decoded)))
                            .mapFailure(e => HttpFieldDecodeException(wireName, "path", method, url.toString, e))
                    case Absent =>
                        Result.fail(HttpMissingFieldException(wireName, "path", method, url.toString))
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
                decodeCaptures(c.left, captures, builder, method, url).flatMap(_ => decodeCaptures(c.right, captures, builder, method, url))
    end decodeCaptures

    // ==================== Internal: param encoding ====================

    private def encodeRequestParams(
        fields: Chunk[HttpRoute.Field[?]],
        dict: Dict[String, Any],
        queryBuilder: StringBuilder,
        headerBuilder: ChunkBuilder[String]
    ): Maybe[StringBuilder] =
        @tailrec def loop(i: Int, cookieBuilder: Maybe[StringBuilder]): Maybe[StringBuilder] =
            if i >= fields.size then cookieBuilder
            else
                val nextCookie = fields(i) match
                    case param: HttpRoute.Field.Param[?, ?, ?] =>
                        val wireName = if param.wireName.isEmpty then param.fieldName else param.wireName
                        dict.get(param.fieldName).flatMap(unwrapOptional(param.optional, _)).map { v =>
                            val encoded = param.codec.asInstanceOf[HttpCodec[Any]].encode(v)
                            param.kind match
                                case HttpRoute.Field.Param.Location.Query =>
                                    if queryBuilder.nonEmpty then discard(queryBuilder.append('&'))
                                    discard(queryBuilder
                                        .append(java.net.URLEncoder.encode(wireName, "UTF-8"))
                                        .append('=')
                                        .append(java.net.URLEncoder.encode(encoded, "UTF-8")))
                                    cookieBuilder
                                case HttpRoute.Field.Param.Location.Header =>
                                    discard(headerBuilder += wireName)
                                    discard(headerBuilder += encoded)
                                    cookieBuilder
                                case HttpRoute.Field.Param.Location.Cookie =>
                                    val cb = cookieBuilder.getOrElse(new StringBuilder)
                                    if cookieBuilder.nonEmpty then discard(cb.append("; "))
                                    discard(cb.append(wireName).append('=').append(encoded))
                                    Present(cb)
                            end match
                        }.getOrElse(cookieBuilder)
                    case _: HttpRoute.Field.Body[?, ?] => cookieBuilder
                loop(i + 1, nextCookie)
        loop(0, Absent)
    end encodeRequestParams

    private def encodeResponseParams(
        fields: Chunk[HttpRoute.Field[?]],
        dict: Dict[String, Any],
        headerBuilder: ChunkBuilder[String]
    ): Unit =
        @tailrec def loop(i: Int): Unit =
            if i < fields.size then
                fields(i) match
                    case param: HttpRoute.Field.Param[?, ?, ?] =>
                        val wireName = if param.wireName.isEmpty then param.fieldName else param.wireName
                        dict.get(param.fieldName).flatMap(unwrapOptional(param.optional, _)).foreach { v =>
                            param.kind match
                                case HttpRoute.Field.Param.Location.Header =>
                                    discard(headerBuilder += wireName)
                                    discard(headerBuilder += param.codec.asInstanceOf[HttpCodec[Any]].encode(v))
                                case HttpRoute.Field.Param.Location.Cookie =>
                                    v match
                                        case cookie: HttpCookie[?] =>
                                            discard(headerBuilder += "Set-Cookie")
                                            discard(headerBuilder += HttpHeaders.serializeCookie(wireName, cookie))
                                        case _ =>
                                case _ =>
                        }
                    case _: HttpRoute.Field.Body[?, ?] =>
                end match
                loop(i + 1)
        loop(0)
    end encodeResponseParams

    // ==================== Internal: param decoding ====================

    private def decodeParam(
        param: HttpRoute.Field.Param[?, ?, ?],
        headers: HttpHeaders,
        queryParam: Maybe[HttpUrl],
        method: String,
        url: HttpUrl,
        isResponse: Boolean = false
    )(using Frame): Result[HttpException, Any] =
        val wireName  = if param.wireName.isEmpty then param.fieldName else param.wireName
        val fieldType = param.kind.toString.toLowerCase
        val raw: Maybe[String] = param.kind match
            case HttpRoute.Field.Param.Location.Query =>
                queryParam match
                    case Present(u) => u.query(wireName)
                    case Absent     => Absent
            case HttpRoute.Field.Param.Location.Header =>
                headers.get(wireName)
            case HttpRoute.Field.Param.Location.Cookie =>
                if isResponse then headers.responseCookie(wireName)
                else headers.cookie(wireName)

        raw match
            case Present(str) =>
                param.codec.decode(str)
                    .map { decoded =>
                        val value =
                            if isResponse && param.kind == HttpRoute.Field.Param.Location.Cookie then
                                HttpCookie(decoded)(using param.codec)
                            else decoded
                        if param.optional then Present(value)
                        else value
                    }
                    .mapFailure(e => HttpFieldDecodeException(wireName, fieldType, method, url.toString, e))
            case Absent =>
                param.default match
                    case Present(d) =>
                        if param.optional then Result.succeed(Present(d))
                        else Result.succeed(d)
                    case Absent =>
                        if param.optional then Result.succeed(Absent)
                        else Result.fail(HttpMissingFieldException(wireName, fieldType, method, url.toString))
        end match
    end decodeParam

    private def decodeParamFields(
        fields: Chunk[HttpRoute.Field[?]],
        headers: HttpHeaders,
        queryParam: Maybe[HttpUrl],
        builder: DictBuilder[String, Any],
        method: String,
        url: HttpUrl,
        isResponse: Boolean = false
    )(using Frame): Result[HttpException, Unit] =
        def loop(i: Int): Result[HttpException, Unit] =
            if i >= fields.size then Result.unit
            else
                fields(i) match
                    case param: HttpRoute.Field.Param[?, ?, ?] =>
                        decodeParam(param, headers, queryParam, method, url, isResponse).flatMap { value =>
                            discard(builder.add(param.fieldName, value))
                            loop(i + 1)
                        }
                    case _: HttpRoute.Field.Body[?, ?] => loop(i + 1)
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

    private def encodeBufferedBodyValueWith[A](ct: HttpRoute.ContentType[?], value: Any)(f: (String, Span[Byte]) => A)(using Frame): A =
        ct match
            case HttpRoute.ContentType.Text =>
                f("text/plain; charset=utf-8", stringToSpan(value.asInstanceOf[String]))
            case HttpRoute.ContentType.Binary =>
                f("application/octet-stream", value.asInstanceOf[Span[Byte]])
            case json: HttpRoute.ContentType.Json[?] =>
                val str = Json.encode(value)(using json.schema.asInstanceOf[Schema[Any]])
                f("application/json", stringToSpan(str))
            case form: HttpRoute.ContentType.Form[?] =>
                val str = form.codec.asInstanceOf[HttpFormCodec[Any]].encode(value)
                f("application/x-www-form-urlencoded", stringToSpan(str))
            case HttpRoute.ContentType.Multipart =>
                val parts    = value.asInstanceOf[Seq[HttpRequest.Part]]
                val boundary = java.util.UUID.randomUUID().toString
                val bytes    = encodeMultipartParts(parts, boundary)
                f(s"multipart/form-data; boundary=$boundary", bytes)
            case _ =>
                throw new IllegalStateException(s"Cannot encode streaming ContentType as buffered: $ct")
    end encodeBufferedBodyValueWith

    private def encodeStreamBodyValueWith[A](ct: HttpRoute.ContentType[?], value: Any)(
        f: (String, Stream[Span[Byte], Async]) => A
    )(using Frame): A =
        ct match
            case HttpRoute.ContentType.ByteStream =>
                f("application/octet-stream", value.asInstanceOf[Stream[Span[Byte], Async]])
            case ndjson: HttpRoute.ContentType.Ndjson[?] =>
                val stream = value.asInstanceOf[Stream[Any, Async]]
                val schema = ndjson.schema.asInstanceOf[Schema[Any]]
                val byteStream = stream.mapPure { v =>
                    stringToSpan(Json.encode(v)(using schema) + "\n")
                }(using ndjson.emitTag.asInstanceOf[Tag[Emit[Chunk[Any]]]], Tag[Emit[Chunk[Span[Byte]]]])
                f("application/x-ndjson", byteStream)
            case sse: HttpRoute.ContentType.Sse[?] =>
                val stream = value.asInstanceOf[Stream[HttpSseEvent[Any], Async]]
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
                    discard(sb.append("data: ").append(Json.encode(event.data)(using schema)).append("\n\n"))
                    stringToSpan(sb.toString)
                }(using sse.emitTag.asInstanceOf[Tag[Emit[Chunk[HttpSseEvent[Any]]]]], Tag[Emit[Chunk[Span[Byte]]]])
                f("text/event-stream", byteStream)
            case sseText: HttpRoute.ContentType.SseText =>
                val stream = value.asInstanceOf[Stream[HttpSseEvent[String], Async]]
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
            case HttpRoute.ContentType.MultipartStream =>
                val stream   = value.asInstanceOf[Stream[HttpRequest.Part, Async]]
                val boundary = java.util.UUID.randomUUID().toString
                val byteStream = stream.mapPure { part =>
                    encodeMultipartPart(part, boundary)
                }(using Tag[Emit[Chunk[HttpRequest.Part]]], Tag[Emit[Chunk[Span[Byte]]]])
                val closingBoundary = Stream.init(Seq(stringToSpan(s"--$boundary--\r\n")))
                f(s"multipart/form-data; boundary=$boundary", byteStream.concat(closingBoundary))
            case _ =>
                throw new IllegalStateException(s"Cannot encode non-streaming ContentType as stream: $ct")
    end encodeStreamBodyValueWith

    // ==================== Internal: body decoding ====================

    private def decodeBufferedBodyValue(
        ct: HttpRoute.ContentType[?],
        bytes: Span[Byte],
        headers: HttpHeaders,
        method: String,
        url: HttpUrl
    )(using Frame): Result[HttpException, Any] =
        ct match
            case HttpRoute.ContentType.Text =>
                Result.succeed(spanToString(bytes))
            case HttpRoute.ContentType.Binary =>
                Result.succeed(bytes)
            case json: HttpRoute.ContentType.Json[?] =>
                if !checkContentType(headers, "application/json") then
                    Result.fail(HttpUnsupportedMediaTypeException("application/json", headers.get("Content-Type"), method, url.toString))
                else
                    val schema = json.schema.asInstanceOf[Schema[Any]]
                    // Unit-valued JSON handlers tolerate empty bodies and JSON null, matching
                    // the 204 No Content semantics the old kyo.Json[Unit] override provided.
                    if isUnitSchema(schema) then
                        val s = spanToString(bytes).trim
                        if s.isEmpty || s == "null" then Result.succeed(())
                        else
                            Json.decode[Any](spanToString(bytes))(using summon[Json], schema, summon[Frame])
                                .mapFailure(e => HttpJsonDecodeException(e.getMessage, method, url.toString))
                        end if
                    else
                        Json.decode[Any](spanToString(bytes))(using summon[Json], schema, summon[Frame])
                            .mapFailure(e => HttpJsonDecodeException(e.getMessage, method, url.toString))
                    end if
            case form: HttpRoute.ContentType.Form[?] =>
                if !checkContentType(headers, "application/x-www-form-urlencoded") then
                    Result.fail(HttpUnsupportedMediaTypeException(
                        "application/x-www-form-urlencoded",
                        headers.get("Content-Type"),
                        method,
                        url.toString
                    ))
                else
                    form.codec.decode(spanToString(bytes))
                        .mapFailure(e => HttpFormDecodeException(e.getMessage, method, url.toString, e))
            case HttpRoute.ContentType.Multipart =>
                parseMultipartBody(bytes, headers, method, url)
            case _ =>
                Result.fail(HttpStreamingDecodeException(ct.toString, method, url.toString))
    end decodeBufferedBodyValue

    private def decodeStreamBodyValue(
        ct: HttpRoute.ContentType[?],
        stream: Stream[Span[Byte], Async],
        headers: HttpHeaders,
        method: String,
        url: HttpUrl
    )(using Frame): Any =
        ct match
            case HttpRoute.ContentType.ByteStream =>
                stream
            case ndjson: HttpRoute.ContentType.Ndjson[?] =>
                val schema = ndjson.schema.asInstanceOf[Schema[Any]]
                splitLines(stream, "\n").mapPure { line =>
                    Json.decode[Any](line)(using summon[Json], schema, summon[Frame]) match
                        case Result.Success(v) => v
                        case Result.Failure(e) => throw HttpJsonDecodeException(e.getMessage, method, url.toString)
                        case p: Result.Panic   => throw p.exception
                    end match
                }(using Tag[Emit[Chunk[String]]], ndjson.emitTag.asInstanceOf[Tag[Emit[Chunk[Any]]]])
            case sse: HttpRoute.ContentType.Sse[?] =>
                val schema = sse.schema.asInstanceOf[Schema[Any]]
                splitLines(stream, "\n\n").mapPure { frame =>
                    parseSseFrame(schema, frame)
                }(using Tag[Emit[Chunk[String]]], sse.emitTag.asInstanceOf[Tag[Emit[Chunk[HttpSseEvent[Any]]]]])
            case sseText: HttpRoute.ContentType.SseText =>
                splitLines(stream, "\n\n").mapPure { frame =>
                    parseSseFields(frame)(identity)
                }(using Tag[Emit[Chunk[String]]], sseText.emitTag)
            case HttpRoute.ContentType.MultipartStream =>
                parseMultipartStream(stream, headers)
            case _ =>
                throw new IllegalStateException(s"Cannot decode non-streaming ContentType as stream: $ct")
    end decodeStreamBodyValue

    /** Splits a byte stream into string segments by delimiter, handling cross-chunk boundaries. */
    private def splitLines(
        stream: Stream[Span[Byte], Async],
        delimiter: String
    )(using Frame): Stream[String, Async] =
        // Accumulate bytes, split on delimiter, carry leftover as loop state to avoid mutation
        val byteTag                            = Tag[Emit[Chunk[Span[Byte]]]]
        given strTag: Tag[Emit[Chunk[String]]] = Tag[Emit[Chunk[String]]]
        Stream(
            ArrowEffect.handleLoop(byteTag, "", stream.emit)(
                [C] =>
                    (input, leftover, cont) =>
                        val sb = new StringBuilder(leftover)
                        input.foreach(span => discard(sb.append(spanToString(span))))
                        val combined = sb.toString
                        val parts    = combined.split(java.util.regex.Pattern.quote(delimiter), -1)
                        if parts.length <= 1 then
                            Loop.continue(combined, cont(()))
                        else
                            val result = ChunkBuilder.init[String]
                            @tailrec def loop(i: Int): Unit =
                                if i < parts.length - 1 then
                                    val part = parts(i).trim
                                    if part.nonEmpty then discard(result += part)
                                    loop(i + 1)
                            loop(0)
                            val out = result.result()
                            if out.isEmpty then Loop.continue(parts.last, cont(()))
                            else Emit.valueWith(out)(Loop.continue(parts.last, cont(())))
                        end if
            )
        )
    end splitLines

    /** Parses SSE frame fields shared by both JSON and text SSE decoders. */
    private inline def parseSseFields[A](frame: String)(inline decodeData: String => A): HttpSseEvent[A] =
        val lines       = frame.split('\n')
        val dataBuilder = new StringBuilder
        @tailrec def loop(
            i: Int,
            eventName: Maybe[String],
            id: Maybe[String],
            retry: Maybe[Duration],
            hasData: Boolean
        ): (Maybe[String], Maybe[String], Maybe[Duration]) =
            if i >= lines.length then (eventName, id, retry)
            else
                val line = lines(i)
                if line.startsWith("event:") then
                    loop(i + 1, Present(line.substring(6).trim), id, retry, hasData)
                else if line.startsWith("id:") then
                    loop(i + 1, eventName, Present(line.substring(3).trim), retry, hasData)
                else if line.startsWith("retry:") then
                    val ms = line.substring(6).trim.toLong
                    loop(i + 1, eventName, id, Present(Duration.fromNanos(ms * 1000000)), hasData)
                else if line.startsWith("data:") then
                    if hasData then discard(dataBuilder.append('\n'))
                    discard(dataBuilder.append(line.substring(5).trim))
                    loop(i + 1, eventName, id, retry, true)
                else
                    loop(i + 1, eventName, id, retry, hasData)
                end if
        val (eventName, id, retry) = loop(0, Absent, Absent, Absent, false)
        HttpSseEvent(decodeData(dataBuilder.toString), eventName, id, retry)
    end parseSseFields

    /** Parses a single SSE frame string into an HttpSseEvent. */
    private def parseSseFrame(schema: Schema[Any], frame: String)(using Frame): HttpSseEvent[Any] =
        parseSseFields(frame) { data =>
            Json.decode[Any](data)(using summon[Json], schema, summon[Frame]) match
                case Result.Success(v) => v
                case Result.Failure(e) => throw new RuntimeException(s"SSE data decode failed: ${e.getMessage}")
                case p: Result.Panic   => throw p.exception
        }

    /** Parses a multipart byte stream into a stream of HttpRequest.Part. */
    private def parseMultipartStream(
        stream: Stream[Span[Byte], Async],
        headers: HttpHeaders
    )(using Frame): Stream[HttpRequest.Part, Async] =
        val boundary = headers.get("Content-Type").flatMap { ct =>
            val idx = ct.indexOf("boundary=")
            if idx >= 0 then Present(ct.substring(idx + 9).trim)
            else Absent
        }
        boundary match
            case Absent =>
                Stream.empty[HttpRequest.Part]
            case Present(b) =>
                val delimiter = s"--$b"
                splitLines(stream, delimiter).mapPure { section =>
                    parseMultipartSection(section)
                }(using Tag[Emit[Chunk[String]]], Tag[Emit[Chunk[HttpRequest.Part]]])
        end match
    end parseMultipartStream

    /** Parse a multipart section from a String (used by streaming multipart parser). */
    private def parseMultipartSection(section: String): HttpRequest.Part =
        val headerBodySep = section.indexOf("\r\n\r\n")
        if headerBodySep < 0 then
            HttpRequest.Part("", Absent, Absent, Span.fromUnsafe(section.getBytes(StandardCharsets.UTF_8)))
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
            ): HttpRequest.Part =
                if i >= headerLines.length then
                    HttpRequest.Part(name, filename, partCt, Span.fromUnsafe(cleanBody.getBytes(StandardCharsets.UTF_8)))
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

    /** Parse a multipart section from raw bytes, keeping body data as raw bytes to avoid UTF-8 corruption. */
    private def parseMultipartSectionBytes(section: Array[Byte], offset: Int, length: Int): HttpRequest.Part =
        val sepIdx = indexOfBytes(section, offset, length, CrNlCrNl)
        if sepIdx < 0 then
            HttpRequest.Part("", Absent, Absent, Span.fromUnsafe(java.util.Arrays.copyOfRange(section, offset, offset + length)))
        else
            val headerStr  = new String(section, offset, sepIdx - offset, StandardCharsets.US_ASCII).trim
            val bodyStart  = sepIdx + 4
            val bodyEndRaw = offset + length
            // Strip trailing \r\n
            val bodyEnd = if bodyEndRaw >= bodyStart + 2 && section(bodyEndRaw - 2) == '\r' && section(bodyEndRaw - 1) == '\n' then
                bodyEndRaw - 2
            else bodyEndRaw
            val bodyData    = java.util.Arrays.copyOfRange(section, bodyStart, bodyEnd)
            val headerLines = headerStr.split("\r\n")

            @tailrec def parseHeaders(
                i: Int,
                name: String,
                filename: Maybe[String],
                partCt: Maybe[String]
            ): HttpRequest.Part =
                if i >= headerLines.length then
                    HttpRequest.Part(name, filename, partCt, Span.fromUnsafe(bodyData))
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
    end parseMultipartSectionBytes

    private val CrNlCrNl = Array[Byte]('\r', '\n', '\r', '\n')

    /** Find needle in haystack starting at offset within length. Returns absolute index or -1. */
    private def indexOfBytes(haystack: Array[Byte], offset: Int, length: Int, needle: Array[Byte]): Int =
        val end = offset + length - needle.length
        @tailrec def outer(i: Int): Int =
            if i > end then -1
            else
                @tailrec def inner(j: Int): Boolean =
                    if j >= needle.length then true
                    else if haystack(i + j) != needle(j) then false
                    else inner(j + 1)
                if inner(0) then i
                else outer(i + 1)
        outer(offset)
    end indexOfBytes

    /** Parses a buffered multipart body into a sequence of HttpRequest.Part, operating on raw bytes. */
    private def parseMultipartBody(body: Span[Byte], headers: HttpHeaders, method: String, url: HttpUrl)(using
        Frame
    ): Result[HttpException, Seq[HttpRequest.Part]] =
        val boundaryOpt = headers.get("Content-Type").flatMap { ct =>
            val idx = ct.indexOf("boundary=")
            if idx >= 0 then Present(ct.substring(idx + 9).trim)
            else Absent
        }
        boundaryOpt match
            case Absent =>
                Result.fail(HttpMissingBoundaryException(method, url.toString))
            case Present(boundary) =>
                val delimBytes = s"--$boundary".getBytes(StandardCharsets.US_ASCII)
                val bytes      = body.toArrayUnsafe
                val parts      = ChunkBuilder.init[HttpRequest.Part]

                // Find all boundary positions
                @tailrec def findSections(searchFrom: Int, isFirst: Boolean): Unit =
                    val pos = indexOfBytes(bytes, searchFrom, bytes.length - searchFrom, delimBytes)
                    if pos >= 0 then
                        val afterDelim = pos + delimBytes.length
                        if !isFirst then
                            // The section runs from the end of the previous delimiter to this delimiter
                            // We need to track the previous afterDelim — handled below
                            ()
                        end if
                        // Skip \r\n after delimiter
                        val sectionStart =
                            if afterDelim + 1 < bytes.length && bytes(afterDelim) == '\r' && bytes(afterDelim + 1) == '\n' then
                                afterDelim + 2
                            else afterDelim
                        // Check for closing boundary (--)
                        if afterDelim + 1 < bytes.length && bytes(afterDelim) == '-' && bytes(afterDelim + 1) == '-' then
                            () // closing boundary, done
                        else
                            // Find next boundary
                            val nextPos = indexOfBytes(bytes, sectionStart, bytes.length - sectionStart, delimBytes)
                            if nextPos >= 0 then
                                val sectionLen = nextPos - sectionStart
                                val part       = parseMultipartSectionBytes(bytes, sectionStart, sectionLen)
                                if part.name.nonEmpty then
                                    discard(parts += part)
                                findSections(nextPos, false)
                            else
                                // No more boundaries — parse remaining as last section
                                val sectionLen = bytes.length - sectionStart
                                if sectionLen > 0 then
                                    val part = parseMultipartSectionBytes(bytes, sectionStart, sectionLen)
                                    if part.name.nonEmpty then
                                        discard(parts += part)
                                end if
                            end if
                        end if
                    end if
                end findSections

                findSections(0, true)
                Result.succeed(parts.result())
        end match
    end parseMultipartBody

    // ==================== Internal: multipart encoding ====================

    private def encodeMultipartParts(parts: Seq[HttpRequest.Part], boundary: String): Span[Byte] =
        val out = new java.io.ByteArrayOutputStream
        parts.foreach { part =>
            appendMultipartPartBytes(out, part, boundary)
        }
        out.write(s"--$boundary--\r\n".getBytes(StandardCharsets.US_ASCII))
        Span.fromUnsafe(out.toByteArray)
    end encodeMultipartParts

    private def encodeMultipartPart(part: HttpRequest.Part, boundary: String): Span[Byte] =
        val out = new java.io.ByteArrayOutputStream
        appendMultipartPartBytes(out, part, boundary)
        Span.fromUnsafe(out.toByteArray)
    end encodeMultipartPart

    private def appendMultipartPartBytes(out: java.io.ByteArrayOutputStream, part: HttpRequest.Part, boundary: String): Unit =
        val header = new StringBuilder
        discard(header.append("--").append(boundary).append("\r\n"))
        discard(header.append("Content-Disposition: form-data; name=\"").append(part.name).append('"'))
        part.filename match
            case Present(fn) => discard(header.append("; filename=\"").append(fn).append('"'))
            case Absent      =>
        discard(header.append("\r\n"))
        part.contentType match
            case Present(ct) => discard(header.append("Content-Type: ").append(ct).append("\r\n"))
            case Absent      =>
        discard(header.append("\r\n"))
        out.write(header.toString.getBytes(StandardCharsets.US_ASCII))
        // Write body data as raw bytes — no String conversion
        val data = part.data.toArrayUnsafe
        out.write(data, 0, data.length)
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII))
    end appendMultipartPartBytes

    // ==================== Internal: helpers ====================

    /** Whether the given schema is the canonical Schema[Unit] instance. Used for short-circuiting Unit-valued JSON decoding on empty or
      * "null" bodies (mirrors the Json[Unit] override that existed in the old kyo-http Json typeclass).
      */
    private def isUnitSchema(schema: Schema[Any]): Boolean =
        (schema: AnyRef) eq (Schema.unitSchema: AnyRef)

    private def isStreamingContentType(ct: HttpRoute.ContentType[?]): Boolean =
        ct match
            case HttpRoute.ContentType.ByteStream | _: HttpRoute.ContentType.Ndjson[?] |
                _: HttpRoute.ContentType.Sse[?] | _: HttpRoute.ContentType.SseText | HttpRoute.ContentType.MultipartStream => true
            case _ => false

    private def findBodyField(fields: Chunk[HttpRoute.Field[?]]): Maybe[HttpRoute.Field.Body[?, ?]] =
        @tailrec def loop(i: Int): Maybe[HttpRoute.Field.Body[?, ?]] =
            if i >= fields.size then Absent
            else
                fields(i) match
                    case body: HttpRoute.Field.Body[?, ?] => Present(body)
                    case _                                => loop(i + 1)
        loop(0)
    end findBodyField

    private def buildRequest[In, Out, S](
        route: HttpRoute[In, Out, S],
        headers: HttpHeaders,
        builder: DictBuilder[String, Any],
        path: String = "",
        methodOverride: Maybe[HttpMethod] = Absent
    ): HttpRequest[In] =
        val url =
            if path.isEmpty then HttpUrl(Absent, "", 0, "", Absent)
            else HttpUrl(Absent, "", 0, path, Absent)
        val method = methodOverride match
            case Present(m) => m
            case Absent     => route.method
        HttpRequest(method, url, headers, Record(builder.result()))
    end buildRequest

    /** Check that the request Content-Type matches the expected media type. Accepts if: header is absent (lenient), or starts with the
      * expected type (allows charset params).
      */
    private def checkContentType(headers: HttpHeaders, expected: String): Boolean =
        headers.get("Content-Type") match
            case Absent => true
            case Present(ct) =>
                val lower = ct.toLowerCase
                lower.startsWith(expected)
    end checkContentType

    // ==================== Error response body ====================

    private[kyo] inline def encodeHalt[A](halt: HttpResponse.Halt)(inline f: (HttpStatus, HttpHeaders, Span[Byte]) => A)(using Frame): A =
        val status = halt.response.status
        if status.code == 304 || status.code == 204 || (status.code >= 100 && status.code < 200) then
            // 1xx, 204, and 304 responses MUST NOT contain a message body (RFC 7230 §3.3)
            // and SHOULD NOT include Content-Type or Content-Length headers
            val headers = halt.response.headers.remove("Content-Type").remove("Content-Length")
            f(status, headers, Span.empty[Byte])
        else
            val body    = encodeErrorBody(status)
            val headers = halt.response.headers.add("Content-Type", "application/json")
            f(status, headers, body)
        end if
    end encodeHalt

    private case class ErrorBody(status: Int, error: String) derives Schema

    private[kyo] def encodeErrorBody(status: HttpStatus)(using Frame): Span[Byte] =
        stringToSpan(Json.encode(ErrorBody(status.code, status.toString)))
    end encodeErrorBody

    private[kyo] def encodeErrorBodyWithMessage(status: HttpStatus, message: String)(using Frame): Span[Byte] =
        stringToSpan(Json.encode(ErrorBody(status.code, message)))
    end encodeErrorBodyWithMessage

end RouteUtil
