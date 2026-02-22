package kyo.http2

import kyo.<
import kyo.Abort
import kyo.Async
import kyo.Duration
import kyo.Frame
import kyo.seconds

object HttpBackend:

    trait Client:
        def send[In, Out](
            route: HttpRoute[In, Out, Any],
            request: HttpRequest[In]
        )(using Frame): HttpResponse[Out] < (Async & Abort[HttpError])
    end Client

    trait Server:
        def bind(
            handlers: Seq[HttpEndpoint[?, ?, Any]],
            port: Int,
            host: String
        )(using Frame): Binding < Async
    end Server

    abstract class Binding:
        def port: Int
        def host: String
        def close(gracePeriod: Duration)(using Frame): Unit < Async
        def close(using Frame): Unit < Async    = close(30.seconds)
        def closeNow(using Frame): Unit < Async = close(Duration.Zero)
        def await(using Frame): Unit < Async
    end Binding

end HttpBackend
