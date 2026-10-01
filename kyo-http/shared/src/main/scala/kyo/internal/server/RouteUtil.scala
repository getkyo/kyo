package kyo.internal.server

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.Ascii
import kyo.internal.PercentEncoding
import kyo.kernel.ArrowEffect
import kyo.mime.Disposition
import kyo.mime.MediaType
import kyo.mime.Multipart
import kyo.mime.Parameters
import scala.annotation.publicInBinary
import scala.annotation.tailrec

/** Shared codec toolkit for translating between typed route definitions (Record-based) and wire-level HTTP primitives. Used internally by
  * backend implementations to avoid duplicating marshalling logic across platforms (Netty, curl, fetch).
  *
  * Encode methods use continuations to avoid intermediate tuple allocations. Decode methods return typed HttpRequest/HttpResponse directly
  * with all unsafe Dict[String, Any] access encapsulated here.
  *
  * Public binary visibility supports inline callers while Scala access remains restricted to kyo.
  */
@publicInBinary
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

    inline def encodeRequest[In, Out, S, A, S2](
        route: HttpRoute[In, Out, S],
        request: HttpRequest[In]
    )(
        inline onEmpty: ( /* url */ String, HttpHeaders) => A < S2,
        inline onBuffered: ( /* url */ String, HttpHeaders, Span[Byte]) => A < S2,
        inline onStreaming: ( /* url */ String, HttpHeaders, Stream[Span[Byte], Async & Abort[HttpException]]) => A < S2
    )(using Frame): A < (S2 & Sync & Abort[HttpException]) =
        // A route without a multipart body is encoded now, not behind a `map` the scheduler may suspend.
        if isMultipart(sentBodyField(route, request)) then
            bodyPlanForRequest(route, request).map { plan =>
                encodeRequestWith(route, request, plan)(onEmpty, onBuffered, onStreaming)
            }
        else encodeRequestWith(route, request, BodyPlan.Direct)(onEmpty, onBuffered, onStreaming)
    end encodeRequest

    private[kyo] inline def encodeRequestWith[In, Out, S, A](
        route: HttpRoute[In, Out, S],
        request: HttpRequest[In],
        plan: BodyPlan
    )(
        inline onEmpty: ( /* url */ String, HttpHeaders) => A,
        inline onBuffered: ( /* url */ String, HttpHeaders, Span[Byte]) => A,
        inline onStreaming: ( /* url */ String, HttpHeaders, Stream[Span[Byte], Async & Abort[HttpException]]) => A
    )(using Frame): A =
        val fields    = route.request.fields
        val dict      = request.fields.dict
        val bodyField = findBodyField(fields)
        val hasParams = fields.size > (if bodyField.isDefined then 1 else 0)

        // Use request.url.path when explicitly set (e.g., redirects), otherwise build from route captures
        val basePath =
            if request.url.path.nonEmpty then request.url.path
            else buildPath(route.request.path, dict)

        val effectiveBodyField = sentBodyField(route, request)

        if hasParams then
            val queryBuilder  = new StringBuilder
            val headerBuilder = ChunkBuilder.init[(String, String)]
            val cookieBuilder = encodeRequestParams(fields, dict, queryBuilder, headerBuilder)
            cookieBuilder match
                case Present(cb) =>
                    discard(headerBuilder += ("Cookie" -> cb.toString))
                case Absent =>
            end match
            val extraHeaders: HttpHeaders = headerBuilder.result()
            val url                       = request.url.rawQuery match
                case Present(rq) =>
                    if queryBuilder.nonEmpty then s"$basePath?$rq&$queryBuilder"
                    else s"$basePath?$rq"
                case _ =>
                    if queryBuilder.nonEmpty then s"$basePath?$queryBuilder"
                    else basePath
            val hdrs = if extraHeaders.isEmpty then request.headers
            else request.headers.concat(extraHeaders)
            encodeBody(effectiveBodyField, dict, url, hdrs, plan)(onEmpty, onBuffered, onStreaming)
        else
            val url = request.url.rawQuery match
                case Present(rq) => s"$basePath?$rq"
                case _           => basePath
            encodeBody(effectiveBodyField, dict, url, request.headers, plan)(onEmpty, onBuffered, onStreaming)
        end if
    end encodeRequestWith

    /** The body plan of `request` on `route`, decided once; the client reuses it for every attempt, so a retried or redirected request
      * keeps its multipart boundary.
      */
    private[kyo] def bodyPlanForRequest[In, Out, S](
        route: HttpRoute[In, Out, S],
        request: HttpRequest[In]
    )(using Frame): BodyPlan < (Sync & Abort[HttpException]) =
        bodyPlan(sentBodyField(route, request), request.fields.dict, request.headers)

    private def isMultipart(bodyField: Maybe[HttpRoute.Field.Body[?, ?]]): Boolean =
        bodyField.exists(body =>
            body.contentType == HttpRoute.ContentType.Multipart || body.contentType == HttpRoute.ContentType.MultipartStream
        )

    /** The body field a request is sent with: none for a GET or HEAD, whose route may still declare one after a 303 turned the method into
      * GET (RFC 9110 section 15.4.4).
      */
    private[kyo] def sentBodyField[In, Out, S](route: HttpRoute[In, Out, S], request: HttpRequest[In]): Maybe[HttpRoute.Field.Body[?, ?]] =
        findBodyField(route.request.fields).filter(_ => request.method != HttpMethod.GET && request.method != HttpMethod.HEAD)

    private inline def encodeBody[A](
        bodyField: Maybe[HttpRoute.Field.Body[?, ?]],
        dict: Dict[String, Any],
        url: String,
        headers: HttpHeaders,
        plan: BodyPlan
    )(
        inline onEmpty: (String, HttpHeaders) => A,
        inline onBuffered: (String, HttpHeaders, Span[Byte]) => A,
        inline onStreaming: (String, HttpHeaders, Stream[Span[Byte], Async & Abort[HttpException]]) => A
    )(using Frame): A =
        bodyField match
            case Absent        => onEmpty(url, headers)
            case Present(body) =>
                plan match
                    case BodyPlan.MultipartBuffered(contentType, bytes) =>
                        onBuffered(url, encodedBodyHeaders(body.contentType, headers, contentType), bytes)
                    case BodyPlan.MultipartStreamed(boundary) =>
                        val parts = dict(body.fieldName).asInstanceOf[Stream[HttpRequest.Part, Async & Abort[HttpException]]]
                        onStreaming(
                            url,
                            encodedBodyHeaders(body.contentType, headers, boundary.contentType),
                            multipartStream(parts, boundary)
                        )
                    case BodyPlan.Direct =>
                        val value = dict(body.fieldName)
                        if isStreamingContentType(body.contentType) then
                            encodeStreamBodyValueWith(body.contentType, value) { (ct, stream) =>
                                onStreaming(url, encodedBodyHeaders(body.contentType, headers, ct), stream)
                            }
                        else
                            encodeBufferedBodyValueWith(body.contentType, value) { (ct, bytes) =>
                                onBuffered(url, encodedBodyHeaders(body.contentType, headers, ct), bytes)
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
        // For non-success responses, attach the raw body as text on the response (or on the
        // exception when decode failed). Schema decoding is permissive and silently accepts
        // arbitrary JSON shapes — without raw body, callers can't distinguish "200 with empty
        // fields" from "500 with name-conflict message". The rawBody field is Absent for 2xx,
        // so success-path memory cost is one reference.
        result match
            case Result.Success(resp) if !status.isSuccess =>
                Result.succeed(resp.copy(rawBody = if body.isEmpty then Absent else Present(spanToString(body))))
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
            case Result.Panic(e)   => Abort.panic(e)
    end decodeBufferedResponseWith

    def decodeStreamingResponse[In, Out, S](
        route: HttpRoute[In, Out, S],
        status: HttpStatus,
        headers: HttpHeaders,
        stream: Stream[Span[Byte], Async & Abort[HttpException]],
        method: String,
        url: HttpUrl
    )(using Frame): Result[HttpException, HttpResponse[Out]] =
        val fields    = route.response.fields
        val bodyField = findBodyField(fields)
        val hasParams = fields.size > (if bodyField.isDefined then 1 else 0)

        val builder = DictBuilder.init[String, Any]

        // No response is declared with a multipart stream body, so the part bound, a server setting, does not apply here.
        def body(bf: HttpRoute.Field.Body[?, ?]): Any =
            decodeStreamBodyValue(bf.contentType, stream, headers, method, url, maxPartSize = Int.MaxValue)
        validateStreamingMultipartBoundary(bodyField, headers, method, url).flatMap { _ =>
            if !hasParams then
                bodyField.foreach(bf => discard(builder.add(bf.fieldName, body(bf))))
                Result.succeed(HttpResponse(status, headers, Record(builder.result())))
            else
                decodeParamFields(fields, headers, Absent, builder, method, url, isResponse = true).map { _ =>
                    bodyField.foreach(bf => discard(builder.add(bf.fieldName, body(bf))))
                    HttpResponse(status, headers, Record(builder.result()))
                }
            end if
        }
    end decodeStreamingResponse

    def decodeStreamingResponseWith[In, Out, S, A, S2](
        route: HttpRoute[In, Out, S],
        status: HttpStatus,
        headers: HttpHeaders,
        stream: Stream[Span[Byte], Async & Abort[HttpException]],
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
                        Result.succeed(buildRequest(route, headers, builder, path, queryParam, methodOverride))
                    case Present(bf) =>
                        decodeBufferedBodyValue(bf.contentType, body, headers, ctxMethod, ctxUrl).map { value =>
                            discard(builder.add(bf.fieldName, value))
                            buildRequest(route, headers, builder, path, queryParam, methodOverride)
                        }
            }
        }
    end decodeBufferedRequest

    def decodeStreamingRequest[In, Out, S](
        route: HttpRoute[In, Out, S],
        pathCaptures: Dict[String, String],
        queryParam: Maybe[HttpUrl],
        headers: HttpHeaders,
        stream: Stream[Span[Byte], Async & Abort[HttpException]],
        maxPartSize: Int,
        path: String = "",
        methodOverride: Maybe[HttpMethod] = Absent
    )(using Frame): Result[HttpException, HttpRequest[In]] =
        val ctxMethod = route.method.name
        val ctxUrl    = HttpUrl.fromUri(route.request.path.show)
        val fields    = route.request.fields
        val bodyField = findBodyField(fields)
        val hasParams = fields.size > (if bodyField.isDefined then 1 else 0)
        val builder   = DictBuilder.init[String, Any]

        validateStreamingMultipartBoundary(bodyField, headers, ctxMethod, ctxUrl).flatMap { _ =>
            decodeCaptures(route.request.path, pathCaptures, builder, ctxMethod, ctxUrl).flatMap { _ =>
                val paramsResult =
                    if hasParams then decodeParamFields(fields, headers, queryParam, builder, ctxMethod, ctxUrl)
                    else Result.unit
                paramsResult.map { _ =>
                    bodyField.foreach(bf =>
                        discard(builder.add(
                            bf.fieldName,
                            decodeStreamBodyValue(bf.contentType, stream, headers, ctxMethod, ctxUrl, maxPartSize)
                        ))
                    )
                    buildRequest(route, headers, builder, path, queryParam, methodOverride)
                }
            }
        }
    end decodeStreamingRequest

    // ==================== Server: encode response ====================

    def encodeResponse[In, Out, S, A, S2](
        route: HttpRoute[In, Out, S],
        response: HttpResponse[Out]
    )(
        onEmpty: (HttpStatus, HttpHeaders) => A < S2,
        onBuffered: (HttpStatus, HttpHeaders, Span[Byte]) => A < S2,
        onStreaming: (HttpStatus, HttpHeaders, Stream[Span[Byte], Async & Abort[HttpException]]) => A < S2
    )(using Frame): A < (S2 & Sync & Abort[HttpException]) =
        val bodyField = findBodyField(route.response.fields)
        if isMultipart(bodyField) then
            bodyPlan(bodyField, response.fields.dict, response.headers).map { plan =>
                encodeResponseWith(route, response, plan)(onEmpty, onBuffered, onStreaming)
            }
        else encodeResponseWith(route, response, BodyPlan.Direct)(onEmpty, onBuffered, onStreaming)
        end if
    end encodeResponse

    private def encodeResponseWith[In, Out, S, A](
        route: HttpRoute[In, Out, S],
        response: HttpResponse[Out],
        plan: BodyPlan
    )(
        onEmpty: (HttpStatus, HttpHeaders) => A,
        onBuffered: (HttpStatus, HttpHeaders, Span[Byte]) => A,
        onStreaming: (HttpStatus, HttpHeaders, Stream[Span[Byte], Async & Abort[HttpException]]) => A
    )(using Frame): A =
        val fields      = route.response.fields
        val routeStatus = route.response.status
        val status      = if routeStatus != HttpStatus.OK then routeStatus else response.status
        val bodyField   = findBodyField(fields)
        val hasParams   = fields.size > (if bodyField.isDefined then 1 else 0)

        // Fast path: no param headers to encode
        if !hasParams then
            encodeResponseBody(bodyField, response.fields.dict, status, response.headers, plan)(onEmpty, onBuffered, onStreaming)
        else
            val dict          = response.fields.dict
            val headerBuilder = ChunkBuilder.init[(String, String)]
            encodeResponseParams(fields, dict, headerBuilder)
            val extraHeaders: HttpHeaders = headerBuilder.result()
            val headers                   = if extraHeaders.isEmpty then response.headers
            else response.headers.concat(extraHeaders)
            encodeResponseBody(bodyField, dict, status, headers, plan)(onEmpty, onBuffered, onStreaming)
        end if
    end encodeResponseWith

    private def encodeResponseBody[A](
        bodyField: Maybe[HttpRoute.Field.Body[?, ?]],
        dict: Dict[String, Any],
        status: HttpStatus,
        headers: HttpHeaders,
        plan: BodyPlan
    )(
        onEmpty: (HttpStatus, HttpHeaders) => A,
        onBuffered: (HttpStatus, HttpHeaders, Span[Byte]) => A,
        onStreaming: (HttpStatus, HttpHeaders, Stream[Span[Byte], Async & Abort[HttpException]]) => A
    )(using Frame): A =
        bodyField match
            case Absent        => onEmpty(status, headers)
            case Present(body) =>
                plan match
                    case BodyPlan.MultipartBuffered(contentType, bytes) =>
                        onBuffered(status, encodedBodyHeaders(body.contentType, headers, contentType), bytes)
                    case BodyPlan.MultipartStreamed(boundary) =>
                        val parts = dict(body.fieldName).asInstanceOf[Stream[HttpRequest.Part, Async & Abort[HttpException]]]
                        onStreaming(
                            status,
                            encodedBodyHeaders(body.contentType, headers, boundary.contentType),
                            multipartStream(parts, boundary)
                        )
                    case BodyPlan.Direct =>
                        val value = dict(body.fieldName)
                        if isStreamingContentType(body.contentType) then
                            encodeStreamBodyValueWith(body.contentType, value) { (ct, stream) =>
                                onStreaming(status, encodedBodyHeaders(body.contentType, headers, ct), stream)
                            }
                        else
                            encodeBufferedBodyValueWith(body.contentType, value) { (ct, bytes) =>
                                onBuffered(status, encodedBodyHeaders(body.contentType, headers, ct), bytes)
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

    private[kyo] def buildPath(path: HttpPath[?], dict: Dict[String, Any]): String =
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
                discard(sb.append(PercentEncoding.encode(encoded, PercentEncoding.Mode.Component)))
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
            case _: HttpPath.Literal       => Result.unit
            case c: HttpPath.Capture[?, ?] =>
                val wireName = if c.wireName.isEmpty then c.fieldName else c.wireName
                captures.get(wireName) match
                    case Present(raw) =>
                        c.codec.decode(raw)
                            .map(decoded => discard(builder.add(c.fieldName, decoded)))
                            .mapFailure(e => HttpPathDecodeException(wireName, method, url.toString, e))
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
        headerBuilder: ChunkBuilder[(String, String)]
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
                                        .append(PercentEncoding.encode(wireName, PercentEncoding.Mode.Component))
                                        .append('=')
                                        .append(PercentEncoding.encode(encoded, PercentEncoding.Mode.Component)))
                                    cookieBuilder
                                case HttpRoute.Field.Param.Location.Header =>
                                    discard(headerBuilder += (wireName -> encoded))
                                    cookieBuilder
                                case HttpRoute.Field.Param.Location.Cookie =>
                                    // The request-side mirror of Set-Cookie serialization, and the same grammar applies: RFC 6265
                                    // section 4.2.1 makes "; " the separator BETWEEN cookie-pairs, so a value carrying one does not
                                    // extend a cookie, it appends another. Concatenating unchecked would let a caller-supplied value
                                    // add or overwrite cookies the caller never named, and a CR or LF would end the header outright.
                                    // Refused rather than escaped for the reason the response side gives: the grammar defines no
                                    // escape, so there is nothing to encode to that a server would decode back.
                                    require(
                                        HttpHeaders.isValidCookieName(wireName),
                                        s"cookie name must be a token per RFC 6265 section 4.1.1; got: $wireName"
                                    )
                                    require(
                                        HttpHeaders.isValidCookieValue(encoded),
                                        s"cookie value must be cookie-octets per RFC 6265 section 4.1.1 (no controls, whitespace, ';', ',', '\"' or '\\\\'); got: $encoded"
                                    )
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
        headerBuilder: ChunkBuilder[(String, String)]
    ): Unit =
        @tailrec def loop(i: Int): Unit =
            if i < fields.size then
                fields(i) match
                    case param: HttpRoute.Field.Param[?, ?, ?] =>
                        val wireName = if param.wireName.isEmpty then param.fieldName else param.wireName
                        dict.get(param.fieldName).flatMap(unwrapOptional(param.optional, _)).foreach { v =>
                            param.kind match
                                case HttpRoute.Field.Param.Location.Header =>
                                    discard(headerBuilder += (wireName -> param.codec.asInstanceOf[HttpCodec[Any]].encode(v)))
                                case HttpRoute.Field.Param.Location.Cookie =>
                                    v match
                                        case cookie: HttpCookie[?] =>
                                            discard(headerBuilder += ("Set-Cookie" -> HttpHeaders.serializeCookie(wireName, cookie)))
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
        val wireName           = if param.wireName.isEmpty then param.fieldName else param.wireName
        val fieldType          = Ascii.toLower(param.kind.toString)
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

    private def encodeBufferedBodyValueWith[A](
        ct: HttpRoute.ContentType[?],
        value: Any
    )(f: (String, Span[Byte]) => A)(using Frame): A =
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
            case _ =>
                throw new IllegalStateException(s"Cannot encode streaming ContentType as buffered: $ct")
    end encodeBufferedBodyValueWith

    private def encodeStreamBodyValueWith[A](
        ct: HttpRoute.ContentType[?],
        value: Any
    )(
        f: (String, Stream[Span[Byte], Async & Abort[HttpException]]) => A
    )(using Frame): A =
        ct match
            case HttpRoute.ContentType.ByteStream =>
                f("application/octet-stream", value.asInstanceOf[Stream[Span[Byte], Async & Abort[HttpException]]])
            case ndjson: HttpRoute.ContentType.Ndjson[?] =>
                val stream     = value.asInstanceOf[Stream[Any, Async & Abort[HttpException]]]
                val schema     = ndjson.schema.asInstanceOf[Schema[Any]]
                val byteStream = stream.mapPure { v =>
                    stringToSpan(Json.encode(v)(using schema) + "\n")
                }(using ndjson.emitTag.asInstanceOf[Tag[Emit[Chunk[Any]]]], Tag[Emit[Chunk[Span[Byte]]]])
                f("application/x-ndjson", byteStream)
            case sse: HttpRoute.ContentType.Sse[?] =>
                val stream     = value.asInstanceOf[Stream[HttpSseEvent[Any], Async & Abort[HttpException]]]
                val schema     = sse.schema.asInstanceOf[Schema[Any]]
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
                val stream     = value.asInstanceOf[Stream[HttpSseEvent[String], Async & Abort[HttpException]]]
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
                    val dataLines                              = event.data.split('\n')
                    @tailrec def appendDataLines(i: Int): Unit =
                        if i < dataLines.length then
                            discard(sb.append("data: ").append(dataLines(i)).append('\n'))
                            appendDataLines(i + 1)
                    appendDataLines(0)
                    discard(sb.append('\n'))
                    stringToSpan(sb.toString)
                }(using sseText.emitTag, Tag[Emit[Chunk[Span[Byte]]]])
                f("text/event-stream", byteStream)
            case _ =>
                throw new IllegalStateException(s"Cannot encode non-streaming ContentType as stream: $ct")
    end encodeStreamBodyValueWith

    /** How a body goes on the wire, decided before its head is built. A multipart body needs a boundary, which may be generated, and its
      * parts' headers can fail to render, so both happen here, on the encoder's row, and the head is built only for a body that can be
      * written: a buffered body is encoded whole, a streamed one carries its boundary and fails its stream at the part that cannot go out.
      * Every other body is encoded by the synchronous encoders.
      */
    private[kyo] enum BodyPlan derives CanEqual:
        case Direct
        case MultipartBuffered(contentType: String, bytes: Span[Byte])
        case MultipartStreamed(boundary: MultipartBoundary)
    end BodyPlan

    private def bodyPlan(
        bodyField: Maybe[HttpRoute.Field.Body[?, ?]],
        dict: Dict[String, Any],
        headers: HttpHeaders
    )(using Frame): BodyPlan < (Sync & Abort[HttpException]) =
        bodyField match
            case Present(body) =>
                body.contentType match
                    case HttpRoute.ContentType.Multipart =>
                        val parts = dict(body.fieldName).asInstanceOf[Seq[HttpRequest.Part]]
                        multipartBoundary(headers).map { boundary =>
                            Abort.get(encodeMultipartParts(parts, boundary.value)).map { bytes =>
                                BodyPlan.MultipartBuffered(boundary.contentType, bytes)
                            }
                        }
                    case HttpRoute.ContentType.MultipartStream =>
                        multipartBoundary(headers).map(BodyPlan.MultipartStreamed(_))
                    case _ => BodyPlan.Direct
            case Absent => BodyPlan.Direct
    end bodyPlan

    /** The boundary the caller's Content-Type supplies when it is usable, or a generated one. */
    private def multipartBoundary(headers: HttpHeaders)(using Frame): MultipartBoundary < (Sync & Abort[HttpException]) =
        val value = multipartBoundaryFromHeaders(headers) match
            case Present(supplied) => supplied: String < Sync
            case Absent            => UUID.v4String
        value.map(v => Abort.get(multipartBoundaryOf(v)))
    end multipartBoundary

    /** The parts of a streamed multipart body framed by `boundary` and closed by its close delimiter. A part whose headers cannot be
      * written fails the stream after the parts before it.
      */
    private def multipartStream(
        parts: Stream[HttpRequest.Part, Async & Abort[HttpException]],
        boundary: MultipartBoundary
    )(using frame: Frame): Stream[Span[Byte], Async & Abort[HttpException]] =
        given Tag[Emit[Chunk[Span[Byte]]]] = Tag[Emit[Chunk[Span[Byte]]]]
        val framed                         = Stream[Span[Byte], Async & Abort[HttpException]](
            ArrowEffect.handleLoopState(Tag[Emit[Chunk[HttpRequest.Part]]], (), parts.emit)([C] =>
                (_, input) =>
                    val encoded                                                   = ChunkBuilder.init[Span[Byte]]
                    @tailrec def loop(i: Int): Maybe[Result.Error[HttpException]] =
                        if i >= input.size then Absent
                        else
                            encodeMultipartPart(input(i), boundary.value) match
                                case Result.Success(bytes) =>
                                    discard(encoded += bytes)
                                    loop(i + 1)
                                case Result.Failure(e) => Present(Result.Failure(e))
                                case p: Result.Panic   => Present(p)
                    val fault   = loop(0)
                    val written = encoded.result()
                    val emitted = if written.isEmpty then Kyo.unit else Emit.value(written)
                    emitted.andThen(fault match
                        case Present(error) => Abort.error(error)
                        case Absent         => Loop.continue((), ()))
            )
        )
        framed.concat(Stream.init(Seq(stringToSpan(s"--${boundary.value}--\r\n"))))
    end multipartStream

    /** The `boundary` of a `multipart/form-data` Content-Type, when the header is that media type and the parameter is a boundary RFC 2046
      * section 5.1.1 allows (1 to 70 of its characters, not ending in a space). The parameter is read as kyo-mime reads one, so a value a
      * sender should have quoted (`boundary=abc:def`) is taken as written: the body was framed with it, and refusing it helps nobody.
      */
    private def multipartBoundaryFromHeaders(headers: HttpHeaders)(using Frame): Maybe[String] =
        headers.get("Content-Type").flatMap { contentType =>
            MediaType.parse(contentType).toMaybe
                .filter(_.baseType == "multipart/form-data")
                .flatMap(_.parameter("boundary"))
                .filter(Multipart.isValidBoundary)
        }

    /** A multipart body's boundary: the value its delimiter lines carry, and the Content-Type line that declares it, rendered through
      * kyo-mime so a boundary that is not a token goes out quoted.
      */
    final private[kyo] case class MultipartBoundary(value: String, contentType: String)

    /** The boundary `value` with its Content-Type. For a value `Multipart.isValidBoundary` accepts, which a UUID is, the failure cannot
      * happen: kyo-mime renders any such value, quoted when it is not a token. It stays a `Result` because `MediaType.init` and `render`
      * return one.
      */
    private def multipartBoundaryOf(value: String)(using Frame): Result[HttpException, MultipartBoundary] =
        MediaType.init("multipart", "form-data", "boundary" -> value).flatMap(_.render) match
            case Result.Success(contentType) => Result.succeed(MultipartBoundary(value, contentType))
            case _                           => Result.fail(HttpInvalidFieldException("the multipart boundary"))

    private def validateStreamingMultipartBoundary(
        bodyField: Maybe[HttpRoute.Field.Body[?, ?]],
        headers: HttpHeaders,
        method: String,
        url: HttpUrl
    )(using Frame): Result[HttpException, Unit] =
        val multipartStream = bodyField.exists(_.contentType == HttpRoute.ContentType.MultipartStream)
        if multipartStream && multipartBoundaryFromHeaders(headers).isEmpty then
            Result.fail(HttpMissingBoundaryException(method, url.toString))
        else Result.unit
    end validateStreamingMultipartBoundary

    private def encodedBodyHeaders(
        bodyContentType: HttpRoute.ContentType[?],
        headers: HttpHeaders,
        encodedContentType: String
    ): HttpHeaders =
        if !headers.contains("Content-Type") then headers.add("Content-Type", encodedContentType)
        else
            bodyContentType match
                // The encoded value carries the boundary the body was framed with, the caller's own when it was usable, rendered so
                // that a boundary which is not a token goes out quoted.
                case HttpRoute.ContentType.Multipart | HttpRoute.ContentType.MultipartStream =>
                    headers.set("Content-Type", encodedContentType)
                case _ => headers
    end encodedBodyHeaders

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

    /** The value of a streamed body field: the byte stream itself, or a stream decoded from it line by line, frame by frame or part by
      * part. A line or frame whose JSON does not decode fails the stream with `HttpJsonDecodeException` on the row the stream declares.
      */
    private def decodeStreamBodyValue(
        ct: HttpRoute.ContentType[?],
        stream: Stream[Span[Byte], Async & Abort[HttpException]],
        headers: HttpHeaders,
        method: String,
        url: HttpUrl,
        maxPartSize: Int
    )(using Frame): Any =
        ct match
            case HttpRoute.ContentType.ByteStream =>
                stream
            case ndjson: HttpRoute.ContentType.Ndjson[?] =>
                val schema = ndjson.schema.asInstanceOf[Schema[Any]]
                framed(
                    stream,
                    JsonLines.Framer.init(MaxFramedLine),
                    (f, span) => feedNdjson(f, span),
                    finishNdjson,
                    decodeJson(schema, method, url)
                )(
                    using
                    summon[Frame],
                    ndjson.emitTag.asInstanceOf[Tag[Emit[Chunk[Any]]]]
                )
            case sse: HttpRoute.ContentType.Sse[?] =>
                val schema = sse.schema.asInstanceOf[Schema[Any]]
                val decode = decodeJson(schema, method, url)
                framed(
                    stream,
                    SseFraming.empty,
                    (st, span) => feedSse(st, span),
                    finishSse,
                    (event: HttpSseEvent[String]) => decode(event.data).map(value => event.copy(data = value))
                )(using summon[Frame], sse.emitTag.asInstanceOf[Tag[Emit[Chunk[HttpSseEvent[Any]]]]])
            case sseText: HttpRoute.ContentType.SseText =>
                framed(stream, SseFraming.empty, (st, span) => feedSse(st, span), finishSse, Result.succeed)(
                    using
                    summon[Frame],
                    sseText.emitTag
                )
            case HttpRoute.ContentType.MultipartStream =>
                parseMultipartStream(stream, headers, maxPartSize)
            case _ =>
                throw new IllegalStateException(s"Cannot decode non-streaming ContentType as stream: $ct")
    end decodeStreamBodyValue

    // ==================== Streamed body framing ====================

    /** What feeding one span to a framing state produced: the elements the span completed, in order, the state for the next span, and the
      * fault framing ended with, if it ended. A fault never discards the elements completed before it. A fault is a `Result.Error` so a
      * panic met while framing is raised as the panic it was, after those elements.
      */
    final private case class Framing[State, F](state: State, elements: Chunk[F], fault: Maybe[Result.Error[HttpException]])

    /** Frames a byte stream into elements and decodes them, on bytes: the framing state carries what a span left incomplete, so an
      * element, a multi-byte character or a delimiter split across spans is reassembled and binary content passes unchanged. The elements
      * each span completes are decoded in order and emitted together; the first that does not decode, or the fault framing ended with,
      * fails the stream after them.
      */
    private def framed[State, F, A](
        stream: Stream[Span[Byte], Async & Abort[HttpException]],
        initial: State,
        feed: (State, Span[Byte]) => Framing[State, F],
        finish: State => (Chunk[F], Maybe[Result.Error[HttpException]]),
        decode: F => Result[HttpException, A]
    )(using frame: Frame, emitTag: Tag[Emit[Chunk[A]]]): Stream[A, Async & Abort[HttpException]] =
        def emitDecoded(
            elements: Chunk[F],
            fault: Maybe[Result.Error[HttpException]]
        ): Maybe[Result.Error[HttpException]] < (Emit[Chunk[A]] & Abort[HttpException]) =
            val decoded                                                   = ChunkBuilder.init[A]
            @tailrec def loop(i: Int): Maybe[Result.Error[HttpException]] =
                if i >= elements.size then fault
                else
                    decode(elements(i)) match
                        case Result.Success(a) =>
                            discard(decoded += a)
                            loop(i + 1)
                        case Result.Failure(e) => Present(Result.Failure(e))
                        case p: Result.Panic   => Present(p)
            val ended  = loop(0)
            val prefix = decoded.result()
            if prefix.isEmpty then ended else Emit.valueWith(prefix)(ended)(using emitTag, frame)
        end emitDecoded
        Stream(
            ArrowEffect.handleLoopState(Tag[Emit[Chunk[Span[Byte]]]], initial, stream.emit)(
                [C] =>
                    (state, input) =>
                        @tailrec def feedAll(i: Int, st: State, acc: Chunk[F]): Framing[State, F] =
                            if i >= input.size then Framing(st, acc, Absent)
                            else
                                val next = feed(st, input(i))
                                if next.fault.isDefined then Framing(next.state, acc.concat(next.elements), next.fault)
                                else feedAll(i + 1, next.state, acc.concat(next.elements))
                        val fed = feedAll(0, state, Chunk.empty)
                        emitDecoded(fed.elements, fed.fault).map {
                            case Present(error) => Abort.error(error)
                            case Absent         => Loop.continue(fed.state, ())
                        }
                ,
                (state, _) =>
                    val (elements, fault) = finish(state)
                    emitDecoded(elements, fault).map {
                        case Present(error) => Abort.error(error)
                        case Absent         => Kyo.unit
                    }
            )
        )
    end framed

    private def decodeJson(schema: Schema[Any], method: String, url: HttpUrl)(using Frame): String => Result[HttpException, Any] =
        text =>
            Json.decode[Any](text)(using summon[Json], schema, summon[Frame]) match
                case Result.Success(v) => Result.succeed(v)
                case Result.Failure(e) => Result.fail(HttpJsonDecodeException(e.getMessage, method, url.toString))
                case p: Result.Panic   => p

    /** The largest NDJSON record, SSE line or multipart boundary line the framers hold before failing the stream: a peer that never sends
      * the terminator would otherwise grow the pending bytes without bound (CWE-400). A multipart part is bounded by
      * `HttpServerConfig.maxMultipartPartSize` instead.
      */
    private val MaxFramedLine: ByteSize = 16.mib

    private val MaxFramedLineBytes: Int = MaxFramedLine.toBytes.toInt

    private def tooLarge(size: Long, limit: Int)(using Frame): Result.Error[HttpException] =
        Result.Failure(HttpPayloadTooLargeException(if size > Int.MaxValue.toLong then Int.MaxValue else size.toInt, limit))

    // NDJSON: one JSON record per line, framed by kyo-schema-json, which strips the terminator and any CR before it, skips blank lines
    // and a byte order mark, and bounds a record.

    private def feedNdjson(framer: JsonLines.Framer, span: Span[Byte])(using Frame): Framing[JsonLines.Framer, String] =
        def lines(results: Chunk[Result[LimitExceededException, JsonLines.Line]]): (Chunk[String], Maybe[Result.Error[HttpException]]) =
            val texts                                                     = ChunkBuilder.init[String]
            @tailrec def loop(i: Int): Maybe[Result.Error[HttpException]] =
                if i >= results.size then Absent
                else
                    results(i) match
                        case Result.Success(line) =>
                            discard(texts += line.text)
                            loop(i + 1)
                        case Result.Failure(limit) => Present(tooLarge(limit.actual.toLong, MaxFramedLineBytes))
                        case p: Result.Panic       => Present(p)
            val fault = loop(0)
            (texts.result(), fault)
        end lines
        framer.feed(span) match
            case JsonLines.Framed.Continued(next, results) =>
                val (texts, fault) = lines(results)
                Framing(next, texts, fault)
            case JsonLines.Framed.Halted(results, breach) =>
                val (texts, fault) = lines(results)
                Framing(framer, texts, fault.orElse(Present(tooLarge(breach.actual.toLong, MaxFramedLineBytes))))
        end match
    end feedNdjson

    private def finishNdjson(framer: JsonLines.Framer): (Chunk[String], Maybe[Result.Error[HttpException]]) =
        framer.finishLine match
            case Present(line) => (Chunk(line.text), Absent)
            case Absent        => (Chunk.empty, Absent)

    // SSE (the WHATWG event stream format): lines end with CR, LF or CRLF; a field is the text before the first colon, its value the text
    // after it minus one leading space; a line starting with a colon is a comment; an empty line dispatches the event, which needs at
    // least one data line; the data lines join with LF; an event the stream ends inside is discarded.

    /** The bytes of the line in progress (as the pieces they arrived in, joined once the line ends), whether it ended on a CR whose LF
      * may still follow, whether it is the stream's first line (the one a byte order mark may precede), and the fields of the event in
      * progress. `data` is `Absent` until a data line arrives, which is what decides whether the empty line dispatches an event. `id` is
      * the last event id, stream state that a dispatch keeps: it names every later event until the next id line.
      */
    final private case class SseFraming(
        pieces: Chunk[Span[Byte]],
        pending: Long,
        afterCr: Boolean,
        first: Boolean,
        data: Maybe[Chunk[String]],
        event: Maybe[String],
        id: Maybe[String],
        retry: Maybe[Duration]
    ):
        def withLine(bytes: Chunk[Span[Byte]], size: Long, cr: Boolean): SseFraming = copy(pieces = bytes, pending = size, afterCr = cr)
        def resetEvent: SseFraming                                                  = copy(data = Absent, event = Absent, retry = Absent)
    end SseFraming

    private object SseFraming:
        val empty: SseFraming = SseFraming(Chunk.empty, 0L, false, true, Absent, Absent, Absent, Absent)

    private val Cr: Byte = '\r'.toByte
    private val Lf: Byte = '\n'.toByte

    private val ByteOrderMark: Char = 0xfeff.toChar

    private def feedSse(state: SseFraming, span: Span[Byte])(using Frame): Framing[SseFraming, HttpSseEvent[String]] =
        val events = ChunkBuilder.init[HttpSseEvent[String]]
        // The line in progress starts in the pieces held from earlier spans and continues at `lineStart` of this span; each terminator
        // resolves it, dispatching an event when the line is empty, and the tail of the span is held for the next one.
        @tailrec def scan(i: Int, lineStart: Int, st: SseFraming): Framing[SseFraming, HttpSseEvent[String]] =
            if i >= span.size then
                val tail    = span.slice(lineStart, span.size)
                val held    = st.pending + tail.size
                val pending = if tail.isEmpty then st.pieces else st.pieces.append(tail)
                if held > MaxFramedLineBytes then Framing(st, events.result(), Present(tooLarge(held, MaxFramedLineBytes)))
                else Framing(st.withLine(pending, held, st.afterCr), events.result(), Absent)
            else
                val b = span(i)
                if b == Lf && st.afterCr && i == lineStart && st.pieces.isEmpty then
                    // the LF of a CRLF whose CR ended the previous span
                    scan(i + 1, i + 1, st.copy(afterCr = false))
                else if b == Lf || b == Cr then
                    val lineBytes = if st.pieces.isEmpty then span.slice(lineStart, i) else joinLine(st.pieces, span.slice(lineStart, i))
                    val size      = st.pending + (i - lineStart)
                    if size > MaxFramedLineBytes then Framing(st, events.result(), Present(tooLarge(size, MaxFramedLineBytes)))
                    else
                        val text = new String(lineBytes.toArrayUnsafe, utf8)
                        // one byte order mark is skipped before the first line; a second one is part of the field name
                        val line = if st.first && text.nonEmpty && text.charAt(0) == ByteOrderMark then text.substring(1) else text
                        val next = sseLine(st.withLine(Chunk.empty, 0L, b == Cr).copy(first = false), line)
                        next._2.foreach(event => discard(events += event))
                        val after = if b == Cr && i + 1 < span.size && span(i + 1) == Lf then i + 2 else i + 1
                        scan(after, after, if after == i + 2 then next._1.copy(afterCr = false) else next._1)
                    end if
                else scan(i + 1, lineStart, if st.afterCr then st.copy(afterCr = false) else st)
                end if
        scan(0, 0, state)
    end feedSse

    private def joinLine(pieces: Chunk[Span[Byte]], tail: Span[Byte]): Span[Byte] =
        val size = pieces.foldLeft(tail.size)((n, p) => n + p.size)
        val out  = new Array[Byte](size)
        val at   = pieces.foldLeft(0) { (at, p) =>
            discard(p.copyToArray(out, at))
            at + p.size
        }
        discard(tail.copyToArray(out, at))
        Span.fromUnsafe(out)
    end joinLine

    /** Applies one line to the event in progress, dispatching it when the line is empty and it has data. */
    private def sseLine(st: SseFraming, line: String): (SseFraming, Maybe[HttpSseEvent[String]]) =
        if line.isEmpty then
            st.data match
                case Present(lines) => (st.resetEvent, Present(HttpSseEvent(lines.mkString("\n"), st.event, st.id, st.retry)))
                case Absent         => (st.resetEvent, Absent)
        else if line.charAt(0) == ':' then (st, Absent)
        else
            val colon = line.indexOf(':')
            val field = if colon < 0 then line else line.substring(0, colon)
            val value =
                if colon < 0 then ""
                else if colon + 1 < line.length && line.charAt(colon + 1) == ' ' then line.substring(colon + 2)
                else line.substring(colon + 1)
            val next = field match
                case "data"  => st.copy(data = Present(st.data.getOrElse(Chunk.empty).append(value)))
                case "event" => st.copy(event = Present(value))
                case "id"    => if value.indexOf('\u0000') < 0 then st.copy(id = Present(value)) else st
                case "retry" =>
                    // digits only, and few enough to be a millisecond count a Long holds (the spec ignores any other value)
                    if value.nonEmpty && value.length <= 18 && value.forall(c => c >= '0' && c <= '9') then
                        st.copy(retry = Present(Duration.fromNanos(value.toLong * 1000000L)))
                    else st
                case _ => st
            (next, Absent)
        end if
    end sseLine

    private def finishSse(state: SseFraming): (Chunk[HttpSseEvent[String]], Maybe[Result.Error[HttpException]]) = (Chunk.empty, Absent)

    // Multipart, with the delimiter rule of kyo-mime's Multipart, which the buffered reader applies too: a delimiter is `--boundary` at
    // the start of a line, the start of the body or right after an LF, with or without a CR before it; a part runs from the line end of
    // its delimiter line to that line end before the next delimiter, which belongs to the delimiter. The bytes of a part are never
    // decoded, so binary content passes unchanged. The preamble before the first delimiter and everything after the close delimiter
    // are dropped.

    /** Where the framing is (`Preamble` before the first delimiter, `BoundaryLine` after a delimiter, awaiting its LF or the `--` of the
      * close, `Section` inside a part, `Done` after the close delimiter), the bytes held for the phase (the tail of the preamble or the
      * boundary line so far), the section's bytes as pieces, and the last bytes of the section, which the search for a delimiter that
      * straddles spans reads across.
      */
    final private case class MultipartFraming(
        phase: MultipartFraming.Phase,
        atStart: Boolean,
        held: Span[Byte],
        pieces: Chunk[Span[Byte]],
        size: Long,
        overlap: Span[Byte]
    )

    private object MultipartFraming:
        enum Phase derives CanEqual:
            case Preamble, BoundaryLine, Section, Done
        val empty: MultipartFraming = MultipartFraming(Phase.Preamble, true, Span.empty[Byte], Chunk.empty, 0L, Span.empty[Byte])
    end MultipartFraming

    private def feedMultipart(delimiter: Array[Byte], maxPartSize: Int, state: MultipartFraming, span: Span[Byte])(using
        Frame
    ): Framing[MultipartFraming, HttpRequest.Part] =
        import MultipartFraming.Phase
        val lfDelimiter                                                = '\n'.toByte +: delimiter
        val parts                                                      = ChunkBuilder.init[HttpRequest.Part]
        def startsWith(buf: Array[Byte], prefix: Array[Byte]): Boolean =
            buf.length >= prefix.length && indexOfBytes(buf, 0, prefix.length, prefix) == 0
        // `rest` is what this span left unconsumed, prefixed with the bytes held from earlier spans when the phase reads across them.
        @tailrec def step(st: MultipartFraming, rest: Span[Byte]): Framing[MultipartFraming, HttpRequest.Part] =
            st.phase match
                case Phase.Done     => Framing(st, parts.result(), Absent)
                case Phase.Preamble =>
                    val buf = (st.held.toArrayUnsafe ++ rest.toArrayUnsafe)
                    val at  =
                        if st.atStart && startsWith(buf, delimiter) then 0
                        else
                            val i = indexOfBytes(buf, 0, buf.length, lfDelimiter)
                            if i < 0 then -1 else i + 1
                    if at < 0 then
                        val keep = math.min(buf.length, lfDelimiter.length - 1)
                        val held = Span.fromUnsafe(java.util.Arrays.copyOfRange(buf, buf.length - keep, buf.length))
                        Framing(st.copy(atStart = st.atStart && buf.length < delimiter.length, held = held), parts.result(), Absent)
                    else
                        val after = at + delimiter.length
                        step(
                            st.copy(phase = Phase.BoundaryLine, atStart = false, held = Span.empty[Byte]),
                            Span.fromUnsafe(java.util.Arrays.copyOfRange(buf, after, buf.length))
                        )
                    end if
                case Phase.BoundaryLine =>
                    val buf = st.held.toArrayUnsafe ++ rest.toArrayUnsafe
                    if buf.length >= 2 && buf(0) == '-' && buf(1) == '-' then Framing(st.copy(phase = Phase.Done), parts.result(), Absent)
                    else
                        val lf = buf.indexOf('\n'.toByte)
                        if lf < 0 then
                            if buf.length > MaxFramedLineBytes then
                                Framing(st, parts.result(), Present(tooLarge(buf.length.toLong, MaxFramedLineBytes)))
                            else Framing(st.copy(held = Span.fromUnsafe(buf)), parts.result(), Absent)
                        else
                            step(
                                st.copy(
                                    phase = Phase.Section,
                                    held = Span.empty[Byte],
                                    pieces = Chunk.empty,
                                    size = 0L,
                                    // the delimiter line's LF, not a section byte, so a delimiter at the section's start is found
                                    overlap = LineStart
                                ),
                                Span.fromUnsafe(java.util.Arrays.copyOfRange(buf, lf + 1, buf.length))
                            )
                        end if
                    end if
                case Phase.Section =>
                    // the delimiter may start inside the overlap, the section's last bytes, so the search runs over overlap and span
                    val probe = st.overlap.toArrayUnsafe ++ rest.toArrayUnsafe
                    val at    = indexOfBytes(probe, 0, probe.length, lfDelimiter)
                    // the overlap keeps a whole delimiter's length, so a delimiter found in it starts past its first byte and the
                    // byte before its LF, the CR it may own, is in the probe; the one exception is the delimiter line's own LF that
                    // opens a section, which has no section byte before it
                    def crAt(i: Int): Boolean = i >= 0 && i < probe.length && probe(i) == '\r'
                    if at < 0 then
                        val size = st.size + rest.size
                        val keep = math.min(probe.length, lfDelimiter.length)
                        // the bytes at the end of the probe that begin the delimiter, a CR before them included, are not the part's
                        // until the next span says so, and the bound counts the bytes known to be the part's
                        val prefix = delimiterPrefixLength(probe, lfDelimiter, lfDelimiter.length - 1)
                        val held   = if crAt(probe.length - prefix - 1) then prefix + 1 else prefix
                        val known  = size - held
                        if known > maxPartSize then Framing(st, parts.result(), Present(tooLarge(known, maxPartSize)))
                        else
                            val overlap = Span.fromUnsafe(java.util.Arrays.copyOfRange(probe, probe.length - keep, probe.length))
                            val pieces  = if rest.isEmpty then st.pieces else st.pieces.append(rest)
                            Framing(st.copy(pieces = pieces, size = size, overlap = overlap), parts.result(), Absent)
                        end if
                    else if st.size + (at - st.overlap.size) - (if crAt(at - 1) then 1 else 0) > maxPartSize then
                        Framing(
                            st,
                            parts.result(),
                            Present(tooLarge(st.size + (at - st.overlap.size) - (if crAt(at - 1) then 1 else 0), maxPartSize))
                        )
                    else
                        // the section ends `at` bytes into the probe: the part is the pieces plus the span's prefix before the
                        // delimiter, minus the overlap bytes the delimiter consumed when it started inside them
                        val inSpan  = at - st.overlap.size
                        val section =
                            if inSpan >= 0 then joinLine(st.pieces, rest.slice(0, inSpan))
                            else joinLine(st.pieces, Span.empty[Byte]).slice(0, math.max(0L, st.size + inSpan).toInt)
                        // the CR of a CRLF before the delimiter is the delimiter's, as its LF is
                        val end = if section.size > 0 && section(section.size - 1) == '\r' then section.size - 1 else section.size
                        parseMultipartSectionBytes(section.toArrayUnsafe, 0, end) match
                            case Result.Success(part) =>
                                part.foreach(p => discard(parts += p))
                                val after = inSpan + lfDelimiter.length
                                step(
                                    st.copy(
                                        phase = Phase.BoundaryLine,
                                        held = Span.empty[Byte],
                                        pieces = Chunk.empty,
                                        size = 0L,
                                        overlap = Span.empty[Byte]
                                    ),
                                    rest.slice(after, rest.size)
                                )
                            case Result.Failure(e) => Framing(st.copy(phase = Phase.Done), parts.result(), Present(Result.Failure(e)))
                            case p: Result.Panic   => Framing(st.copy(phase = Phase.Done), parts.result(), Present(p))
                        end match
                    end if
        step(state, span)
    end feedMultipart

    private val LineStart: Span[Byte] = Span.fromUnsafe(Array[Byte]('\n'))

    /** The length of the longest suffix of `buf`, at most `max` bytes, that is a prefix of `delimiter`: the bytes that may begin a
      * delimiter the next span completes.
      */
    private def delimiterPrefixLength(buf: Array[Byte], delimiter: Array[Byte], max: Int): Int =
        def suffixMatches(k: Int): Boolean =
            @tailrec def loop(j: Int): Boolean = j >= k || (buf(buf.length - k + j) == delimiter(j) && loop(j + 1))
            loop(0)
        @tailrec def longest(k: Int): Int =
            if k <= 0 then 0
            else if suffixMatches(k) then k
            else longest(k - 1)
        longest(math.min(max, math.min(buf.length, delimiter.length - 1)))
    end delimiterPrefixLength

    /** A body that ends inside a part, with no close delimiter, still yields that part to the body's end, as the buffered parser does,
      * bounded like one a delimiter ends: the bytes a span held back as a possible delimiter start are part bytes after all.
      */
    private def finishMultipart(maxPartSize: Int, state: MultipartFraming)(using
        Frame
    ): (Chunk[HttpRequest.Part], Maybe[Result.Error[HttpException]]) =
        if state.phase == MultipartFraming.Phase.Section && state.size > 0 then
            if state.size > maxPartSize then (Chunk.empty, Present(tooLarge(state.size, maxPartSize)))
            else
                val section = joinLine(state.pieces, Span.empty[Byte])
                parseMultipartSectionBytes(section.toArrayUnsafe, 0, section.size) match
                    case Result.Success(part) => (part.map(Chunk(_)).getOrElse(Chunk.empty), Absent)
                    case Result.Failure(e)    => (Chunk.empty, Present(Result.Failure(e)))
                    case p: Result.Panic      => (Chunk.empty, Present(p))
                end match
        else (Chunk.empty, Absent)

    /** Parses a multipart byte stream into a stream of HttpRequest.Part. */
    private def parseMultipartStream(
        stream: Stream[Span[Byte], Async & Abort[HttpException]],
        headers: HttpHeaders,
        maxPartSize: Int
    )(using Frame): Stream[HttpRequest.Part, Async & Abort[HttpException]] =
        multipartBoundaryFromHeaders(headers) match
            case Absent =>
                Stream.empty[HttpRequest.Part]
            case Present(b) =>
                val delimiter = s"--$b".getBytes(StandardCharsets.US_ASCII)
                framed(
                    stream,
                    MultipartFraming.empty,
                    (st, span) => feedMultipart(delimiter, maxPartSize, st, span),
                    finishMultipart(maxPartSize, _),
                    Result.succeed
                )(using summon[Frame], Tag[Emit[Chunk[HttpRequest.Part]]])
        end match
    end parseMultipartStream

    /** The `name`, `filename` and `Content-Type` of a part, from the header block before its empty line. `Content-Disposition` is read
      * as the HTML standard's form-data parser reads it (`Disposition.parseFormData`: `form-data; name="..."[; filename="..."]`, no quoted
      * pairs, `%22`, `%0D` and `%0A` decoded), the encoding browsers and this module's writer use; a value in any other shape leaves the
      * part nameless, and the body's other parts are unaffected. Header names fold ASCII case; any other header is ignored. A header line
      * ends only in CRLF: a CR or LF anywhere else fails the body, since a recipient that ends lines at a bare LF would read a header this
      * reader does not see.
      */
    private def partHeaders(headerBlock: String)(using Frame): Result[HttpException, (String, Maybe[String], Maybe[String])] =
        val lines = headerBlock.split("\r\n", -1)
        @tailrec def loop(
            i: Int,
            name: String,
            filename: Maybe[String],
            contentType: Maybe[String]
        ): Result[HttpException, (String, Maybe[String], Maybe[String])] =
            if i >= lines.length then Result.succeed((name, filename, contentType))
            else
                val line  = lines(i)
                val colon = line.indexOf(':')
                if line.indexOf('\r') >= 0 || line.indexOf('\n') >= 0 then
                    Result.fail(HttpMalformedBodyException("a multipart part header holds a CR or LF outside a CRLF"))
                else if colon < 0 then loop(i + 1, name, filename, contentType)
                else
                    val header = line.substring(0, colon).trim
                    val value  = line.substring(colon + 1).trim
                    if Ascii.equalsIgnoreCase(header, "Content-Disposition") then
                        Disposition.parseFormData(value) match
                            case Result.Success(disposition) =>
                                loop(i + 1, disposition.name.getOrElse(name), disposition.filename.orElse(filename), contentType)
                            case _ => loop(i + 1, name, filename, contentType)
                    else if Ascii.equalsIgnoreCase(header, "Content-Type") then loop(i + 1, name, filename, Present(value))
                    else loop(i + 1, name, filename, contentType)
                    end if
                end if
        loop(0, "", Absent, Absent)
    end partHeaders

    /** A part from the bytes between its delimiter line and the line end before the next delimiter: the header block up to the empty
      * line, then the data, kept as bytes. An empty section, or one with no header block, names no part and is dropped; a section whose
      * header block never ends in CRLF CRLF fails the body.
      */
    private def parseMultipartSectionBytes(section: Array[Byte], offset: Int, length: Int)(using
        Frame
    ): Result[HttpException, Maybe[HttpRequest.Part]] =
        if length == 0 || (length >= 2 && section(offset) == '\r' && section(offset + 1) == '\n') then Result.succeed(Absent)
        else
            val sepIdx = indexOfBytes(section, offset, length, CrNlCrNl)
            if sepIdx < 0 then Result.fail(HttpMalformedBodyException("a multipart part's header block does not end in CRLF CRLF"))
            else
                // UTF-8: a browser sends a raw file name in UTF-8 (RFC 7578 section 5.1.3), as does this module's writer.
                val headerStr = new String(section, offset, sepIdx - offset, StandardCharsets.UTF_8)
                val bodyData  = java.util.Arrays.copyOfRange(section, sepIdx + 4, offset + length)
                partHeaders(headerStr).map { (name, filename, partCt) =>
                    if name.isEmpty then Absent else Present(HttpRequest.Part(name, filename, partCt, Span.fromUnsafe(bodyData)))
                }
            end if
        end if
    end parseMultipartSectionBytes

    private val CrNlCrNl = Array[Byte]('\r', '\n', '\r', '\n')

    /** Find needle in haystack starting at offset within length. Returns absolute index or -1. */
    private def indexOfBytes(haystack: Array[Byte], offset: Int, length: Int, needle: Array[Byte]): Int =
        val end                         = offset + length - needle.length
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
        multipartBoundaryFromHeaders(headers) match
            case Absent =>
                Result.fail(HttpMissingBoundaryException(method, url.toString))
            case Present(boundary) =>
                val boundaryBytes = Span.fromUnsafe(boundary.getBytes(StandardCharsets.US_ASCII))
                val bytes         = body.toArrayUnsafe
                val parts         = ChunkBuilder.init[HttpRequest.Part]

                // One part per delimiter that is not the close delimiter: from the end of its delimiter line to the byte before the
                // next delimiter's own line end (or the body's end when a peer omitted the close delimiter). What is before the first
                // delimiter is the preamble, and a part whose headers name nothing is dropped, both as RFC 2046 section 5.1.1 has it.
                @tailrec def loop(delimiter: Int): Result[HttpException, Seq[HttpRequest.Part]] =
                    if delimiter < 0 || Multipart.isCloseDelimiter(body, boundaryBytes, delimiter) then Result.succeed(parts.result())
                    else
                        val start = Multipart.delimiterLineEnd(body, boundaryBytes, delimiter)
                        val next  = Multipart.findDelimiter(body, boundaryBytes, start)
                        val end   = if next < 0 then body.size else Multipart.partEndBefore(body, next)
                        parseMultipartSectionBytes(bytes, start, math.max(0, end - start)) match
                            case Result.Success(part) =>
                                part.foreach(p => discard(parts += p))
                                loop(next)
                            case Result.Failure(e) => Result.fail(e)
                            case p: Result.Panic   => p
                        end match
                loop(Multipart.findDelimiter(body, boundaryBytes, 0))
        end match
    end parseMultipartBody

    // ==================== Internal: multipart encoding ====================

    private def encodeMultipartParts(parts: Seq[HttpRequest.Part], boundary: String)(using
        Frame
    ): Result[HttpException, Span[Byte]] =
        val out                                                = new java.io.ByteArrayOutputStream
        @tailrec def loop(i: Int): Result[HttpException, Unit] =
            if i >= parts.size then Result.unit
            else
                appendMultipartPartBytes(out, parts(i), boundary) match
                    case Result.Success(_) => loop(i + 1)
                    case failed            => failed
        loop(0).map { _ =>
            out.write(s"--$boundary--\r\n".getBytes(StandardCharsets.US_ASCII))
            Span.fromUnsafe(out.toByteArray)
        }
    end encodeMultipartParts

    private def encodeMultipartPart(part: HttpRequest.Part, boundary: String)(using Frame): Result[HttpException, Span[Byte]] =
        val out = new java.io.ByteArrayOutputStream
        appendMultipartPartBytes(out, part, boundary).map(_ => Span.fromUnsafe(out.toByteArray))

    /** Writes one part through kyo-mime: the Content-Disposition in the HTML standard's form-data encoding (`Style.FormData`: every value
      * quoted, `"`, CR and LF as `%22`, `%0D` and `%0A`, nothing else escaped), and the Content-Type rendered as a media type, a value
      * that is not one, a CR LF that would add a part header or end the header block among them, refused with nothing of the part
      * written. So is a part whose data holds `--boundary` at the start of a line, which a reader would take for a delimiter and split
      * the part at.
      */
    private def appendMultipartPartBytes(out: java.io.ByteArrayOutputStream, part: HttpRequest.Part, boundary: String)(using
        Frame
    ): Result[HttpException, Unit] =
        val parameters  = Chunk("name" -> part.name) ++ part.filename.map(fn => Chunk("filename" -> fn)).getOrElse(Chunk.empty)
        val disposition = Disposition.init("form-data", parameters*).flatMap(_.render(Parameters.Style.FormData)) match
            case Result.Success(rendered) => Result.succeed(rendered)
            case _                        => Result.fail(HttpInvalidFieldException("the Content-Disposition of a multipart part"))
        val contentType: Result[HttpException, Maybe[String]] = part.contentType match
            case Present(ct) =>
                MediaType.parse(ct).flatMap(_.render) match
                    case Result.Success(rendered) => Result.succeed(Present(rendered))
                    case _                        => Result.fail(HttpInvalidFieldException("the Content-Type of a multipart part"))
            case Absent => Result.succeed(Absent)
        val boundaryBytes = Span.fromUnsafe(boundary.getBytes(StandardCharsets.US_ASCII))
        val headers       =
            if Multipart.findDelimiter(part.data, boundaryBytes, 0) >= 0 then
                Result.fail(HttpInvalidFieldException("the multipart boundary, which a part's data holds at the start of a line"))
            else disposition.flatMap(d => contentType.map(ct => (d, ct)))
        headers.map { (disposition, rendered) =>
            val header = new StringBuilder
            discard(header.append("--").append(boundary).append("\r\n"))
            discard(header.append("Content-Disposition: ").append(disposition).append("\r\n"))
            rendered.foreach(ct => discard(header.append("Content-Type: ").append(ct).append("\r\n")))
            discard(header.append("\r\n"))
            // UTF-8, since a file name goes out raw (RFC 7578 section 5.1.3).
            out.write(header.toString.getBytes(StandardCharsets.UTF_8))
            val data = part.data.toArrayUnsafe
            out.write(data, 0, data.length)
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII))
        }
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
        path: String,
        queryParam: Maybe[HttpUrl],
        methodOverride: Maybe[HttpMethod]
    ): HttpRequest[In] =
        val url    = HttpUrl(Absent, "", 0, path, queryParam.flatMap(_.rawQuery))
        val method = methodOverride match
            case Present(m) => m
            case Absent     => route.method
        HttpRequest(method, url, headers, Record(builder.result()))
    end buildRequest

    /** Whether the request's `Content-Type` is the media type `expected`: its `type/subtype` as kyo-mime parses it, compared ASCII
      * case-insensitively; the parameters are not read, and a value that is not a media type is not `expected`. An absent header is
      * accepted.
      */
    private def checkContentType(headers: HttpHeaders, expected: String)(using Frame): Boolean =
        headers.get("Content-Type") match
            case Absent      => true
            case Present(ct) => MediaType.parse(ct).exists(_.baseType == expected)
    end checkContentType

    // ==================== Error response body ====================

    private[kyo] inline def encodeHalt[A](halt: HttpResponse.Halt)(inline f: (HttpStatus, HttpHeaders, Span[Byte]) => A)(using Frame): A =
        val status = halt.response.status
        if status.forbidsContent then
            // Such a response SHOULD NOT include Content-Type or Content-Length either.
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
