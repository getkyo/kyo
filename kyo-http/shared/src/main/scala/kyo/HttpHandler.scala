package kyo

import kyo.*
import kyo.Record2.~

sealed abstract class HttpHandler[In, Out, +E](val route: HttpRoute[In, Out, E]):

    def apply(request: HttpRequest[In])(using Frame): HttpResponse[Out] < (Async & Abort[E | HttpResponse.Halt])

end HttpHandler

object HttpHandler:

    def init[In, Out, E](
        route: HttpRoute[In, Out, E]
    )(
        handler: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E | HttpResponse.Halt])
    ): HttpHandler[In, Out, E] =
        val f = route.filter.asInstanceOf[HttpFilter[Any, Any, Any, Any, E]]
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
        val route = HttpRoute.getRaw(path).response(_.bodyText)
        route.handler(_ => HttpResponse.okText("healthy"))

    def const(method: HttpMethod, path: String, status: HttpStatus)(using Frame): HttpHandler[Any, Any, Nothing] =
        makeRoute(method, path).handler(_ => HttpResponse(status))

    def const(method: HttpMethod, path: String, response: HttpResponse[Any])(using Frame): HttpHandler[Any, Any, Nothing] =
        makeRoute(method, path).handler(_ => response)

    private def makeRoute(method: HttpMethod, path: String): HttpRoute[Any, Any, Nothing] =
        HttpRoute(method, HttpRoute.RequestDef(path))

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

    def getJson[A: Schema](path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => A < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler[Any, "body" ~ A, Nothing] =
        HttpRoute.getJson[A](path).handler(req => f(req).map(HttpResponse.okJson(_)))

    def postJson[A: Schema](path: String)(using
        Frame
    )[B: Schema](
        f: (HttpRequest["body" ~ A], A) => B < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler["body" ~ A, "body" ~ B, Nothing] =
        HttpRoute.postJson[B, A](path).handler(req => f(req, req.fields.body).map(HttpResponse.okJson(_)))

    def putJson[A: Schema](path: String)(using
        Frame
    )[B: Schema](
        f: (HttpRequest["body" ~ A], A) => B < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler["body" ~ A, "body" ~ B, Nothing] =
        HttpRoute.putJson[B, A](path).handler(req => f(req, req.fields.body).map(HttpResponse.okJson(_)))

    def patchJson[A: Schema](path: String)(using
        Frame
    )[B: Schema](
        f: (HttpRequest["body" ~ A], A) => B < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler["body" ~ A, "body" ~ B, Nothing] =
        HttpRoute.patchJson[B, A](path).handler(req => f(req, req.fields.body).map(HttpResponse.okJson(_)))

    def deleteJson[A: Schema](path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => A < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler[Any, "body" ~ A, Nothing] =
        HttpRoute.deleteJson[A](path).handler(req => f(req).map(HttpResponse.okJson(_)))

    // ==================== Text methods ====================

    def getText(path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => String < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler[Any, "body" ~ String, Nothing] =
        HttpRoute.getText(path).handler(req => f(req).map(HttpResponse.okText(_)))

    def postText(path: String)(using
        Frame
    )(
        f: (HttpRequest["body" ~ String], String) => String < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler["body" ~ String, "body" ~ String, Nothing] =
        HttpRoute.postText(path).handler(req => f(req, req.fields.body).map(HttpResponse.okText(_)))

    def putText(path: String)(using
        Frame
    )(
        f: (HttpRequest["body" ~ String], String) => String < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler["body" ~ String, "body" ~ String, Nothing] =
        HttpRoute.putText(path).handler(req => f(req, req.fields.body).map(HttpResponse.okText(_)))

    def patchText(path: String)(using
        Frame
    )(
        f: (HttpRequest["body" ~ String], String) => String < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler["body" ~ String, "body" ~ String, Nothing] =
        HttpRoute.patchText(path).handler(req => f(req, req.fields.body).map(HttpResponse.okText(_)))

    def deleteText(path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => String < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler[Any, "body" ~ String, Nothing] =
        HttpRoute.deleteText(path).handler(req => f(req).map(HttpResponse.okText(_)))

    // ==================== Binary methods ====================

    def getBinary(path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => Span[Byte] < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler[Any, "body" ~ Span[Byte], Nothing] =
        HttpRoute.getBinary(path).handler(req => f(req).map(HttpResponse.okBinary(_)))

    def postBinary(path: String)(using
        Frame
    )(
        f: (HttpRequest["body" ~ Span[Byte]], Span[Byte]) => Span[Byte] < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler["body" ~ Span[Byte], "body" ~ Span[Byte], Nothing] =
        HttpRoute.postBinary(path).handler(req => f(req, req.fields.body).map(HttpResponse.okBinary(_)))

    def putBinary(path: String)(using
        Frame
    )(
        f: (HttpRequest["body" ~ Span[Byte]], Span[Byte]) => Span[Byte] < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler["body" ~ Span[Byte], "body" ~ Span[Byte], Nothing] =
        HttpRoute.putBinary(path).handler(req => f(req, req.fields.body).map(HttpResponse.okBinary(_)))

    def patchBinary(path: String)(using
        Frame
    )(
        f: (HttpRequest["body" ~ Span[Byte]], Span[Byte]) => Span[Byte] < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler["body" ~ Span[Byte], "body" ~ Span[Byte], Nothing] =
        HttpRoute.patchBinary(path).handler(req => f(req, req.fields.body).map(HttpResponse.okBinary(_)))

    def deleteBinary(path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => Span[Byte] < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler[Any, "body" ~ Span[Byte], Nothing] =
        HttpRoute.deleteBinary(path).handler(req => f(req).map(HttpResponse.okBinary(_)))

    // ==================== Streaming methods ====================

    def getSseJson[V: Schema: Tag](path: String)(using
        Frame,
        Tag[Emit[Chunk[HttpEvent[V]]]]
    )(
        f: HttpRequest[Any] => Stream[HttpEvent[V], Async] < Async
    ): HttpHandler[Any, "body" ~ Stream[HttpEvent[V], Async], Nothing] =
        val route = HttpRoute.getRaw(path).response(_.bodySseJson[V])
        route.handler(req => f(req).map(stream => HttpResponse.ok.addField("body", stream)))
    end getSseJson

    def getSseText(path: String)(using
        Frame,
        Tag[Emit[Chunk[HttpEvent[String]]]]
    )(
        f: HttpRequest[Any] => Stream[HttpEvent[String], Async] < Async
    ): HttpHandler[Any, "body" ~ Stream[HttpEvent[String], Async], Nothing] =
        val route = HttpRoute.getRaw(path).response(_.bodySseText)
        route.handler(req => f(req).map(stream => HttpResponse.ok.addField("body", stream)))
    end getSseText

    def getNdJson[V: Schema: Tag](path: String)(using
        Frame,
        Tag[Emit[Chunk[V]]]
    )(
        f: HttpRequest[Any] => Stream[V, Async] < Async
    ): HttpHandler[Any, "body" ~ Stream[V, Async], Nothing] =
        val route = HttpRoute.getRaw(path).response(_.bodyNdjson[V])
        route.handler(req => f(req).map(stream => HttpResponse.ok.addField("body", stream)))
    end getNdJson

end HttpHandler
