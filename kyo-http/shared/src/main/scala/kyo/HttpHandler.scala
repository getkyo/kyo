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

/** Sealed handler that maps an HTTP request to an HTTP response for a given route.
  *
  * Handlers are created either from an `HttpRoute` via `route.handle(f)` (typed, with automatic input extraction and output serialization)
  * or via low-level `HttpHandler.init`/`HttpHandler.get`/etc. (untyped, receiving the raw `HttpRequest`). Convenience methods like
  * `health`, `const`, `streamSse`, and `streamNdjson` cover common patterns.
  *
  * Handlers are passed to `HttpServer.init` for serving. The `HttpRouter` dispatches incoming requests to the correct handler based on
  * method and path. The handler's `route` field is used for routing, OpenAPI spec generation, and streaming detection.
  *
  * @tparam S
  *   the effect type required by the handler function beyond `Async`
  * @see
  *   [[kyo.HttpRoute]]
  * @see
  *   [[kyo.HttpServer]]
  * @see
  *   [[kyo.HttpRouter]]
  * @see
  *   [[kyo.HttpOpenApi]]
  */
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
                val pathValues = extractPath(r.path, request.path)
                Abort.run[HttpError](extractInputs(r.request.inputFields, request)).map {
                    case Result.Success(inputValues) =>
                        val allValues = concat(pathValues, inputValues, request)
                        val row       = Tuple.fromArray(allValues).asInstanceOf[Row[FullInput[PathIn, In]]]
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
                    case Result.Failure(_: HttpError.MissingAuth) | Result.Failure(_: HttpError.InvalidAuth) =>
                        HttpResponse(HttpStatus.Unauthorized)
                            .setHeader("WWW-Authenticate", "Basic realm=\"Restricted\"")
                    case Result.Failure(_: HttpError.MissingParam) =>
                        HttpResponse(HttpStatus.BadRequest)
                    case Result.Failure(err) =>
                        HttpResponse.serverError(err.toString)
                    case Result.Panic(ex) => HttpResponse.serverError(ex.getMessage)
                }
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
                (Array(codec.asInstanceOf[HttpParamCodec[Any]].parse(decoded)), segments.tail)
            case HttpPath.Concat(left, right) =>
                val (leftVals, remaining)   = extractPathSegments(left, segments)
                val (rightVals, remaining2) = extractPathSegments(right, remaining)
                (leftVals ++ rightVals, remaining2)
            case HttpPath.Rest(_) =>
                val remaining = segments.mkString("/")
                (Array(remaining), Nil)
    end extractPathSegments

    private def extractInputs(fields: Seq[InputField], request: HttpRequest[?])(using Frame): Array[Any] < (Sync & Abort[HttpError]) =
        Kyo.foldLeft(fields)(ArrayBuilder.make[Any]) { (result, field) =>
            field match
                case InputField.Query(name, codec, default, optional, _) =>
                    Abort.get(extractParam(request.query(name), codec, default, optional, "query", name)).map { v =>
                        result += v; result
                    }
                case InputField.Header(name, codec, default, optional, _) =>
                    Abort.get(extractParam(request.header(name), codec, default, optional, "header", name)).map { v =>
                        result += v; result
                    }
                case InputField.Cookie(name, codec, default, optional, _) =>
                    val raw = request.cookie(name).map(_.value)
                    Abort.get(extractParam(raw, codec, default, optional, "cookie", name)).map { v =>
                        result += v; result
                    }
                case InputField.FormBody(codec, _) =>
                    val body = request match
                        case r: HttpRequest[HttpBody.Bytes] @unchecked => r.bodyText
                        case _                                         => ""
                    val parsed = codec.parse(body)
                    result += parsed; result
                case InputField.Body(content, _) =>
                    extractBody(content, request).map { body =>
                        result += body
                        result
                    }
                case InputField.Auth(scheme) =>
                    val extracted = scheme match
                        case AuthScheme.Bearer                 => extractBearer(request)
                        case AuthScheme.BasicUsername          => extractBasicAuth(request).map(_(0))
                        case AuthScheme.BasicPassword          => extractBasicAuth(request).map(_(1))
                        case AuthScheme.ApiKey(name, location) => extractApiKey(request, name, location)
                    Abort.get(extracted).map { v =>
                        result += v; result
                    }
        }.map(_.result())
    end extractInputs

    private def extractParam(
        raw: Maybe[String],
        codec: HttpParamCodec[?],
        default: Maybe[Any],
        optional: Boolean,
        kind: String,
        name: String
    )(using Frame): Result[HttpError.MissingParam, Any] =
        raw match
            case Present(v) =>
                val parsed = codec.asInstanceOf[HttpParamCodec[Any]].parse(v)
                Result.succeed(if optional then Present(parsed) else parsed)
            case Absent =>
                default match
                    case Present(d) => Result.succeed(if optional then Present(d) else d)
                    case Absent =>
                        if optional then Result.succeed(Absent)
                        else Result.fail(HttpError.MissingParam(s"Missing required $kind: $name"))

    private def extractBody(content: Content, request: HttpRequest[?])(using Frame): Any < (Sync & Abort[HttpError]) =
        content match
            case c: Content.Input       => Abort.get(c.decodeFrom(request.asInstanceOf[HttpRequest[HttpBody.Bytes]]))
            case c: Content.StreamInput => c.decodeFrom(request.asInstanceOf[HttpRequest[HttpBody.Streamed]])
    end extractBody

    private def extractBearer(request: HttpRequest[?])(using Frame): Result[HttpError, String] =
        request.header("Authorization") match
            case Absent => Result.fail(HttpError.MissingAuth("Authorization"))
            case Present(raw) =>
                if raw.startsWith("Bearer ") then Result.succeed(raw.substring(7))
                else Result.fail(HttpError.InvalidAuth("Expected Bearer token"))

    private def extractBasicAuth(request: HttpRequest[?])(using Frame): Result[HttpError, (String, String)] =
        request.header("Authorization") match
            case Absent => Result.fail(HttpError.MissingAuth("Authorization"))
            case Present(raw) =>
                if !raw.startsWith("Basic ") then Result.fail(HttpError.InvalidAuth("Expected Basic auth"))
                else
                    val decoded  = new String(java.util.Base64.getDecoder.decode(raw.substring(6)), "UTF-8")
                    val colonIdx = decoded.indexOf(':')
                    if colonIdx < 0 then Result.fail(HttpError.InvalidAuth("Invalid Basic auth format"))
                    else Result.succeed((decoded.substring(0, colonIdx), decoded.substring(colonIdx + 1)))

    private def extractApiKey(request: HttpRequest[?], name: String, location: AuthLocation)(using
        Frame
    ): Result[HttpError.MissingAuth, String] =
        val value = location match
            case AuthLocation.Header => request.header(name)
            case AuthLocation.Query  => request.query(name)
            case AuthLocation.Cookie => request.cookie(name).map(_.value)
        value match
            case Present(v) => Result.succeed(v)
            case Absent     => Result.fail(HttpError.MissingAuth(name))
    end extractApiKey

    // ==================== Private: Output Serialization ====================

    private def buildResponse(responseDef: ResponseDef[?, ?], output: Any)(using Frame): HttpResponse[?] =
        val fields = responseDef.outputFields
        val status = responseDef.status

        if fields.isEmpty then HttpResponse(status)
        else
            val isSingle = fields.size == 1

            @tailrec def loop(i: Int, fieldIdx: Int, response: Maybe[HttpResponse[?]]): HttpResponse[?] =
                if i >= fields.size then response.getOrElse(HttpResponse(status))
                else
                    fields(i) match
                        case OutputField.Body(content, _) =>
                            val value = extractOutput(output, fieldIdx, isSingle)
                            loop(i + 1, fieldIdx + 1, Present(serializeBody(content, value, status)))

                        case OutputField.Header(name, codec, optional, _) =>
                            val value = extractOutput(output, fieldIdx, isSingle)
                            val base  = response.getOrElse(HttpResponse(status))
                            val next =
                                if !optional then
                                    base.setHeader(name, codec.asInstanceOf[HttpParamCodec[Any]].serialize(value))
                                else
                                    value.asInstanceOf[Maybe[Any]] match
                                        case Present(v) =>
                                            base.setHeader(name, codec.asInstanceOf[HttpParamCodec[Any]].serialize(v))
                                        case Absent => base
                            loop(i + 1, fieldIdx + 1, Present(next))

                        case OutputField.Cookie(name, codec, optional, attrs, _) =>
                            val value = extractOutput(output, fieldIdx, isSingle)
                            val base  = response.getOrElse(HttpResponse(status))
                            val next =
                                if !optional then
                                    val serialized = codec.asInstanceOf[HttpParamCodec[Any]].serialize(value)
                                    base.addCookie(buildCookie(name, serialized, attrs))
                                else
                                    value.asInstanceOf[Maybe[Any]] match
                                        case Present(v) =>
                                            val serialized = codec.asInstanceOf[HttpParamCodec[Any]].serialize(v)
                                            base.addCookie(buildCookie(name, serialized, attrs))
                                        case Absent => base
                            loop(i + 1, fieldIdx + 1, Present(next))
            loop(0, 0, Absent)
        end if
    end buildResponse

    private def extractOutput(output: Any, fieldIdx: Int, isSingle: Boolean): Any =
        if isSingle then output
        else output.asInstanceOf[Tuple].productElement(fieldIdx)

    private def serializeBody(content: Content.Output, value: Any, status: HttpStatus)(using Frame): HttpResponse[?] =
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
        val c0 = HttpResponse.Cookie(name, value)
        val c1 = if attrs.httpOnly then c0.httpOnly(true) else c0
        val c2 = if attrs.secure then c1.secure(true) else c1
        val c3 = attrs.sameSite match
            case Present(ss) => c2.sameSite(ss)
            case Absent      => c2
        val c4 = attrs.maxAge match
            case Present(s) => c3.maxAge(Duration.fromUnits(s.toLong, Duration.Units.Seconds))
            case Absent     => c3
        val c5 = attrs.domain match
            case Present(d) => c4.domain(d)
            case Absent     => c4
        val c6 = attrs.path match
            case Present(p) => c5.path(p)
            case Absent     => c5
        c6
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
        java.lang.System.arraycopy(pathValues.asInstanceOf[Array[Object]], 0, arr, 0, pathValues.length)
        java.lang.System.arraycopy(inputValues.asInstanceOf[Array[Object]], 0, arr, pathValues.length, inputValues.length)
        arr(total - 1) = request.asInstanceOf[Object]
        arr
    end concat

    // Control-flow exceptions — caught in handler try-catch, never escape

end HttpHandler
