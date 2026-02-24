package kyo.http2

import kyo.<
import kyo.Abort
import kyo.Async
import kyo.Chunk
import kyo.Emit
import kyo.Frame
import kyo.Record2.~
import kyo.Scope
import kyo.Span
import kyo.Stream
import kyo.Tag

sealed abstract class HttpHandler[In, Out, +E](val route: HttpRoute[In, Out, E]):

    def apply(request: HttpRequest[In])(using Frame): HttpResponse[Out] < (Async & Abort[E | HttpResponse.Halt])

end HttpHandler

object HttpHandler:

    def init[In, Out, E](
        route: HttpRoute[In, Out, E]
    )(
        handler: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E | HttpResponse.Halt])
    ): HttpHandler[In, Out, E] =
        new HttpHandler[In, Out, E](route):
            def apply(request: HttpRequest[In])(using Frame) =
                handler(request)

    // ==================== Convenience helpers ====================

    def health()(using Frame): HttpHandler[Any, "body" ~ String, Nothing] =
        health("health")

    def health(path: String)(using Frame): HttpHandler[Any, "body" ~ String, Nothing] =
        val route = HttpRoute.get(path).response(_.bodyText)
        route.handler(_ => HttpResponse.okText("healthy"))

    def const(method: HttpMethod, path: String, status: HttpStatus)(using Frame): HttpHandler[Any, Any, Nothing] =
        makeRoute(method, path).handler(_ => HttpResponse(status))

    def const(method: HttpMethod, path: String, response: HttpResponse[Any])(using Frame): HttpHandler[Any, Any, Nothing] =
        makeRoute(method, path).handler(_ => response)

    private def makeRoute(method: HttpMethod, path: String): HttpRoute[Any, Any, Nothing] =
        HttpRoute(method, HttpRoute.RequestDef(path))

    // ==================== Shortcut methods ====================

    def get[E](path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => HttpResponse[Any] < (Async & Abort[E | HttpResponse.Halt])
    ): HttpHandler[Any, Any, E] =
        val route = HttpRoute.get(path)
        init(route)(f)
    end get

    def post[E](path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => HttpResponse[Any] < (Async & Abort[E | HttpResponse.Halt])
    ): HttpHandler[Any, Any, E] =
        val route = HttpRoute.post(path)
        init(route)(f)
    end post

    def put[E](path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => HttpResponse[Any] < (Async & Abort[E | HttpResponse.Halt])
    ): HttpHandler[Any, Any, E] =
        val route = HttpRoute.put(path)
        init(route)(f)
    end put

    def patch[E](path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => HttpResponse[Any] < (Async & Abort[E | HttpResponse.Halt])
    ): HttpHandler[Any, Any, E] =
        val route = HttpRoute.patch(path)
        init(route)(f)
    end patch

    def delete[E](path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => HttpResponse[Any] < (Async & Abort[E | HttpResponse.Halt])
    ): HttpHandler[Any, Any, E] =
        val route = HttpRoute.delete(path)
        init(route)(f)
    end delete

    def head[E](path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => HttpResponse[Any] < (Async & Abort[E | HttpResponse.Halt])
    ): HttpHandler[Any, Any, E] =
        val route = HttpRoute.head(path)
        init(route)(f)
    end head

    def options[E](path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => HttpResponse[Any] < (Async & Abort[E | HttpResponse.Halt])
    ): HttpHandler[Any, Any, E] =
        val route = HttpRoute.options(path)
        init(route)(f)
    end options

    // ==================== Streaming handlers ====================

    def streamSse[V: Schema: Tag](path: String)(using
        Frame,
        Tag[Emit[Chunk[HttpEvent[V]]]]
    )(
        f: HttpRequest[Any] => Stream[HttpEvent[V], Async] < Async
    ): HttpHandler[Any, "body" ~ Stream[HttpEvent[V], Async & Scope], Nothing] =
        val route = HttpRoute.get(path).response(_.bodySseJson[V])
        route.handler(req => f(req).map(stream => HttpResponse.ok.addField("body", stream)))
    end streamSse

    def streamNdjson[V: Schema: Tag](path: String)(using
        Frame,
        Tag[Emit[Chunk[V]]]
    )(
        f: HttpRequest[Any] => Stream[V, Async] < Async
    ): HttpHandler[Any, "body" ~ Stream[V, Async], Nothing] =
        val route = HttpRoute.get(path).response(_.bodyNdjson[V])
        route.handler(req => f(req).map(stream => HttpResponse.ok.addField("body", stream)))
    end streamNdjson

end HttpHandler
