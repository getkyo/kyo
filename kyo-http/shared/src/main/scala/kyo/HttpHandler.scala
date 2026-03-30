package kyo

import kyo.*

/** An executable endpoint that pairs an HttpRoute with a request handler function.
  *
  * HttpHandler is what the server dispatches to when a request matches a route's method and path. It wraps the route's filter chain and the
  * user's handler function so that filters are applied automatically on every request. The handler function receives an `HttpRequest[In]`
  * with all declared fields type-safely accessible via `req.fields`.
  *
  * For simple endpoints, use the convenience methods on the companion (`getJson`, `postText`, `getSseJson`, etc.) which create a route and
  * handler in one call. These methods automatically wrap the return value in the appropriate `HttpResponse` factory (e.g.,
  * `HttpResponse.ok`). For full control over the response, use `HttpRoute.handler` or `HttpHandler.init(route)(handler)` directly.
  *
  * @tparam In
  *   request field types (from the route's captures, query params, headers, cookies, body)
  * @tparam Out
  *   response field types (from the route's body, headers, cookies)
  * @tparam E
  *   domain error types mapped to HTTP status codes via the route's `.error[E](status)`
  *
  * @see
  *   [[kyo.HttpRoute.handler]] The primary way to create a handler from a route
  * @see
  *   [[kyo.HttpServer]] Binds handlers to a port
  * @see
  *   [[kyo.HttpResponse.Halt]] Short-circuit mechanism for early exits
  */
sealed abstract class HttpHandler[In, Out, +E](val route: HttpRoute[In, Out, E]):

    def apply(request: HttpRequest[In])(using Frame): HttpResponse[Out] < (Async & Abort[E | HttpResponse.Halt])

    /** Decode a buffered request from raw wire data and invoke the handler. No casts needed — this method has access to the concrete types
      * In, Out, E through `this`. Used by HttpTransportServer to serve requests without type erasure issues.
      */
    private[kyo] def serveBuffered(
        pathCaptures: Dict[String, String],
        queryParam: Maybe[HttpUrl],
        headers: HttpHeaders,
        body: Span[Byte],
        path: String,
        method: HttpMethod
    )(using Frame): Result[HttpException, HttpResponse[Out] < (Async & Abort[E | HttpResponse.Halt])] =
        internal.RouteUtil.decodeBufferedRequest(route, pathCaptures, queryParam, headers, body, path, Present(method))
            .map(request => this(request))

    /** Decode a streaming request from raw wire data and invoke the handler. */
    private[kyo] def serveStreaming(
        pathCaptures: Dict[String, String],
        queryParam: Maybe[HttpUrl],
        headers: HttpHeaders,
        body: Stream[Span[Byte], Async],
        path: String,
        method: HttpMethod
    )(using Frame): Result[HttpException, HttpResponse[Out] < (Async & Abort[E | HttpResponse.Halt])] =
        internal.RouteUtil.decodeStreamingRequest(route, pathCaptures, queryParam, headers, body, path, Present(method))
            .map(request => this(request))

    /** Encode a successful response to wire format using RouteUtil callbacks. */
    private[kyo] def encodeResponse[A](response: HttpResponse[Out])(
        onEmpty: (HttpStatus, HttpHeaders) => A,
        onBuffered: (HttpStatus, HttpHeaders, Span[Byte]) => A,
        onStreaming: (HttpStatus, HttpHeaders, Stream[Span[Byte], Async]) => A
    )(using Frame): A =
        internal.RouteUtil.encodeResponse(route, response)(onEmpty, onBuffered, onStreaming)

    /** Try to encode a typed error via the route's error mappings. */
    private[kyo] def encodeError(error: Any)(using Frame): Maybe[(HttpStatus, HttpHeaders, Span[Byte])] =
        internal.RouteUtil.encodeError(route, error)

end HttpHandler

/** A WebSocket endpoint handler. Extends HttpHandler so it can be registered with HttpServer.init alongside normal HTTP handlers. The
  * backend detects this type and performs a WebSocket upgrade instead of normal request-response dispatch.
  */
final private[kyo] class WebSocketHttpHandler(
    route: HttpRoute[Any, Any, Nothing],
    private[kyo] val wsHandler: (HttpRequest[Any], WebSocket) => Unit < (Async & Abort[Closed]),
    private[kyo] val wsConfig: WebSocketConfig
) extends HttpHandler[Any, Any, Nothing](route):
    def apply(request: HttpRequest[Any])(using Frame) =
        HttpResponse(HttpStatus.SwitchingProtocols)
end WebSocketHttpHandler

object HttpHandler:

    def init[In, Out, E](
        route: HttpRoute[In, Out, E]
    )(
        handler: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E | HttpResponse.Halt])
    ): HttpHandler[In, Out, E] =
        val f = HttpFilterFactory.composedServer.andThen(route.filter)
            .asInstanceOf[HttpFilter[Any, Any, Any, Any, E]]
        new HttpHandler[In, Out, E](route):
            def apply(request: HttpRequest[In])(using Frame) =
                f(request, handler)
    end init

    private[kyo] def wrapHeaders[In, Out, E](
        handler: HttpHandler[In, Out, E],
        addHeaders: Seq[(String, String)]
    ): HttpHandler[In, Out, E] =
        val h = handler
        new HttpHandler[In, Out, E](h.route):
            private def applyHeaders[F](response: HttpResponse[F]): HttpResponse[F] =
                var r = response
                addHeaders.foreach((k, v) => r = r.addHeader(k, v))
                r
            end applyHeaders

            def apply(request: HttpRequest[In])(using Frame) =
                h(request).handle(Abort.run[HttpResponse.Halt]).map {
                    case kyo.Result.Success(response) =>
                        applyHeaders(response)
                    case kyo.Result.Failure(halt) =>
                        Abort.fail(HttpResponse.Halt(applyHeaders(halt.response)))
                }
        end new
    end wrapHeaders

    // ==================== Convenience helpers ====================

    def health()(using Frame): HttpHandler[Any, "body" ~ String, Nothing] =
        health("health")

    def health(path: String)(using Frame): HttpHandler[Any, "body" ~ String, Nothing] =
        val route    = HttpRoute.getRaw(path).response(_.bodyText)
        val response = HttpResponse.ok("healthy")
        route.handler(_ => response)
    end health

    def const(method: HttpMethod, path: String, status: HttpStatus)(using Frame): HttpHandler[Any, Any, Nothing] =
        val response = HttpResponse(status)
        const(method, path, response)

    def const(method: HttpMethod, path: String, response: HttpResponse[Any])(using Frame): HttpHandler[Any, Any, Nothing] =
        HttpRoute(method, HttpRoute.RequestDef(path)).handler(_ => response)

    // ==================== Raw methods ====================

    def getRaw[E](path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => HttpResponse[Any] < (Async & Abort[E | HttpResponse.Halt])
    ): HttpHandler[Any, Any, E] =
        init(HttpRoute.getRaw(path))(f)

    def postRaw[E](path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => HttpResponse[Any] < (Async & Abort[E | HttpResponse.Halt])
    ): HttpHandler[Any, Any, E] =
        init(HttpRoute.postRaw(path))(f)

    def putRaw[E](path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => HttpResponse[Any] < (Async & Abort[E | HttpResponse.Halt])
    ): HttpHandler[Any, Any, E] =
        init(HttpRoute.putRaw(path))(f)

    def patchRaw[E](path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => HttpResponse[Any] < (Async & Abort[E | HttpResponse.Halt])
    ): HttpHandler[Any, Any, E] =
        init(HttpRoute.patchRaw(path))(f)

    def deleteRaw[E](path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => HttpResponse[Any] < (Async & Abort[E | HttpResponse.Halt])
    ): HttpHandler[Any, Any, E] =
        init(HttpRoute.deleteRaw(path))(f)

    def headRaw[E](path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => HttpResponse[Any] < (Async & Abort[E | HttpResponse.Halt])
    ): HttpHandler[Any, Any, E] =
        init(HttpRoute.headRaw(path))(f)

    def optionsRaw[E](path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => HttpResponse[Any] < (Async & Abort[E | HttpResponse.Halt])
    ): HttpHandler[Any, Any, E] =
        init(HttpRoute.optionsRaw(path))(f)

    // ==================== JSON methods ====================

    def getJson[A: Json](path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => A < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler[Any, "body" ~ A, Nothing] =
        HttpRoute.getJson[A](path).handler(req => f(req).map(HttpResponse.ok(_)))

    def postJson[A: Json](path: String)(using
        Frame
    )[B: Json](
        f: (HttpRequest["body" ~ A], A) => B < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler["body" ~ A, "body" ~ B, Nothing] =
        HttpRoute.postJson[B, A](path).handler(req => f(req, req.fields.body).map(HttpResponse.ok(_)))

    def putJson[A: Json](path: String)(using
        Frame
    )[B: Json](
        f: (HttpRequest["body" ~ A], A) => B < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler["body" ~ A, "body" ~ B, Nothing] =
        HttpRoute.putJson[B, A](path).handler(req => f(req, req.fields.body).map(HttpResponse.ok(_)))

    def patchJson[A: Json](path: String)(using
        Frame
    )[B: Json](
        f: (HttpRequest["body" ~ A], A) => B < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler["body" ~ A, "body" ~ B, Nothing] =
        HttpRoute.patchJson[B, A](path).handler(req => f(req, req.fields.body).map(HttpResponse.ok(_)))

    def deleteJson[A: Json](path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => A < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler[Any, "body" ~ A, Nothing] =
        HttpRoute.deleteJson[A](path).handler(req => f(req).map(HttpResponse.ok(_)))

    // ==================== Text methods ====================

    def getText(path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => String < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler[Any, "body" ~ String, Nothing] =
        HttpRoute.getText(path).handler(req => f(req).map(HttpResponse.ok(_)))

    def postText(path: String)(using
        Frame
    )(
        f: (HttpRequest["body" ~ String], String) => String < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler["body" ~ String, "body" ~ String, Nothing] =
        HttpRoute.postText(path).handler(req => f(req, req.fields.body).map(HttpResponse.ok(_)))

    def putText(path: String)(using
        Frame
    )(
        f: (HttpRequest["body" ~ String], String) => String < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler["body" ~ String, "body" ~ String, Nothing] =
        HttpRoute.putText(path).handler(req => f(req, req.fields.body).map(HttpResponse.ok(_)))

    def patchText(path: String)(using
        Frame
    )(
        f: (HttpRequest["body" ~ String], String) => String < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler["body" ~ String, "body" ~ String, Nothing] =
        HttpRoute.patchText(path).handler(req => f(req, req.fields.body).map(HttpResponse.ok(_)))

    def deleteText(path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => String < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler[Any, "body" ~ String, Nothing] =
        HttpRoute.deleteText(path).handler(req => f(req).map(HttpResponse.ok(_)))

    // ==================== Binary methods ====================

    def getBinary(path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => Span[Byte] < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler[Any, "body" ~ Span[Byte], Nothing] =
        HttpRoute.getBinary(path).handler(req => f(req).map(HttpResponse.ok(_)))

    def postBinary(path: String)(using
        Frame
    )(
        f: (HttpRequest["body" ~ Span[Byte]], Span[Byte]) => Span[Byte] < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler["body" ~ Span[Byte], "body" ~ Span[Byte], Nothing] =
        HttpRoute.postBinary(path).handler(req => f(req, req.fields.body).map(HttpResponse.ok(_)))

    def putBinary(path: String)(using
        Frame
    )(
        f: (HttpRequest["body" ~ Span[Byte]], Span[Byte]) => Span[Byte] < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler["body" ~ Span[Byte], "body" ~ Span[Byte], Nothing] =
        HttpRoute.putBinary(path).handler(req => f(req, req.fields.body).map(HttpResponse.ok(_)))

    def patchBinary(path: String)(using
        Frame
    )(
        f: (HttpRequest["body" ~ Span[Byte]], Span[Byte]) => Span[Byte] < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler["body" ~ Span[Byte], "body" ~ Span[Byte], Nothing] =
        HttpRoute.patchBinary(path).handler(req => f(req, req.fields.body).map(HttpResponse.ok(_)))

    def deleteBinary(path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => Span[Byte] < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler[Any, "body" ~ Span[Byte], Nothing] =
        HttpRoute.deleteBinary(path).handler(req => f(req).map(HttpResponse.ok(_)))

    // ==================== Streaming methods ====================

    def getSseJson[V: Json: Tag](path: String)(using
        Frame,
        Tag[Emit[Chunk[HttpSseEvent[V]]]]
    )(
        f: HttpRequest[Any] => Stream[HttpSseEvent[V], Async] < Async
    ): HttpHandler[Any, "body" ~ Stream[HttpSseEvent[V], Async], Nothing] =
        val route = HttpRoute.getRaw(path).response(_.bodySseJson[V])
        route.handler(req => f(req).map(stream => HttpResponse.ok.addField("body", stream)))
    end getSseJson

    def getSseText(path: String)(using
        Frame,
        Tag[Emit[Chunk[HttpSseEvent[String]]]]
    )(
        f: HttpRequest[Any] => Stream[HttpSseEvent[String], Async] < Async
    ): HttpHandler[Any, "body" ~ Stream[HttpSseEvent[String], Async], Nothing] =
        val route = HttpRoute.getRaw(path).response(_.bodySseText)
        route.handler(req => f(req).map(stream => HttpResponse.ok.addField("body", stream)))
    end getSseText

    def getNdJson[V: Json: Tag](path: String)(using
        Frame,
        Tag[Emit[Chunk[V]]]
    )(
        f: HttpRequest[Any] => Stream[V, Async] < Async
    ): HttpHandler[Any, "body" ~ Stream[V, Async], Nothing] =
        val route = HttpRoute.getRaw(path).response(_.bodyNdjson[V])
        route.handler(req => f(req).map(stream => HttpResponse.ok.addField("body", stream)))
    end getNdJson

    // ==================== WebSocket methods ====================

    /** Creates a WebSocket endpoint at the given path. The handler function receives the upgrade request (for auth, cookies, subprotocol
      * negotiation) and a WebSocket handle. The handler runs for the lifetime of the connection — when it returns, the connection closes.
      */
    def webSocket(path: String)(
        f: (HttpRequest[Any], WebSocket) => Unit < (Async & Abort[Closed])
    )(using Frame): HttpHandler[Any, Any, Nothing] =
        new WebSocketHttpHandler(HttpRoute.getRaw(path), f, WebSocketConfig())

    /** Creates a WebSocket endpoint with custom configuration. */
    def webSocket(path: String, config: WebSocketConfig)(
        f: (HttpRequest[Any], WebSocket) => Unit < (Async & Abort[Closed])
    )(using Frame): HttpHandler[Any, Any, Nothing] =
        new WebSocketHttpHandler(HttpRoute.getRaw(path), f, config)

end HttpHandler
