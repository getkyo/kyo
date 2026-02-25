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

    // ==================== Raw methods ====================

    def getRaw[E](path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => HttpResponse[Any] < (Async & Abort[E | HttpResponse.Halt])
    ): HttpHandler[Any, Any, E] =
        init(HttpRoute.get(path))(f)

    def postRaw[E](path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => HttpResponse[Any] < (Async & Abort[E | HttpResponse.Halt])
    ): HttpHandler[Any, Any, E] =
        init(HttpRoute.post(path))(f)

    def putRaw[E](path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => HttpResponse[Any] < (Async & Abort[E | HttpResponse.Halt])
    ): HttpHandler[Any, Any, E] =
        init(HttpRoute.put(path))(f)

    def patchRaw[E](path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => HttpResponse[Any] < (Async & Abort[E | HttpResponse.Halt])
    ): HttpHandler[Any, Any, E] =
        init(HttpRoute.patch(path))(f)

    def deleteRaw[E](path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => HttpResponse[Any] < (Async & Abort[E | HttpResponse.Halt])
    ): HttpHandler[Any, Any, E] =
        init(HttpRoute.delete(path))(f)

    def head[E](path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => HttpResponse[Any] < (Async & Abort[E | HttpResponse.Halt])
    ): HttpHandler[Any, Any, E] =
        init(HttpRoute.head(path))(f)

    def options[E](path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => HttpResponse[Any] < (Async & Abort[E | HttpResponse.Halt])
    ): HttpHandler[Any, Any, E] =
        init(HttpRoute.options(path))(f)

    // ==================== JSON methods ====================

    // GET
    def getJson[A: Schema](path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => A < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler[Any, "body" ~ A, Nothing] =
        val route = HttpRoute.get(path).response(_.bodyJson[A])
        route.handler(req => f(req).map(HttpResponse.okJson(_)))
    end getJson

    // POST
    def postJson[A: Schema, B: Schema](path: String)(using
        Frame
    )(
        f: (HttpRequest["body" ~ B], B) => A < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler["body" ~ B, "body" ~ A, Nothing] =
        val route = HttpRoute.post(path).request(_.bodyJson[B]).response(_.bodyJson[A])
        route.handler(req => f(req, req.fields.body).map(HttpResponse.okJson(_)))
    end postJson

    // PUT
    def putJson[A: Schema, B: Schema](path: String)(using
        Frame
    )(
        f: (HttpRequest["body" ~ B], B) => A < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler["body" ~ B, "body" ~ A, Nothing] =
        val route = HttpRoute.put(path).request(_.bodyJson[B]).response(_.bodyJson[A])
        route.handler(req => f(req, req.fields.body).map(HttpResponse.okJson(_)))
    end putJson

    // PATCH
    def patchJson[A: Schema, B: Schema](path: String)(using
        Frame
    )(
        f: (HttpRequest["body" ~ B], B) => A < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler["body" ~ B, "body" ~ A, Nothing] =
        val route = HttpRoute.patch(path).request(_.bodyJson[B]).response(_.bodyJson[A])
        route.handler(req => f(req, req.fields.body).map(HttpResponse.okJson(_)))
    end patchJson

    // DELETE
    def deleteJson[A: Schema](path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => A < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler[Any, "body" ~ A, Nothing] =
        val route = HttpRoute.delete(path).response(_.bodyJson[A])
        route.handler(req => f(req).map(HttpResponse.okJson(_)))
    end deleteJson

    // ==================== Text methods ====================

    // GET
    def getText(path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => String < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler[Any, "body" ~ String, Nothing] =
        val route = HttpRoute.get(path).response(_.bodyText)
        route.handler(req => f(req).map(HttpResponse.okText(_)))
    end getText

    // POST
    def postText(path: String)(using
        Frame
    )(
        f: (HttpRequest["body" ~ String], String) => String < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler["body" ~ String, "body" ~ String, Nothing] =
        val route = HttpRoute.post(path).request(_.bodyText).response(_.bodyText)
        route.handler(req => f(req, req.fields.body).map(HttpResponse.okText(_)))
    end postText

    // PUT
    def putText(path: String)(using
        Frame
    )(
        f: (HttpRequest["body" ~ String], String) => String < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler["body" ~ String, "body" ~ String, Nothing] =
        val route = HttpRoute.put(path).request(_.bodyText).response(_.bodyText)
        route.handler(req => f(req, req.fields.body).map(HttpResponse.okText(_)))
    end putText

    // PATCH
    def patchText(path: String)(using
        Frame
    )(
        f: (HttpRequest["body" ~ String], String) => String < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler["body" ~ String, "body" ~ String, Nothing] =
        val route = HttpRoute.patch(path).request(_.bodyText).response(_.bodyText)
        route.handler(req => f(req, req.fields.body).map(HttpResponse.okText(_)))
    end patchText

    // DELETE
    def deleteText(path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => String < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler[Any, "body" ~ String, Nothing] =
        val route = HttpRoute.delete(path).response(_.bodyText)
        route.handler(req => f(req).map(HttpResponse.okText(_)))
    end deleteText

    // ==================== Binary methods ====================

    // GET
    def getBinary(path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => Span[Byte] < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler[Any, "body" ~ Span[Byte], Nothing] =
        val route = HttpRoute.get(path).response(_.bodyBinary)
        route.handler(req => f(req).map(HttpResponse.okBinary(_)))
    end getBinary

    // POST
    def postBinary(path: String)(using
        Frame
    )(
        f: (HttpRequest["body" ~ Span[Byte]], Span[Byte]) => Span[Byte] < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler["body" ~ Span[Byte], "body" ~ Span[Byte], Nothing] =
        val route = HttpRoute.post(path).request(_.bodyBinary).response(_.bodyBinary)
        route.handler(req => f(req, req.fields.body).map(HttpResponse.okBinary(_)))
    end postBinary

    // PUT
    def putBinary(path: String)(using
        Frame
    )(
        f: (HttpRequest["body" ~ Span[Byte]], Span[Byte]) => Span[Byte] < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler["body" ~ Span[Byte], "body" ~ Span[Byte], Nothing] =
        val route = HttpRoute.put(path).request(_.bodyBinary).response(_.bodyBinary)
        route.handler(req => f(req, req.fields.body).map(HttpResponse.okBinary(_)))
    end putBinary

    // PATCH
    def patchBinary(path: String)(using
        Frame
    )(
        f: (HttpRequest["body" ~ Span[Byte]], Span[Byte]) => Span[Byte] < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler["body" ~ Span[Byte], "body" ~ Span[Byte], Nothing] =
        val route = HttpRoute.patch(path).request(_.bodyBinary).response(_.bodyBinary)
        route.handler(req => f(req, req.fields.body).map(HttpResponse.okBinary(_)))
    end patchBinary

    // DELETE
    def deleteBinary(path: String)(using
        Frame
    )(
        f: HttpRequest[Any] => Span[Byte] < (Async & Abort[HttpResponse.Halt])
    ): HttpHandler[Any, "body" ~ Span[Byte], Nothing] =
        val route = HttpRoute.delete(path).response(_.bodyBinary)
        route.handler(req => f(req).map(HttpResponse.okBinary(_)))
    end deleteBinary

    // ==================== Streaming methods ====================

    def getSseJson[V: Schema: Tag](path: String)(using
        Frame,
        Tag[Emit[Chunk[HttpEvent[V]]]]
    )(
        f: HttpRequest[Any] => Stream[HttpEvent[V], Async] < Async
    ): HttpHandler[Any, "body" ~ Stream[HttpEvent[V], Async & Scope], Nothing] =
        val route = HttpRoute.get(path).response(_.bodySseJson[V])
        route.handler(req => f(req).map(stream => HttpResponse.ok.addField("body", stream)))
    end getSseJson

    def getSseText(path: String)(using
        Frame,
        Tag[Emit[Chunk[HttpEvent[String]]]]
    )(
        f: HttpRequest[Any] => Stream[HttpEvent[String], Async] < Async
    ): HttpHandler[Any, "body" ~ Stream[HttpEvent[String], Async & Scope], Nothing] =
        val route = HttpRoute.get(path).response(_.bodySseText)
        route.handler(req => f(req).map(stream => HttpResponse.ok.addField("body", stream)))
    end getSseText

    def getNdJson[V: Schema: Tag](path: String)(using
        Frame,
        Tag[Emit[Chunk[V]]]
    )(
        f: HttpRequest[Any] => Stream[V, Async] < Async
    ): HttpHandler[Any, "body" ~ Stream[V, Async], Nothing] =
        val route = HttpRoute.get(path).response(_.bodyNdjson[V])
        route.handler(req => f(req).map(stream => HttpResponse.ok.addField("body", stream)))
    end getNdJson

end HttpHandler
