package kyo

import kyo.*
import kyo.seconds

object HttpBackend:

    trait Client:
        type Connection

        def connectWith[A, S](
            host: String,
            port: Int,
            ssl: Boolean,
            connectTimeout: Maybe[Duration]
        )(
            f: Connection => A < S
        )(using Frame): A < (S & Async & Abort[HttpError])

        def sendWith[In, Out, A, S](
            conn: Connection,
            route: HttpRoute[In, Out, ?],
            request: HttpRequest[In],
            onReleaseUnsafe: Maybe[Result.Error[Any]] => Unit = _ => ()
        )(
            f: HttpResponse[Out] => A < S
        )(using Frame): A < (S & Async & Abort[HttpError])

        def isAlive(conn: Connection)(using AllowUnsafe): Boolean
        def closeNowUnsafe(conn: Connection)(using AllowUnsafe): Unit
        def close(conn: Connection, gracePeriod: Duration)(using Frame): Unit < Async
        def close(conn: Connection)(using Frame): Unit < Async    = close(conn, 30.seconds)
        def closeNow(conn: Connection)(using Frame): Unit < Async = close(conn, Duration.Zero)

        def close(gracePeriod: Duration)(using Frame): Unit < Async
        def close(using Frame): Unit < Async    = close(30.seconds)
        def closeNow(using Frame): Unit < Async = close(Duration.Zero)
    end Client

    trait Server:
        def bind(
            handlers: Seq[HttpHandler[?, ?, ?]],
            config: HttpServer.Config
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
