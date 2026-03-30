package kyo.internal

import kyo.*

/** Pure version of HttpBackend — all effects suspended, zero AllowUnsafe.
  *
  * Will replace HttpBackend once all old backends are deleted.
  */
private[kyo] object HttpBackend2:

    trait Client:
        type Connection

        def connectWith[A](
            host: String,
            port: Int,
            ssl: Boolean,
            connectTimeout: Maybe[Duration]
        )(
            f: Connection => A < (Async & Abort[HttpException])
        )(using Frame): A < (Async & Abort[HttpException])

        def sendWith[In, Out, A](
            conn: Connection,
            route: HttpRoute[In, Out, ?],
            request: HttpRequest[In],
            onRelease: Maybe[Result.Error[Any]] => Unit < Sync = _ => Kyo.unit
        )(
            f: HttpResponse[Out] => A < (Async & Abort[HttpException])
        )(using Frame): A < (Async & Abort[HttpException])

        def isAlive(conn: Connection)(using Frame): Boolean < Sync
        def closeNow(conn: Connection)(using Frame): Unit < Sync
        def close(conn: Connection, gracePeriod: Duration)(using Frame): Unit < Async
        def close(conn: Connection)(using Frame): Unit < Async = close(conn, 30.seconds)

        def close(gracePeriod: Duration)(using Frame): Unit < Async
        def close(using Frame): Unit < Async = close(30.seconds)
    end Client

    trait Server:
        def bind(
            handlers: Seq[HttpHandler[?, ?, ?]],
            config: HttpServerConfig
        )(using Frame): HttpBackend.Binding < (Async & Scope)
    end Server

    trait WebSocketClient:
        def connect[A, S](
            host: String,
            port: Int,
            path: String,
            ssl: Boolean,
            headers: HttpHeaders,
            config: WebSocketConfig
        )(
            f: WebSocket => A < S
        )(using Frame): A < (S & Async & Abort[HttpException])
    end WebSocketClient

end HttpBackend2
