package kyo

import kyo.*

final class HttpFilterTestFactory extends HttpFilter.Factory:

    override def clientFilter(using Frame, AllowUnsafe): Maybe[HttpFilter.Passthrough[Nothing]] =
        Present(new HttpFilter.Passthrough[Nothing]:
            def apply[In, Out, E2, S](
                request: HttpRequest[In],
                next: HttpRequest[In] => HttpResponse[Out] < (S & Async & Abort[E2 | HttpResponse.Halt])
            )(using Frame): HttpResponse[Out] < (S & Async & Abort[E2 | HttpResponse.Halt]) =
                val filtered =
                    if request.url.path == "/auto-client" then request.addHeader("X-Order", "auto-client")
                    else request
                next(filtered))

    override def serverFilter(using Frame, AllowUnsafe): Maybe[HttpFilter.Passthrough[Nothing]] =
        Present(new HttpFilter.Passthrough[Nothing]:
            def apply[In, Out, E2, S](
                request: HttpRequest[In],
                next: HttpRequest[In] => HttpResponse[Out] < (S & Async & Abort[E2 | HttpResponse.Halt])
            )(using Frame): HttpResponse[Out] < (S & Async & Abort[E2 | HttpResponse.Halt]) =
                next(request).map { response =>
                    if request.url.path == "/auto-server" then response.addHeader("X-Auto-Server", "enabled")
                    else response
                })

end HttpFilterTestFactory
