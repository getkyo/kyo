package kyo

import HttpResponse.Status
import kyo.HttpRequest.Method
import kyo.internal.Combine
import kyo.internal.MultipartUtil
import scala.annotation.nowarn
import scala.annotation.tailrec

/** Server-side request handler that connects a route definition to application logic.
  *
  * Each handler declares a route (method + path + schemas) and a function that processes matched requests. The router dispatches to the
  * matching handler based on method and path.
  *
  * Two handler styles:
  *
  * Route-based handlers via `HttpHandler.init(route)(f)` get auto-deserialized typed inputs and auto-serialized typed outputs with error
  * mapping. The function `f: In => Out` receives the route's accumulated `In` named tuple — all path captures, query, header, and body
  * params combined via `Combine`.
  *
  * Low-level handlers via `HttpHandler.init(method, path)(f)` receive path captures and the raw `HttpRequest` as a named tuple via the
  * `Combine` type class — the request is the last field named `request`. With no captures the function takes `(request: HttpRequest[...])`;
  * with captures all fields are combined into a single named tuple ending with the request field.
  *
  *   - Route-based handlers with automatic JSON serialization/deserialization
  *   - Low-level handlers with named tuple path captures + request via `Combine`
  *   - Per-method convenience factories (`get`, `post`, `put`, `patch`, `delete`, `head`, `options`)
  *   - Streaming request body support via `streamingBody`
  *   - SSE and NDJSON streaming response handlers
  *   - Typed error responses mapped by status code via route error schemas
  *   - Health check and constant response helpers
  *
  * IMPORTANT: In route-based handlers, `Abort[Err]` is caught internally and mapped to HTTP error responses using the route's error
  * schemas. Unmatched errors and `Panic` exceptions become 500 responses.
  *
  * IMPORTANT: Handler effects beyond `Async` (the `S` type parameter) must be handled inside the handler function itself — the server does
  * not handle them.
  *
  * @tparam S
  *   Additional effects required by the handler function beyond `Async`
  *
  * @see
  *   [[kyo.HttpRoute]]
  * @see
  *   [[kyo.HttpRequest]]
  * @see
  *   [[HttpResponse]]
  * @see
  *   [[kyo.HttpServer]]
  * @see
  *   [[kyo.HttpPath]]
  * @see
  *   [[kyo.HttpFilter]]
  */
sealed abstract class HttpHandler[-S]:
    def route: HttpRoute[?, ?, ?]
    private[kyo] def streamingRequest: Boolean = false
    private[kyo] def apply(request: HttpRequest[?]): HttpResponse[?] < (Async & S)
end HttpHandler

object HttpHandler:

    /** Creates a route-based handler. `f: In => Out` receives the route's accumulated `In` named tuple — all path captures, query, header,
      * and body params combined via `Combine`. Output is serialized to JSON. Errors matching route error schemas get the corresponding
      * status code; unmatched errors become 500.
      */
    @nowarn("msg=anonymous")
    inline def init[In, Out, Err, S](r: HttpRoute[In, Out, Err])(inline f: In => Out < (Abort[Err] & Async & S))(using
        frame: Frame
    ): HttpHandler[S] =
        new HttpHandler[S]:
            val route: HttpRoute[?, ?, ?] = r
            private[kyo] def apply(request: HttpRequest[?]): HttpResponse[?] < (Async & S) =
                try
                    val req         = request.asInstanceOf[HttpRequest[HttpBody.Bytes]]
                    val pathInput   = extractPathParams(r.path, req.path)
                    val queryInput  = extractQueryParams(r.queryParams, req)
                    val headerInput = extractHeaderParams(r.headerParams, req)
                    val cookieInput = extractCookieParams(r.cookieParams, req)
                    val bodyInput =
                        if r.multipartInput then
                            Tuple1(req.parts.toArrayUnsafe.toSeq)
                        else
                            r.inputSchema match
                                case Present(schema) =>
                                    Tuple1(schema.asInstanceOf[Schema[Any]].decode(req.bodyText))
                                case Absent =>
                                    EmptyTuple
                    val fullInput = combineAllInputs(pathInput, queryInput, headerInput, cookieInput, bodyInput, r)
                    val computation = f(fullInput.asInstanceOf[In]).map { output =>
                        r.outputSchema match
                            case Present(s) =>
                                val json = s.asInstanceOf[Schema[Out]].encode(output)
                                HttpResponse.json(json)
                            case Absent =>
                                HttpResponse.ok
                    }
                    computation.asInstanceOf[HttpResponse[?] < (Abort[Any] & Async & S)]
                        .handle(Abort.recover[Any](
                            err =>
                                findErrorResponse(err, r.errorSchemas)
                                    .getOrElse(HttpResponse.serverError(err.toString)),
                            ex => HttpResponse.serverError(ex.getMessage)
                        ))
                catch
                    case _: MissingAuthException | _: InvalidAuthException =>
                        HttpResponse(HttpResponse.Status.Unauthorized)
                            .setHeader("WWW-Authenticate", "Basic realm=\"Restricted\"")
                    case _: MissingParamException =>
                        HttpResponse(HttpResponse.Status.BadRequest)
            end apply

    /** Creates a low-level handler. The function receives path captures and the request as a named tuple via `Combine[A, (request:
      * HttpRequest)]`. No captures -> just `(request: HttpRequest[...])`; with captures -> named tuple with all fields.
      */
    @nowarn("msg=anonymous")
    inline def init[A, S](
        method: Method,
        path: HttpPath[A]
    )(using
        c: Combine[A, (request: HttpRequest[HttpBody.Bytes])],
        frame: Frame
    )(
        inline f: c.Out => HttpResponse[?] < (Async & S)
    ): HttpHandler[S] =
        new HttpHandler[S]:
            val route: HttpRoute[?, ?, ?] = HttpRoute(method, path.asInstanceOf[HttpPath[Any]], Status.OK, Absent, Absent)
            private[kyo] def apply(request: HttpRequest[?]): HttpResponse[?] < (Async & S) =
                val req       = request.asInstanceOf[HttpRequest[HttpBody.Bytes]]
                val pathInput = extractPathParams(path.asInstanceOf[HttpPath[Any]], req.path)
                val fullInput = combineInputs(pathInput, Tuple1(req))
                f(fullInput.asInstanceOf[c.Out])
            end apply

    /** Creates a GET handler that returns "healthy". */
    def health(path: HttpPath[EmptyTuple] = "/health")(using Frame): HttpHandler[Any] =
        init(Method.GET, path)(_ => HttpResponse.ok("healthy"))

    /** Creates a handler that always returns a fixed status or response, ignoring the request. */
    def const[A](method: Method, path: HttpPath[A], status: Status)(using Frame): HttpHandler[Any] =
        new HttpHandler[Any]:
            val route: HttpRoute[?, ?, ?] = HttpRoute(method, path.asInstanceOf[HttpPath[Any]], Status.OK, Absent, Absent)
            private[kyo] def apply(request: HttpRequest[?]): HttpResponse[?] < Async =
                HttpResponse(status)

    def const[A](method: Method, path: HttpPath[A], response: HttpResponse[?])(using Frame): HttpHandler[Any] =
        new HttpHandler[Any]:
            val route: HttpRoute[?, ?, ?] = HttpRoute(method, path.asInstanceOf[HttpPath[Any]], Status.OK, Absent, Absent)
            private[kyo] def apply(request: HttpRequest[?]): HttpResponse[?] < Async =
                response

    inline def get[A, S](path: HttpPath[A])(using
        c: Combine[A, (request: HttpRequest[HttpBody.Bytes])],
        frame: Frame
    )(
        inline f: c.Out => HttpResponse[?] < (Async & S)
    ): HttpHandler[S] =
        init(Method.GET, path)(f)

    inline def post[A, S](path: HttpPath[A])(using
        c: Combine[A, (request: HttpRequest[HttpBody.Bytes])],
        frame: Frame
    )(
        inline f: c.Out => HttpResponse[?] < (Async & S)
    ): HttpHandler[S] =
        init(Method.POST, path)(f)

    inline def put[A, S](path: HttpPath[A])(using
        c: Combine[A, (request: HttpRequest[HttpBody.Bytes])],
        frame: Frame
    )(
        inline f: c.Out => HttpResponse[?] < (Async & S)
    ): HttpHandler[S] =
        init(Method.PUT, path)(f)

    inline def patch[A, S](path: HttpPath[A])(using
        c: Combine[A, (request: HttpRequest[HttpBody.Bytes])],
        frame: Frame
    )(
        inline f: c.Out => HttpResponse[?] < (Async & S)
    ): HttpHandler[S] =
        init(Method.PATCH, path)(f)

    inline def delete[A, S](path: HttpPath[A])(using
        c: Combine[A, (request: HttpRequest[HttpBody.Bytes])],
        frame: Frame
    )(
        inline f: c.Out => HttpResponse[?] < (Async & S)
    ): HttpHandler[S] =
        init(Method.DELETE, path)(f)

    inline def head[A, S](path: HttpPath[A])(using
        c: Combine[A, (request: HttpRequest[HttpBody.Bytes])],
        frame: Frame
    )(
        inline f: c.Out => HttpResponse[?] < (Async & S)
    ): HttpHandler[S] =
        init(Method.HEAD, path)(f)

    inline def options[A, S](path: HttpPath[A])(using
        c: Combine[A, (request: HttpRequest[HttpBody.Bytes])],
        frame: Frame
    )(
        inline f: c.Out => HttpResponse[?] < (Async & S)
    ): HttpHandler[S] =
        init(Method.OPTIONS, path)(f)

    // --- Streaming request body ---

    @nowarn("msg=anonymous")
    inline def streamingBody[A, S](
        method: Method,
        path: HttpPath[A]
    )(using
        c: Combine[A, (request: HttpRequest[HttpBody.Streamed])],
        frame: Frame
    )(
        inline f: c.Out => HttpResponse[?] < (Async & S)
    ): HttpHandler[S] =
        new HttpHandler[S]:
            val route: HttpRoute[?, ?, ?] = HttpRoute(method, path.asInstanceOf[HttpPath[Any]], Status.OK, Absent, Absent)
            override private[kyo] def streamingRequest: Boolean = true
            private[kyo] def apply(request: HttpRequest[?]): HttpResponse[?] < (Async & S) =
                val pathInput = extractPathParams(path.asInstanceOf[HttpPath[Any]], request.path)
                val fullInput = combineInputs(pathInput, Tuple1(request.asInstanceOf[HttpRequest[HttpBody.Streamed]]))
                f(fullInput.asInstanceOf[c.Out])
            end apply
        end new
    end streamingBody

    // --- Streaming multipart ---

    @nowarn("msg=anonymous")
    inline def streamingMultipart[A, S](
        method: Method,
        path: HttpPath[A]
    )(using
        c: Combine[A, (parts: Stream[HttpRequest.Part, Async])],
        frame: Frame
    )(
        inline f: c.Out => HttpResponse[?] < (Async & S)
    ): HttpHandler[S] =
        new HttpHandler[S]:
            val route: HttpRoute[?, ?, ?] = HttpRoute(method, path.asInstanceOf[HttpPath[Any]], Status.OK, Absent, Absent)
            override private[kyo] def streamingRequest: Boolean = true
            private[kyo] def apply(request: HttpRequest[?]): HttpResponse[?] < (Async & S) =
                val pathInput = extractPathParams(path.asInstanceOf[HttpPath[Any]], request.path)
                val boundary = request.contentType match
                    case Present(ct) => extractBoundary(ct)
                    case Absent      => Absent
                boundary match
                    case Present(b) =>
                        val streamReq = request.asInstanceOf[HttpRequest[HttpBody.Streamed]]
                        val decoder   = new internal.MultipartStreamDecoder(b)
                        val partStream: Stream[HttpRequest.Part, Async] =
                            streamReq.bodyStream.mapChunkPure[Span[Byte], HttpRequest.Part] { chunk =>
                                val result = Seq.newBuilder[HttpRequest.Part]
                                chunk.foreach(bytes => result ++= decoder.decode(bytes))
                                result.result()
                            }
                        val fullInput = combineInputs(pathInput, Tuple1(partStream))
                        f(fullInput.asInstanceOf[c.Out])
                    case Absent =>
                        HttpResponse.badRequest("Missing or invalid multipart boundary")
                end match
            end apply
        end new
    end streamingMultipart

    // --- SSE streaming ---

    inline def streamSse[A, V: Schema: Tag, S](
        path: HttpPath[A]
    )(using
        c: Combine[A, (request: HttpRequest[HttpBody.Bytes])],
        frame: Frame
    )(
        inline f: c.Out => Stream[HttpEvent[V], Async & S]
    ): HttpHandler[S] =
        streamSse(Method.GET, path)(f)

    @nowarn("msg=anonymous")
    inline def streamSse[A, V: Schema: Tag, S](
        method: Method,
        path: HttpPath[A]
    )(using
        c: Combine[A, (request: HttpRequest[HttpBody.Bytes])],
        frame: Frame
    )(
        inline f: c.Out => Stream[HttpEvent[V], Async & S]
    ): HttpHandler[S] =
        val schema = Schema[V]
        new HttpHandler[S]:
            val route = HttpRoute(method, path.asInstanceOf[HttpPath[Any]], Status.OK, Absent, Absent)
            private[kyo] def apply(request: HttpRequest[?]) =
                val req       = request.asInstanceOf[HttpRequest[HttpBody.Bytes]]
                val pathInput = extractPathParams(path.asInstanceOf[HttpPath[Any]], req.path)
                val fullInput = combineInputs(pathInput, Tuple1(req))
                val stream    = f(fullInput.asInstanceOf[c.Out])
                HttpResponse.streamSse(stream.asInstanceOf[Stream[HttpEvent[Any], Async]])(using schema.asInstanceOf[Schema[Any]])
            end apply
        end new
    end streamSse

    // --- NDJSON streaming ---

    inline def streamNdjson[A, V: Schema: Tag, S](
        path: HttpPath[A]
    )(using
        c: Combine[A, (request: HttpRequest[HttpBody.Bytes])],
        frame: Frame
    )(
        inline f: c.Out => Stream[V, Async & S]
    ): HttpHandler[S] =
        streamNdjson(Method.GET, path)(f)

    @nowarn("msg=anonymous")
    inline def streamNdjson[A, V: Schema: Tag, S](
        method: Method,
        path: HttpPath[A]
    )(using
        c: Combine[A, (request: HttpRequest[HttpBody.Bytes])],
        frame: Frame
    )(
        inline f: c.Out => Stream[V, Async & S]
    ): HttpHandler[S] =
        val schema = Schema[V]
        new HttpHandler[S]:
            val route = HttpRoute(method, path.asInstanceOf[HttpPath[Any]], Status.OK, Absent, Absent)
            private[kyo] def apply(request: HttpRequest[?]) =
                val req       = request.asInstanceOf[HttpRequest[HttpBody.Bytes]]
                val pathInput = extractPathParams(path.asInstanceOf[HttpPath[Any]], req.path)
                val fullInput = combineInputs(pathInput, Tuple1(req))
                val stream    = f(fullInput.asInstanceOf[c.Out])
                HttpResponse.streamNdjson(stream.asInstanceOf[Stream[Any, Async]])(using schema.asInstanceOf[Schema[Any]])
            end apply
        end new
    end streamNdjson

    /** Creates a stub handler that preserves route metadata but returns a fixed response. For OpenAPI spec generation only. */
    private[kyo] def stub(r: HttpRoute[?, ?, ?]): HttpHandler[Any] =
        new HttpHandler[Any]:
            val route: HttpRoute[?, ?, ?] = r
            private[kyo] def apply(request: HttpRequest[?]): HttpResponse[?] < Async =
                HttpResponse.ok

    // --- Private implementation ---

    // Try to encode an error using registered error schemas and return appropriate HTTP response
    private def findErrorResponse(
        err: Any,
        errorSchemas: Seq[(HttpResponse.Status, Schema[?], ConcreteTag[Any])]
    ): Maybe[HttpResponse[HttpBody.Bytes]] =
        @tailrec def loop(remaining: Seq[(HttpResponse.Status, Schema[?], ConcreteTag[Any])]): Maybe[HttpResponse[HttpBody.Bytes]] =
            remaining match
                case Seq() => Absent
                case (status, schema, tag) +: tail =>
                    if tag.accepts(err) then
                        try
                            val json = schema.asInstanceOf[Schema[Any]].encode(err)
                            Present(HttpResponse(status, json).setHeader("Content-Type", "application/json"))
                        catch
                            case _: Throwable => loop(tail)
                    else loop(tail)
        loop(errorSchemas)
    end findErrorResponse

    // Combines two tuple values. EmptyTuple is the identity element.
    // All extraction methods return either EmptyTuple or proper Tuple values.
    private def combineInputs(a: Any, b: Any): Any =
        if a.isInstanceOf[EmptyTuple] then b
        else if b.isInstanceOf[EmptyTuple] then a
        else
            (a, b) match
                case (v1: Tuple, v2: Tuple) => Tuple.fromArray((v1.toArray ++ v2.toArray))
                case (v1: Tuple, v2)        => Tuple.fromArray(v1.toArray :+ v2)
                case (v1, v2: Tuple)        => Tuple.fromArray(v1 +: v2.toArray)
                case (v1, v2)               => (v1, v2)

    // Combines inputs in declaration order using param ids.
    // Each category's first param id determines its position. Path is always first.
    private def combineAllInputs(
        pathInput: Any,
        queryInput: Any,
        headerInput: Any,
        cookieInput: Any,
        bodyInput: Any,
        route: HttpRoute[?, ?, ?]
    ): Any =
        // Get first-declared id for each category (-1 = not present)
        val qId = if route.queryParams.nonEmpty then route.queryParams.head.id else -1
        val hId = if route.headerParams.nonEmpty then route.headerParams.head.id else -1
        val cId = if route.cookieParams.nonEmpty then route.cookieParams.head.id else -1
        val bId = route.bodyId

        // Build (id, input) pairs for present categories, then sort by id
        var count  = 0
        val ids    = new Array[Int](4)
        val inputs = new Array[Any](4)
        if qId >= 0 then
            ids(count) = qId; inputs(count) = queryInput; count += 1
        if hId >= 0 then
            ids(count) = hId; inputs(count) = headerInput; count += 1
        if cId >= 0 then
            ids(count) = cId; inputs(count) = cookieInput; count += 1
        if bId >= 0 then
            ids(count) = bId; inputs(count) = bodyInput; count += 1

        // Simple insertion sort (at most 4 elements)
        var i = 1
        while i < count do
            val key    = ids(i)
            val keyVal = inputs(i)
            var j      = i - 1
            while j >= 0 && ids(j) > key do
                ids(j + 1) = ids(j)
                inputs(j + 1) = inputs(j)
                j -= 1
            end while
            ids(j + 1) = key
            inputs(j + 1) = keyVal
            i += 1
        end while

        // Combine in sorted order
        var result: Any = pathInput
        i = 0
        while i < count do
            result = combineInputs(result, inputs(i))
            i += 1
        result
    end combineAllInputs

    private def extractQueryParams(queryParams: Seq[HttpRoute.QueryParam[?]], request: HttpRequest[?])(using Frame): Any =
        val size = queryParams.size
        if size == 0 then EmptyTuple
        else if size == 1 then
            Tuple1(extractQueryParam(queryParams.head, request))
        else
            val arr = new Array[Any](size)
            @tailrec def loop(i: Int): Unit =
                if i < size then
                    arr(i) = extractQueryParam(queryParams(i), request)
                    loop(i + 1)
            loop(0)
            Tuple.fromArray(arr)
        end if
    end extractQueryParams

    private def extractQueryParam(param: HttpRoute.QueryParam[?], request: HttpRequest[?])(using Frame): Any =
        request.query(param.name) match
            case Present(value) =>
                param.schema.asInstanceOf[Schema[Any]].decode(value)
            case Absent =>
                param.default match
                    case Present(d) => d
                    case Absent     => throw new MissingParamException(s"Missing required query parameter: ${param.name}")

    private def extractHeaderParams(headerParams: Seq[HttpRoute.HeaderParam], request: HttpRequest[?])(using Frame): Any =
        val size = headerParams.size
        if size == 0 then EmptyTuple
        else if size == 1 then
            val result = extractHeaderParam(headerParams.head, request)
            result match
                // Basic auth returns (username, password) which is already a Tuple2
                case t: Tuple if headerParams.head.authScheme == Present(HttpRoute.AuthScheme.Basic) => t
                case other                                                                           => Tuple1(other)
            end match
        else
            @tailrec def computeSize(i: Int, acc: Int): Int =
                if i < size then computeSize(i + 1, acc + headerParamInputSize(headerParams(i)))
                else acc
            val arr = new Array[Any](computeSize(0, 0))
            @tailrec def loop(i: Int, j: Int): Unit =
                if i < size then
                    val result = extractHeaderParam(headerParams(i), request)
                    result match
                        case (a, b) if headerParams(i).authScheme == Present(HttpRoute.AuthScheme.Basic) =>
                            arr(j) = a
                            arr(j + 1) = b
                            loop(i + 1, j + 2)
                        case other =>
                            arr(j) = other
                            loop(i + 1, j + 1)
                    end match
            loop(0, 0)
            Tuple.fromArray(arr)
        end if
    end extractHeaderParams

    private def headerParamInputSize(param: HttpRoute.HeaderParam) =
        param.authScheme match
            case Present(HttpRoute.AuthScheme.Basic) => 2
            case _                                   => 1

    private def extractHeaderParam(param: HttpRoute.HeaderParam, request: HttpRequest[?])(using Frame): Any =
        val rawValue = request.header(param.name) match
            case Present(value) => value
            case Absent =>
                param.default match
                    case Present(d) => d
                    case Absent =>
                        param.authScheme match
                            case Present(_) => throw new MissingAuthException(param.name)
                            case Absent     => throw new MissingParamException(s"Missing required header: ${param.name}")
        param.authScheme match
            case Present(HttpRoute.AuthScheme.Bearer) =>
                if rawValue.startsWith("Bearer ") then rawValue.substring(7)
                else throw new InvalidAuthException("Expected Bearer token")
            case Present(HttpRoute.AuthScheme.Basic) =>
                val encoded =
                    if rawValue.startsWith("Basic ") then rawValue.substring(6)
                    else throw new InvalidAuthException("Expected Basic auth")
                val decoded  = new String(java.util.Base64.getDecoder.decode(encoded), "UTF-8")
                val colonIdx = decoded.indexOf(':')
                if colonIdx < 0 then throw new InvalidAuthException("Invalid Basic auth format")
                (decoded.substring(0, colonIdx), decoded.substring(colonIdx + 1))
            case Present(HttpRoute.AuthScheme.ApiKey) =>
                rawValue
            case Absent =>
                rawValue
        end match
    end extractHeaderParam

    private def extractCookieParams(cookieParams: Seq[HttpRoute.CookieParam], request: HttpRequest[?])(using Frame): Any =
        val size = cookieParams.size
        if size == 0 then EmptyTuple
        else if size == 1 then
            Tuple1(extractCookieParam(cookieParams.head, request))
        else
            val arr = new Array[Any](size)
            @tailrec def loop(i: Int): Unit =
                if i < size then
                    arr(i) = extractCookieParam(cookieParams(i), request)
                    loop(i + 1)
            loop(0)
            Tuple.fromArray(arr)
        end if
    end extractCookieParams

    private def extractCookieParam(param: HttpRoute.CookieParam, request: HttpRequest[?])(using Frame) =
        request.cookie(param.name) match
            case Present(cookie) => cookie.value
            case Absent =>
                param.default match
                    case Present(d) => d
                    case Absent     => throw new MissingParamException(s"Missing required cookie: ${param.name}")

    // Internal control-flow exceptions — caught in route handler try-catch, never escape.
    final private class MissingAuthException(name: String)(using Frame)
        extends KyoException(name)
    final private class InvalidAuthException(msg: String)(using Frame)
        extends KyoException(msg)
    final private class MissingParamException(msg: String)(using Frame)
        extends KyoException(msg)

    private def extractPathParams(routePath: HttpPath[Any], requestPath: String): Any =
        routePath match
            case s: String => EmptyTuple
            case segment: HttpPath.Segment[?] =>
                val parts = HttpPath.parseSegments(requestPath)
                extractFromSegment(segment, parts)._1

    private def extractFromSegment(segment: HttpPath.Segment[?], parts: List[String]): (Any, List[String]) =
        segment match
            case HttpPath.Segment.Literal(v) =>
                val literalSize = HttpPath.countSegments(v)
                (EmptyTuple, parts.drop(literalSize))
            case HttpPath.Segment.Capture(_, parse) =>
                // Escape + before decoding: in URL paths, + is literal (only means space in form-encoded queries)
                val decoded = java.net.URLDecoder.decode(parts.head.replace("+", "%2B"), "UTF-8")
                val value   = parse(decoded)
                (Tuple1(value), parts.tail)
            case HttpPath.Segment.Concat(left, right) =>
                val (leftVal, remaining)   = extractFromSegment(left.asInstanceOf[HttpPath.Segment[?]], parts)
                val (rightVal, remaining2) = extractFromSegment(right.asInstanceOf[HttpPath.Segment[?]], remaining)
                (combineInputs(leftVal, rightVal), remaining2)

    // Forwarding method — direct MultipartUtil reference in inline-expanded code
    // causes ClassNotFoundException: kyo/internal at runtime
    private def extractBoundary(contentType: String): Maybe[String] =
        MultipartUtil.extractBoundary(contentType)

end HttpHandler
