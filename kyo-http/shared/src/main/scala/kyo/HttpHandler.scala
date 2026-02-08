package kyo

import kyo.HttpRequest.Method
import kyo.HttpResponse.Status
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
  * mapping. The function `f: In => Out` receives the route's accumulated `In` type as a single flat argument — all path captures, query,
  * header, and body params combined via `Inputs`.
  *
  * Low-level handlers via `HttpHandler.init(method, path)(f)` receive path captures and the raw `HttpRequest` flattened into a single
  * function argument via the `Inputs` match type — the request is the last element. With no captures the function takes just the request;
  * with one capture it takes `(capture, request)`; with multiple captures all values are flattened into a single tuple ending with the
  * request.
  *
  *   - Route-based handlers with automatic JSON serialization/deserialization
  *   - Low-level handlers with flattened path captures + request via `Inputs` match type
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
  *   [[kyo.HttpResponse]]
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
    private[kyo] def apply(request: HttpRequest[?]): kyo.HttpResponse[?] < (Async & S)
end HttpHandler

object HttpHandler:

    /** Creates a route-based handler. `f: In => Out` receives the route's accumulated `In` type as a flat argument — all path captures,
      * query, header, and body params combined via `Inputs`. Output is serialized to JSON. Errors matching route error schemas get the
      * corresponding status code; unmatched errors become 500.
      */
    // asInstanceOf casts: Schema[Any] due to type erasure, fullInput.asInstanceOf[In] bridges runtime-extracted values
    // to compile-time types (route DSL builds In via match types, runtime extraction is dynamic), Abort.run[Any] because Err is erased
    @nowarn("msg=anonymous")
    inline def init[In, Out, Err, S](r: HttpRoute[In, Out, Err])(inline f: In => Out < (Abort[Err] & Async & S))(using
        frame: Frame
    ): HttpHandler[S] =
        new HttpHandler[S]:
            val route: HttpRoute[?, ?, ?] = r
            private[kyo] def apply(request: HttpRequest[?]): kyo.HttpResponse[?] < (Async & S) =
                // Safe cast: server guarantees buffered body for non-streaming handlers
                val req       = request.asInstanceOf[HttpRequest[HttpBody.Bytes]]
                val pathInput = extractPathParams(r.path, req.path)
                // Extract query parameters
                val queryInput = extractQueryParams(r.queryParams, req)
                // Extract header parameters
                val headerInput = extractHeaderParams(r.headerParams, req)
                // Extract body if there's an input schema (cast justified: Schema[?] loses type info)
                val bodyInput = r.inputSchema match
                    case Present(schema) =>
                        schema.asInstanceOf[Schema[Any]].decode(req.bodyText)
                    case Absent =>
                        ()
                // Combine all inputs in order: path, query, headers, body
                val fullInput = combineAllInputs(pathInput, queryInput, headerInput, bodyInput)
                // Call the handler function (cast justified: In is computed via match types at compile time,
                // but fullInput is built dynamically at runtime)
                val computation = f(fullInput.asInstanceOf[In]).map { output =>
                    r.outputSchema match
                        case Present(s) =>
                            // Cast justified: Schema[?] loses type info due to erasure
                            val json = s.asInstanceOf[Schema[Out]].encode(output)
                            kyo.HttpResponse.json(json)
                        case Absent =>
                            kyo.HttpResponse.ok
                }
                // Cast justified: Err is erased at runtime, so Abort.run needs Any as the type parameter.
                // Abort.recover would also need the cast and doesn't handle Panic, so Abort.run is the right choice.
                Abort.run[Any](computation.asInstanceOf[kyo.HttpResponse[?] < (Abort[Any] & Async & S)]).map {
                    case Result.Success(resp) => resp
                    case Result.Failure(err)  =>
                        // Try to find matching error schema and return appropriate response
                        findErrorResponse(err, r.errorSchemas).getOrElse(kyo.HttpResponse.serverError(err.toString))
                    case Result.Panic(ex) => kyo.HttpResponse.serverError(ex.getMessage)
                }
            end apply

    /** Creates a low-level handler. The function receives path captures and the request flattened into a single argument via
      * `Inputs[A, HttpRequest]`. No captures → just the request; one capture → `(capture, request)`; multiple → flat tuple ending with
      * request.
      */
    @nowarn("msg=anonymous")
    inline def init[A, S](
        method: Method,
        path: HttpPath[A]
    )(inline f: HttpPath.Inputs[A, HttpRequest[HttpBody.Bytes]] => kyo.HttpResponse[?] < (Async & S))(using
        Frame
    ): HttpHandler[S] =
        new HttpHandler[S]:
            val route: HttpRoute[?, ?, ?] = HttpRoute(method, path.asInstanceOf[HttpPath[Any]], Status.OK, Absent, Absent)
            private[kyo] def apply(request: HttpRequest[?]): kyo.HttpResponse[?] < (Async & S) =
                val req       = request.asInstanceOf[HttpRequest[HttpBody.Bytes]]
                val pathInput = extractPathParams(path.asInstanceOf[HttpPath[Any]], req.path)
                val fullInput = combineInputs(pathInput, req)
                f(fullInput.asInstanceOf[HttpPath.Inputs[A, HttpRequest[HttpBody.Bytes]]])
            end apply

    /** Creates a GET handler that returns "healthy". */
    def health(path: HttpPath[Unit] = "/health")(using Frame): HttpHandler[Any] =
        init(Method.GET, path)(_ => kyo.HttpResponse.ok("healthy"))

    /** Creates a handler that always returns a fixed status or response, ignoring the request. */
    def const[A](method: Method, path: HttpPath[A], status: Status)(using Frame): HttpHandler[Any] =
        new HttpHandler[Any]:
            val route: HttpRoute[?, ?, ?] = HttpRoute(method, path.asInstanceOf[HttpPath[Any]], Status.OK, Absent, Absent)
            private[kyo] def apply(request: HttpRequest[?]): kyo.HttpResponse[?] < Async =
                kyo.HttpResponse(status)

    def const[A](method: Method, path: HttpPath[A], response: kyo.HttpResponse[?])(using Frame): HttpHandler[Any] =
        new HttpHandler[Any]:
            val route: HttpRoute[?, ?, ?] = HttpRoute(method, path.asInstanceOf[HttpPath[Any]], Status.OK, Absent, Absent)
            private[kyo] def apply(request: HttpRequest[?]): kyo.HttpResponse[?] < Async =
                response

    inline def get[A, S](path: HttpPath[A])(inline f: HttpPath.Inputs[A, HttpRequest[HttpBody.Bytes]] => kyo.HttpResponse[?] < (Async & S))(
        using Frame
    ): HttpHandler[S] =
        init(Method.GET, path)(f)

    inline def post[
        A,
        S
    ](path: HttpPath[A])(inline f: HttpPath.Inputs[A, HttpRequest[HttpBody.Bytes]] => kyo.HttpResponse[?] < (Async & S))(
        using Frame
    ): HttpHandler[S] =
        init(Method.POST, path)(f)

    inline def put[A, S](path: HttpPath[A])(inline f: HttpPath.Inputs[A, HttpRequest[HttpBody.Bytes]] => kyo.HttpResponse[?] < (Async & S))(
        using Frame
    ): HttpHandler[S] =
        init(Method.PUT, path)(f)

    inline def patch[A, S](path: HttpPath[A])(
        inline f: HttpPath.Inputs[A, HttpRequest[HttpBody.Bytes]] => kyo.HttpResponse[?] < (Async & S)
    )(using Frame): HttpHandler[S] =
        init(Method.PATCH, path)(f)

    inline def delete[A, S](path: HttpPath[A])(
        inline f: HttpPath.Inputs[A, HttpRequest[HttpBody.Bytes]] => kyo.HttpResponse[?] < (Async & S)
    )(using Frame): HttpHandler[S] =
        init(Method.DELETE, path)(f)

    inline def head[
        A,
        S
    ](path: HttpPath[A])(inline f: HttpPath.Inputs[A, HttpRequest[HttpBody.Bytes]] => kyo.HttpResponse[?] < (Async & S))(
        using Frame
    ): HttpHandler[S] =
        init(Method.HEAD, path)(f)

    inline def options[A, S](path: HttpPath[A])(
        inline f: HttpPath.Inputs[A, HttpRequest[HttpBody.Bytes]] => kyo.HttpResponse[?] < (Async & S)
    )(using Frame): HttpHandler[S] =
        init(Method.OPTIONS, path)(f)

    // --- Streaming request body ---

    /** Creates a handler that receives a streaming request body.
      *
      * The handler receives `HttpRequest[HttpBody.Streamed]` whose `bodyStream` delivers chunks as they arrive from the client, without
      * buffering the entire body in memory.
      */
    @nowarn("msg=anonymous")
    inline def streamingBody[A, S](
        method: Method,
        path: HttpPath[A]
    )(inline f: HttpPath.Inputs[A, HttpRequest[HttpBody.Streamed]] => kyo.HttpResponse[?] < (Async & S))(using
        Frame
    ): HttpHandler[S] =
        new HttpHandler[S]:
            val route: HttpRoute[?, ?, ?] = HttpRoute(method, path.asInstanceOf[HttpPath[Any]], Status.OK, Absent, Absent)
            override private[kyo] def streamingRequest: Boolean = true
            private[kyo] def apply(request: HttpRequest[?]): kyo.HttpResponse[?] < (Async & S) =
                val pathInput = extractPathParams(path.asInstanceOf[HttpPath[Any]], request.path)
                val fullInput = combineInputs(pathInput, request.asInstanceOf[HttpRequest[HttpBody.Streamed]])
                f(fullInput.asInstanceOf[HttpPath.Inputs[A, HttpRequest[HttpBody.Streamed]]])
            end apply
        end new
    end streamingBody

    // --- SSE streaming ---

    inline def streamSse[A, V: Schema: Tag, S](
        path: HttpPath[A]
    )(
        inline f: HttpPath.Inputs[A, HttpRequest[HttpBody.Bytes]] => Stream[HttpEvent[V], Async & S]
    )(using Frame): HttpHandler[S] =
        streamSse(Method.GET, path)(f)

    @nowarn("msg=anonymous")
    inline def streamSse[A, V: Schema: Tag, S](
        method: Method,
        path: HttpPath[A]
    )(
        inline f: HttpPath.Inputs[A, HttpRequest[HttpBody.Bytes]] => Stream[HttpEvent[V], Async & S]
    )(using Frame): HttpHandler[S] =
        val schema = Schema[V]
        new HttpHandler[S]:
            val route = HttpRoute(method, path.asInstanceOf[HttpPath[Any]], Status.OK, Absent, Absent)
            private[kyo] def apply(request: HttpRequest[?]) =
                val req       = request.asInstanceOf[HttpRequest[HttpBody.Bytes]]
                val pathInput = extractPathParams(path.asInstanceOf[HttpPath[Any]], req.path)
                val fullInput = combineInputs(pathInput, req)
                val stream    = f(fullInput.asInstanceOf[HttpPath.Inputs[A, HttpRequest[HttpBody.Bytes]]])
                HttpResponse.streamSse(stream.asInstanceOf[Stream[HttpEvent[Any], Async]])(using schema.asInstanceOf[Schema[Any]])
            end apply
        end new
    end streamSse

    // --- NDJSON streaming ---

    inline def streamNdjson[A, V: Schema: Tag, S](
        path: HttpPath[A]
    )(
        inline f: HttpPath.Inputs[A, HttpRequest[HttpBody.Bytes]] => Stream[V, Async & S]
    )(using Frame): HttpHandler[S] =
        streamNdjson(Method.GET, path)(f)

    @nowarn("msg=anonymous")
    inline def streamNdjson[A, V: Schema: Tag, S](
        method: Method,
        path: HttpPath[A]
    )(
        inline f: HttpPath.Inputs[A, HttpRequest[HttpBody.Bytes]] => Stream[V, Async & S]
    )(using Frame): HttpHandler[S] =
        val schema = Schema[V]
        new HttpHandler[S]:
            val route = HttpRoute(method, path.asInstanceOf[HttpPath[Any]], Status.OK, Absent, Absent)
            private[kyo] def apply(request: HttpRequest[?]) =
                val req       = request.asInstanceOf[HttpRequest[HttpBody.Bytes]]
                val pathInput = extractPathParams(path.asInstanceOf[HttpPath[Any]], req.path)
                val fullInput = combineInputs(pathInput, req)
                val stream    = f(fullInput.asInstanceOf[HttpPath.Inputs[A, HttpRequest[HttpBody.Bytes]]])
                HttpResponse.streamNdjson(stream.asInstanceOf[Stream[Any, Async]])(using schema.asInstanceOf[Schema[Any]])
            end apply
        end new
    end streamNdjson

    /** Creates a stub handler that preserves route metadata but returns a fixed response. For OpenAPI spec generation only. */
    private[kyo] def stub(r: HttpRoute[?, ?, ?]): HttpHandler[Any] =
        new HttpHandler[Any]:
            val route: HttpRoute[?, ?, ?] = r
            private[kyo] def apply(request: HttpRequest[?]): kyo.HttpResponse[?] < Async =
                kyo.HttpResponse.ok

    // --- Private implementation ---

    // Try to encode an error using registered error schemas and return appropriate HTTP response
    private def findErrorResponse(
        err: Any,
        errorSchemas: Seq[(HttpResponse.Status, Schema[?], ConcreteTag[Any])]
    ): Option[kyo.HttpResponse[HttpBody.Bytes]] =
        @tailrec def loop(remaining: Seq[(HttpResponse.Status, Schema[?], ConcreteTag[Any])]): Option[kyo.HttpResponse[HttpBody.Bytes]] =
            remaining match
                case Seq() => None
                case (status, schema, tag) +: tail =>
                    if tag.accepts(err) then
                        try
                            val json = schema.asInstanceOf[Schema[Any]].encode(err)
                            Some(kyo.HttpResponse(status, json).addHeader("Content-Type", "application/json"))
                        catch
                            case _: Throwable => loop(tail)
                    else loop(tail)
        loop(errorSchemas)
    end findErrorResponse

    // Note: isInstanceOf[Unit] is used here because we're working with Any at runtime.
    // At compile time, the Inputs[A, B] match type handles Unit specially, but at runtime
    // we need to detect Unit values to avoid wrapping them in tuples. This is a consequence
    // of the type-level DSL design where compile-time types don't match runtime representations.
    private[kyo] def combineInputs(a: Any, b: Any): Any =
        if a.isInstanceOf[Unit] then b
        else if b.isInstanceOf[Unit] then a
        else
            (a, b) match
                case (v1: Tuple, v2: Tuple) => Tuple.fromArray((v1.toArray ++ v2.toArray))
                case (v1: Tuple, v2)        => Tuple.fromArray(v1.toArray :+ v2)
                case (v1, v2: Tuple)        => Tuple.fromArray(v1 +: v2.toArray)
                case (v1, v2)               => (v1, v2)

    // Combines in declaration order: path → query → header → body (must match route's In type accumulation)
    private def combineAllInputs(pathInput: Any, queryInput: Any, headerInput: Any, bodyInput: Any): Any =
        val combined1 = combineInputs(pathInput, queryInput)
        val combined2 = combineInputs(combined1, headerInput)
        combineInputs(combined2, bodyInput)
    end combineAllInputs

    private def extractQueryParams(queryParams: Seq[HttpRoute.QueryParam[?]], request: HttpRequest[?]): Any =
        val size = queryParams.size
        if size == 0 then ()
        else if size == 1 then
            extractQueryParam(queryParams.head, request)
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

    private def extractQueryParam(param: HttpRoute.QueryParam[?], request: HttpRequest[?]): Any =
        request.query(param.name) match
            case Present(value) =>
                // Decode using the schema
                param.schema.asInstanceOf[Schema[Any]].decode(value)
            case Absent =>
                param.default match
                    case Present(d) => d
                    case Absent     => throw new IllegalArgumentException(s"Missing required query parameter: ${param.name}")

    private def extractHeaderParams(headerParams: Seq[HttpRoute.HeaderParam], request: HttpRequest[?]): Any =
        val size = headerParams.size
        if size == 0 then ()
        else if size == 1 then
            extractHeaderParam(headerParams.head, request)
        else
            val arr = new Array[Any](size)
            @tailrec def loop(i: Int): Unit =
                if i < size then
                    arr(i) = extractHeaderParam(headerParams(i), request)
                    loop(i + 1)
            loop(0)
            Tuple.fromArray(arr)
        end if
    end extractHeaderParams

    private def extractHeaderParam(param: HttpRoute.HeaderParam, request: HttpRequest[?]): String =
        request.header(param.name) match
            case Present(value) => value
            case Absent =>
                param.default match
                    case Present(d) => d
                    case Absent     => throw new IllegalArgumentException(s"Missing required header: ${param.name}")

    private[kyo] def extractPathParams(routePath: HttpPath[Any], requestPath: String): Any =
        routePath match
            case s: String => ()
            case segment: HttpPath.Segment[?] =>
                val parts = HttpPath.parseSegments(requestPath)
                extractFromSegment(segment, parts)._1

    private def extractFromSegment(segment: HttpPath.Segment[?], parts: List[String]): (Any, List[String]) =
        segment match
            case HttpPath.Segment.Literal(v) =>
                // Skip literal parts
                val literalSize = HttpPath.countSegments(v)
                ((), parts.drop(literalSize))
            case HttpPath.Segment.Capture(_, parse) =>
                val value = parse(parts.head)
                (value, parts.tail)
            case HttpPath.Segment.Concat(left, right) =>
                val (leftVal, remaining)   = extractFromSegment(left.asInstanceOf[HttpPath.Segment[?]], parts)
                val (rightVal, remaining2) = extractFromSegment(right.asInstanceOf[HttpPath.Segment[?]], remaining)
                (combineInputs(leftVal, rightVal), remaining2)

end HttpHandler
