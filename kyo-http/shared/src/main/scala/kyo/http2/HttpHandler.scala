package kyo.http2

import kyo.<
import kyo.Frame
import kyo.Record.~

sealed abstract class HttpHandler[In, Out, -S](val route: HttpRoute[In, Out, ? >: S]):

    def apply(request: HttpRequest[In])(using Frame): HttpResponse[Out] < S

    def handle[S2](f: HttpResponse[Out] < S => HttpResponse[Out] < S2)(using Frame): HttpHandler[In, Out, S2] =
        val self = this
        HttpHandler(self.route, req => f(self(req)))

end HttpHandler

object HttpHandler:

    private[http2] def apply[In, Out, S](
        route: HttpRoute[In, Out, ? >: S],
        handler: HttpRequest[In] => Frame ?=> HttpResponse[Out] < S
    ): HttpHandler[In, Out, S] =
        new HttpHandler[In, Out, S](route):
            def apply(request: HttpRequest[In])(using Frame): HttpResponse[Out] < S =
                handler(request)

    def const[In, Out](
        route: HttpRoute[In, Out, ? >: Any],
        response: HttpResponse[Out]
    ): HttpHandler[In, Out, Any] =
        new HttpHandler[In, Out, Any](route):
            def apply(request: HttpRequest[In])(using Frame): HttpResponse[Out] < Any =
                response

    def health()(using Frame): HttpHandler[Any, "body" ~ String, Any] =
        health("health")

    def health(path: String)(using Frame): HttpHandler[Any, "body" ~ String, Any] =
        val route = HttpRoute.get(path).response(_.bodyText)
        route.handle(_ => HttpResponse.ok.addField("body", "healthy"))
    end health

end HttpHandler
