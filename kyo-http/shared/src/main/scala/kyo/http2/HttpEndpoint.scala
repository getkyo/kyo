package kyo.http2

import kyo.<
import kyo.Frame
import kyo.Record.~

sealed abstract class HttpEndpoint[In, Out, -S](val route: HttpRoute[In, Out, ?]):
    self =>

    def apply(request: HttpRequest[In])(using Frame): HttpResponse[Out] < S

    def handle[S2](f: HttpResponse[Out] < S => HttpResponse[Out] < S2)(using Frame): HttpEndpoint[In, Out, S2] =
        new HttpEndpoint[In, Out, S2](self.route):
            def apply(request: HttpRequest[In])(using Frame): HttpResponse[Out] < S2 =
                f(self(request))

end HttpEndpoint

object HttpEndpoint:

    def apply[In, Out, S](
        route: HttpRoute[In, Out, ? >: S]
    )(
        handler: HttpRequest[In] => Frame ?=> HttpResponse[Out] < S
    ): HttpEndpoint[In, Out, S] =
        new HttpEndpoint[In, Out, S](route):
            def apply(request: HttpRequest[In])(using Frame) =
                handler(request)

    def const[In, Out, S](
        route: HttpRoute[In, Out, S],
        response: HttpResponse[Out]
    ): HttpEndpoint[In, Out, S] =
        HttpEndpoint(route)(_ => response)

    def health()(using Frame): HttpEndpoint[Any, "body" ~ String, Any] =
        health("health")

    def health(path: String)(using Frame): HttpEndpoint[Any, "body" ~ String, Any] =
        val route = HttpRoute.get(path).response(_.bodyText)
        route.endpoint(_ => HttpResponse.ok.addField("body", "healthy"))

end HttpEndpoint
