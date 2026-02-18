package kyo

import HttpRoute.*
import java.nio.charset.StandardCharsets
import kyo.HttpRequest.Method
import kyo.internal.Content
import kyo.internal.MultipartUtil
import scala.NamedTuple.AnyNamedTuple
import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuilder

sealed abstract class HttpHandler[-S]:
    def route: HttpRoute[?, ?, ?, ?]
    private[kyo] def streamingRequest: Boolean =
        route.request.inputFields.exists:
            case InputField.Body(Content.ByteStream | Content.MultipartStream, _) => true
            case _                                                                => false
    private[kyo] def apply(request: HttpRequest[?]): HttpResponse[?] < (Async & S)
end HttpHandler

object HttpHandler:

    // ==================== Route-based handler ====================

    private[kyo] def fromRoute[
        PathIn <: AnyNamedTuple,
        In <: AnyNamedTuple,
        Out <: AnyNamedTuple,
        Err,
        S
    ](
        r: HttpRoute[PathIn, In, Out, Err]
    )(using
        Frame
    )(
        f: Row[FullInput[PathIn, In]] => OutputValue[Out] < (Abort[Err] & Async & S)
    ): HttpHandler[S] =
        new HttpHandler[S]:
            val route = r
            private[kyo] def apply(request: HttpRequest[?]): HttpResponse[?] < (Async & S) =
                try
                    val pathValues  = extractPath(r.path, request.path)
                    val inputValues = extractInputs(r.request.inputFields, request)
                    val allValues   = concat(pathValues, inputValues, request)
                    val row         = Tuple.fromArray(allValues).asInstanceOf[Row[FullInput[PathIn, In]]]

                    // Standard Kyo pattern: widen Abort[Err] → Abort[Any] for uniform error handling
                    // (same cast used in kyo-combinators/AbortCombinators.scala)
                    Abort.run[Any](
                        f(row).map(output => buildResponse(r.response, output))
                            .asInstanceOf[HttpResponse[?] < (Abort[Any] & Async & S)]
                    ).map {
                        case Result.Success(response) => response
                        case Result.Failure(err) =>
                            matchError(err, r.response.errorMappings)
                                .getOrElse(HttpResponse.serverError(err.toString))
                        case Result.Panic(ex) => HttpResponse.serverError(ex.getMessage)
                    }
                catch
                    case _: MissingAuthException =>
                        HttpResponse(HttpStatus.Unauthorized)
                            .setHeader("WWW-Authenticate", "Basic realm=\"Restricted\"")
                    case _: MissingParamException =>
                        HttpResponse(HttpStatus.BadRequest)
            end apply

    // ==================== Low-level handler ====================

    @nowarn("msg=anonymous")
    def init[A <: AnyNamedTuple, S](
        method: Method,
        path: HttpPath[A]
    )(using
        Frame
    )(
        f: Row[Row.Append[A, "request", HttpRequest[HttpBody.Bytes]]] => HttpResponse[?] < (Async & S)
    ): HttpHandler[S] =
        new HttpHandler[S]:
            val route: HttpRoute[?, ?, ?, ?] = HttpRoute(method, path)
            private[kyo] def apply(request: HttpRequest[?]): HttpResponse[?] < (Async & S) =
                val pathValues = extractPath(path, request.path)
                val allValues  = concat(pathValues, Array.empty, request)
                val row = Tuple.fromArray(allValues)
                    .asInstanceOf[Row[Row.Append[A, "request", HttpRequest[HttpBody.Bytes]]]]
                f(row)
            end apply

    // ==================== Convenience helpers ====================

    def health(path: HttpPath[Row.Empty] = "health")(using Frame): HttpHandler[Any] =
        init(Method.GET, path)(in => HttpResponse.ok("healthy"))

    def const[A <: AnyNamedTuple](method: Method, path: HttpPath[A], status: HttpStatus)(using Frame): HttpHandler[Any] =
        new HttpHandler[Any]:
            val route: HttpRoute[?, ?, ?, ?] = HttpRoute(method, path)
            private[kyo] def apply(request: HttpRequest[?]): HttpResponse[?] < Async =
                HttpResponse(status)

    def const[A <: AnyNamedTuple](method: Method, path: HttpPath[A], response: HttpResponse[?])(using Frame): HttpHandler[Any] =
        new HttpHandler[Any]:
            val route: HttpRoute[?, ?, ?, ?] = HttpRoute(method, path)
            private[kyo] def apply(request: HttpRequest[?]): HttpResponse[?] < Async =
                response

    // ==================== Shortcut methods ====================

    def get[A <: AnyNamedTuple, S](path: HttpPath[A])(using
        Frame
    )(
        f: Row[Row.Append[A, "request", HttpRequest[HttpBody.Bytes]]] => HttpResponse[?] < (Async & S)
    ): HttpHandler[S] = init(Method.GET, path)(f)

    def post[A <: AnyNamedTuple, S](path: HttpPath[A])(using
        Frame
    )(
        f: Row[Row.Append[A, "request", HttpRequest[HttpBody.Bytes]]] => HttpResponse[?] < (Async & S)
    ): HttpHandler[S] = init(Method.POST, path)(f)

    def put[A <: AnyNamedTuple, S](path: HttpPath[A])(using
        Frame
    )(
        f: Row[Row.Append[A, "request", HttpRequest[HttpBody.Bytes]]] => HttpResponse[?] < (Async & S)
    ): HttpHandler[S] = init(Method.PUT, path)(f)

    def patch[A <: AnyNamedTuple, S](path: HttpPath[A])(using
        Frame
    )(
        f: Row[Row.Append[A, "request", HttpRequest[HttpBody.Bytes]]] => HttpResponse[?] < (Async & S)
    ): HttpHandler[S] = init(Method.PATCH, path)(f)

    def delete[A <: AnyNamedTuple, S](path: HttpPath[A])(using
        Frame
    )(
        f: Row[Row.Append[A, "request", HttpRequest[HttpBody.Bytes]]] => HttpResponse[?] < (Async & S)
    ): HttpHandler[S] = init(Method.DELETE, path)(f)

    def head[A <: AnyNamedTuple, S](path: HttpPath[A])(using
        Frame
    )(
        f: Row[Row.Append[A, "request", HttpRequest[HttpBody.Bytes]]] => HttpResponse[?] < (Async & S)
    ): HttpHandler[S] = init(Method.HEAD, path)(f)

    def options[A <: AnyNamedTuple, S](path: HttpPath[A])(using
        Frame
    )(
        f: Row[Row.Append[A, "request", HttpRequest[HttpBody.Bytes]]] => HttpResponse[?] < (Async & S)
    ): HttpHandler[S] = init(Method.OPTIONS, path)(f)

    // ==================== Streaming handlers ====================

    /** Creates a handler that receives a streaming request body. */
    def streamingBody[A <: AnyNamedTuple, S](method: Method, path: HttpPath[A])(using
        Frame
    )(
        f: Row[Row.Append[A, "request", HttpRequest[HttpBody.Streamed]]] => HttpResponse[?] < (Async & S)
    ): HttpHandler[S] =
        new HttpHandler[S]:
            val route: HttpRoute[?, ?, ?, ?] = HttpRoute(method, path)
                .request(_.bodyStream)
            override private[kyo] def streamingRequest: Boolean = true
            private[kyo] def apply(request: HttpRequest[?]): HttpResponse[?] < (Async & S) =
                val pathValues = extractPath(path, request.path)
                val allValues  = concat(pathValues, Array.empty, request)
                val row = Tuple.fromArray(allValues)
                    .asInstanceOf[Row[Row.Append[A, "request", HttpRequest[HttpBody.Streamed]]]]
                f(row)
            end apply

    /** Creates a handler that streams SSE events. */
    def streamSse[V: Schema: Tag](path: String)(using
        Frame,
        Tag[Emit[Chunk[HttpEvent[V]]]]
    )(
        f: HttpRequest[HttpBody.Bytes] => Stream[HttpEvent[V], Async] < Async
    ): HttpHandler[Any] =
        get(path) { in =>
            f(in.request).map(stream => HttpResponse.streamSse(stream))
        }

    /** Creates a handler that streams NDJSON values. */
    def streamNdjson[V: Schema: Tag](path: String)(using
        Frame,
        Tag[Emit[Chunk[V]]]
    )(
        f: HttpRequest[HttpBody.Bytes] => Stream[V, Async] < Async
    ): HttpHandler[Any] =
        get(path) { in =>
            f(in.request).map(stream => HttpResponse.streamNdjson(stream))
        }

    /** Stub handler for OpenAPI spec generation — preserves route metadata but returns a fixed response. */
    private[kyo] def stub(r: HttpRoute[?, ?, ?, ?]): HttpHandler[Any] =
        new HttpHandler[Any]:
            val route: HttpRoute[?, ?, ?, ?] = r
            private[kyo] def apply(request: HttpRequest[?]): HttpResponse[?] < Async =
                HttpResponse.ok

    // ==================== Private: Input Extraction ====================

    private def extractPath(path: HttpPath[?], requestPath: String): Array[Any] =
        path match
            case HttpPath.Literal(_) => Array.empty
            case _ =>
                val segments = HttpPath.parseSegments(requestPath)
                extractPathSegments(path, segments)._1
    end extractPath

    private def extractPathSegments(path: HttpPath[?], segments: List[String]): (Array[Any], List[String]) =
        path match
            case HttpPath.Literal(v) =>
                val skip = HttpPath.countSegments(v)
                (Array.empty, segments.drop(skip))
            case HttpPath.Capture(_, _, codec) =>
                val decoded = java.net.URLDecoder.decode(segments.head.replace("+", "%2B"), "UTF-8")
                (Array(codec.asInstanceOf[HttpCodec[Any]].parse(decoded)), segments.tail)
            case HttpPath.Concat(left, right) =>
                val (leftVals, remaining)   = extractPathSegments(left, segments)
                val (rightVals, remaining2) = extractPathSegments(right, remaining)
                (leftVals ++ rightVals, remaining2)
            case HttpPath.Rest(_) =>
                val remaining = segments.mkString("/")
                (Array(remaining), Nil)
    end extractPathSegments

    private def extractInputs(fields: Seq[InputField], request: HttpRequest[?])(using Frame): Array[Any] =
        val result = ArrayBuilder.make[Any]
        fields.foreach:
            case InputField.Query(name, codec, default, optional, _) =>
                result += extractParam(request.query(name), codec, default, optional, "query", name)

            case InputField.Header(name, codec, default, optional, _) =>
                result += extractParam(request.header(name), codec, default, optional, "header", name)

            case InputField.Cookie(name, codec, default, optional, _) =>
                val raw = request.cookie(name).map(_.value)
                result += extractParam(raw, codec, default, optional, "cookie", name)

            case InputField.Body(content, _) =>
                result += extractBody(content, request)

            case InputField.Auth(scheme) =>
                scheme match
                    case AuthScheme.Bearer =>
                        result += extractBearer(request)
                    case AuthScheme.BasicUsername =>
                        val (user, _) = extractBasicAuth(request)
                        result += user
                    case AuthScheme.BasicPassword =>
                        val (_, pass) = extractBasicAuth(request)
                        result += pass
                    case AuthScheme.ApiKey(name, location) =>
                        result += extractApiKey(request, name, location)
        result.result()
    end extractInputs

    private def extractParam(
        raw: Maybe[String],
        codec: HttpCodec[?],
        default: Maybe[Any],
        optional: Boolean,
        kind: String,
        name: String
    )(using Frame): Any =
        raw match
            case Present(v) =>
                val parsed = codec.asInstanceOf[HttpCodec[Any]].parse(v)
                if optional then Present(parsed) else parsed
            case Absent =>
                default match
                    case Present(d) => if optional then Present(d) else d
                    case Absent => if optional then Absent
                        else throw new MissingParamException(s"Missing required $kind: $name")

    private def extractBody(content: Content.Input, request: HttpRequest[?])(using Frame): Any =
        content match
            case Content.Json(schema) =>
                schema.decode(request.asInstanceOf[HttpRequest[HttpBody.Bytes]].bodyText)
            case Content.Text =>
                request.asInstanceOf[HttpRequest[HttpBody.Bytes]].bodyText
            case Content.Binary =>
                request.asInstanceOf[HttpRequest[HttpBody.Bytes]].bodyBytes
            case Content.ByteStream =>
                request.asInstanceOf[HttpRequest[HttpBody.Streamed]].bodyStream
            case Content.Form(schema) =>
                schema.decode(request.asInstanceOf[HttpRequest[HttpBody.Bytes]].bodyText)
            case Content.Multipart =>
                request.asInstanceOf[HttpRequest[HttpBody.Bytes]].parts.toArrayUnsafe.toSeq
            case Content.MultipartStream =>
                val streamReq = request.asInstanceOf[HttpRequest[HttpBody.Streamed]]
                val boundary = request.contentType match
                    case Present(ct) => MultipartUtil.extractBoundary(ct)
                    case Absent      => Absent
                boundary match
                    case Present(b) =>
                        val decoder = new internal.MultipartStreamDecoder(b)
                        streamReq.bodyStream.mapChunkPure[Span[Byte], HttpRequest.Part] { chunk =>
                            val result = Seq.newBuilder[HttpRequest.Part]
                            chunk.foreach(bytes => result ++= decoder.decode(bytes))
                            result.result()
                        }
                    case Absent =>
                        throw new IllegalArgumentException("Missing multipart boundary")
                end match
            case Content.Ndjson(schema, _) =>
                val streamReq = request.asInstanceOf[HttpRequest[HttpBody.Streamed]]
                val decoder   = new internal.NdjsonDecoder[Any](schema)
                streamReq.bodyStream.mapChunkPure[Span[Byte], Any] { chunk =>
                    val result = Seq.newBuilder[Any]
                    chunk.foreach(bytes => result ++= decoder.decode(bytes))
                    result.result()
                }
    end extractBody

    private def extractBearer(request: HttpRequest[?])(using Frame): String =
        val raw = request.header("Authorization") match
            case Present(v) => v
            case Absent     => throw new MissingAuthException("Authorization")
        if raw.startsWith("Bearer ") then raw.substring(7)
        else throw new InvalidAuthException("Expected Bearer token")
    end extractBearer

    // Cache parsed basic auth per request to avoid double-parsing for username/password
    @volatile private var lastBasicAuth: (HttpRequest[?], (String, String)) = (null, null)

    private def extractBasicAuth(request: HttpRequest[?])(using Frame): (String, String) =
        val cached = lastBasicAuth
        if (cached ne null) && (cached._1 eq request) then return cached._2

        val raw = request.header("Authorization") match
            case Present(v) => v
            case Absent     => throw new MissingAuthException("Authorization")
        if !raw.startsWith("Basic ") then throw new InvalidAuthException("Expected Basic auth")
        val decoded  = new String(java.util.Base64.getDecoder.decode(raw.substring(6)), "UTF-8")
        val colonIdx = decoded.indexOf(':')
        if colonIdx < 0 then throw new InvalidAuthException("Invalid Basic auth format")
        val result = (decoded.substring(0, colonIdx), decoded.substring(colonIdx + 1))
        lastBasicAuth = (request, result)
        result
    end extractBasicAuth

    private def extractApiKey(request: HttpRequest[?], name: String, location: AuthLocation)(using Frame): String =
        location match
            case AuthLocation.Header =>
                request.header(name) match
                    case Present(v) => v
                    case Absent     => throw new MissingAuthException(name)
            case AuthLocation.Query =>
                request.query(name) match
                    case Present(v) => v
                    case Absent     => throw new MissingAuthException(name)
            case AuthLocation.Cookie =>
                request.cookie(name) match
                    case Present(c) => c.value
                    case Absent     => throw new MissingAuthException(name)

    // ==================== Private: Output Serialization ====================

    private def buildResponse(responseDef: ResponseDef[?, ?], output: Any): HttpResponse[?] =
        val fields = responseDef.outputFields
        val status = responseDef.status

        if fields.isEmpty then
            return HttpResponse(status)

        val isSingle                  = fields.size == 1
        var response: HttpResponse[?] = null
        var fieldIdx                  = 0

        fields.foreach:
            case OutputField.Body(content, _) =>
                val value = extractOutput(output, fieldIdx, isSingle)
                response = serializeBody(content, value, status)
                fieldIdx += 1

            case OutputField.Header(name, codec, optional, _) =>
                val value = extractOutput(output, fieldIdx, isSingle)
                val base  = if response ne null then response else HttpResponse(status)
                if !optional then
                    response = base.setHeader(name, codec.asInstanceOf[HttpCodec[Any]].serialize(value))
                else
                    value.asInstanceOf[Maybe[Any]] match
                        case Present(v) =>
                            response = base.setHeader(name, codec.asInstanceOf[HttpCodec[Any]].serialize(v))
                        case Absent =>
                            response = base
                end if
                fieldIdx += 1

            case OutputField.Cookie(name, codec, optional, attrs, _) =>
                val value = extractOutput(output, fieldIdx, isSingle)
                val base  = if response ne null then response else HttpResponse(status)
                if !optional then
                    val serialized = codec.asInstanceOf[HttpCodec[Any]].serialize(value)
                    response = base.addCookie(buildCookie(name, serialized, attrs))
                else
                    value.asInstanceOf[Maybe[Any]] match
                        case Present(v) =>
                            val serialized = codec.asInstanceOf[HttpCodec[Any]].serialize(v)
                            response = base.addCookie(buildCookie(name, serialized, attrs))
                        case Absent =>
                            response = base
                end if
                fieldIdx += 1

        if response eq null then HttpResponse(status)
        else response
    end buildResponse

    private def extractOutput(output: Any, fieldIdx: Int, isSingle: Boolean): Any =
        if isSingle then output
        else output.asInstanceOf[Tuple].productElement(fieldIdx)

    private def serializeBody(content: Content.Output, value: Any, status: HttpStatus): HttpResponse[?] =
        given Frame    = Frame.internal
        val respStatus = status
        content match
            case Content.Json(schema) =>
                HttpResponse.json(respStatus, schema.asInstanceOf[Schema[Any]].encode(value))
            case Content.Text =>
                HttpResponse(respStatus, value.toString)
            case Content.Binary =>
                HttpResponse(respStatus, value.asInstanceOf[Span[Byte]])
            case Content.ByteStream =>
                HttpResponse.stream(value.asInstanceOf[Stream[Span[Byte], Async]], respStatus)
            case Content.Ndjson(schema, _) =>
                val stream = value.asInstanceOf[Stream[Any, Async]]
                val byteStream = stream.map { v =>
                    Span.fromUnsafe((schema.asInstanceOf[Schema[Any]].encode(v) + "\n").getBytes(StandardCharsets.UTF_8))
                }
                HttpResponse.stream(byteStream, respStatus)
                    .setHeader("Content-Type", "application/x-ndjson")
            case Content.Sse(schema, _) =>
                val stream = value.asInstanceOf[Stream[HttpEvent[Any], Async]]
                val byteStream = stream.map { event =>
                    val sb = new StringBuilder
                    event.event.foreach(e => discard(sb.append("event: ").append(e).append('\n')))
                    event.id.foreach(id => discard(sb.append("id: ").append(id).append('\n')))
                    event.retry.foreach(d => discard(sb.append("retry: ").append(d.toMillis).append('\n')))
                    discard(sb.append("data: ").append(schema.asInstanceOf[Schema[Any]].encode(event.data)).append('\n'))
                    discard(sb.append('\n'))
                    Span.fromUnsafe(sb.toString.getBytes(StandardCharsets.UTF_8))
                }
                HttpResponse.stream(byteStream, respStatus)
                    .setHeader("Content-Type", "text/event-stream")
                    .setHeader("Cache-Control", "no-cache")
                    .setHeader("Connection", "keep-alive")
        end match
    end serializeBody

    private def buildCookie(name: String, value: String, attrs: CookieAttributes): HttpResponse.Cookie =
        var cookie = HttpResponse.Cookie(name, value)
        if attrs.httpOnly then cookie = cookie.httpOnly(true)
        if attrs.secure then cookie = cookie.secure(true)
        attrs.sameSite.foreach:
            case SameSite.Strict => cookie = cookie.sameSite(HttpResponse.Cookie.SameSite.Strict)
            case SameSite.Lax    => cookie = cookie.sameSite(HttpResponse.Cookie.SameSite.Lax)
            case SameSite.None   => cookie = cookie.sameSite(HttpResponse.Cookie.SameSite.None)
        attrs.maxAge.foreach(s => cookie = cookie.maxAge(Duration.fromUnits(s.toLong, Duration.Units.Seconds)))
        attrs.domain.foreach(d => cookie = cookie.domain(d))
        attrs.path.foreach(p => cookie = cookie.path(p))
        cookie
    end buildCookie

    // ==================== Private: Error Handling ====================

    private def matchError(
        err: Any,
        mappings: Seq[ErrorMapping]
    ): Maybe[HttpResponse[HttpBody.Bytes]] =
        @tailrec def loop(remaining: Seq[ErrorMapping]): Maybe[HttpResponse[HttpBody.Bytes]] =
            remaining match
                case Seq() => Absent
                case mapping +: tail =>
                    if mapping.tag.accepts(err) then
                        try
                            val json = mapping.schema.asInstanceOf[Schema[Any]].encode(err)
                            Present(
                                HttpResponse(mapping.status, json)
                                    .setHeader("Content-Type", "application/json")
                            )
                        catch
                            case _: Throwable => loop(tail)
                    else loop(tail)
        loop(mappings)
    end matchError

    // ==================== Private: Utilities ====================

    private def concat(pathValues: Array[Any], inputValues: Array[Any], request: Any): Array[Object] =
        val total = pathValues.length + inputValues.length + 1
        val arr   = new Array[Object](total)
        var i     = 0
        while i < pathValues.length do
            arr(i) = pathValues(i).asInstanceOf[Object]
            i += 1
        var j = 0
        while j < inputValues.length do
            arr(i + j) = inputValues(j).asInstanceOf[Object]
            j += 1
        arr(i + j) = request.asInstanceOf[Object]
        arr
    end concat

    // Control-flow exceptions — caught in handler try-catch, never escape
    final private class MissingAuthException(name: String)(using Frame) extends KyoException(name)
    final private class InvalidAuthException(msg: String)(using Frame)  extends KyoException(msg)
    final private class MissingParamException(msg: String)(using Frame) extends KyoException(msg)

end HttpHandler
