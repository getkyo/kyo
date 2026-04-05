package kyo

import kyo.*

/** Platform abstraction layer for HTTP client and server implementations.
  *
  * `HttpBackend` defines the traits that platform backends implement. Users typically don't interact with backends directly — `HttpClient`
  * and `HttpServer` use the platform-appropriate backend automatically.
  *
  * The `Client` trait is connection-oriented: `connectWith` establishes a connection, `sendWith` sends a request through it. This design
  * lets `HttpClient` manage connection pooling independently of the backend.
  *
  * WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code.
  *
  * @see
  *   [[kyo.HttpClient]] The user-facing client that wraps backend connections with pooling
  * @see
  *   [[kyo.HttpServer]] The user-facing server that delegates to the backend
  */
object HttpBackend:

    trait Client:
        type Connection

        def connectWith[A](
            url: HttpUrl,
            connectTimeout: Maybe[Duration]
        )(
            f: Connection => A < (Async & Abort[HttpException])
        )(using Frame): A < (Async & Abort[HttpException])

        def sendWith[In, Out, A](
            conn: Connection,
            route: HttpRoute[In, Out, ?],
            request: HttpRequest[In],
            onRelease: Maybe[Result.Error[Any]] => Unit < Async = _ => Kyo.unit
        )(
            f: HttpResponse[Out] => A < (Async & Abort[HttpException])
        )(using Frame): A < (Async & Abort[HttpException])

        def isAlive(conn: Connection)(using Frame): Boolean < Sync
        def closeNow(conn: Connection)(using Frame): Unit < Async
        def close(conn: Connection, gracePeriod: Duration)(using Frame): Unit < Async
        def close(conn: Connection)(using Frame): Unit < Async = close(conn, 30.seconds)

        def close(gracePeriod: Duration)(using Frame): Unit < Async
        def close(using Frame): Unit < Async = close(30.seconds)
    end Client

    trait Server:
        def bind(
            handlers: Seq[HttpHandler[?, ?, ?]],
            config: HttpServerConfig
        )(using Frame): Binding < (Async & Scope)
    end Server

    abstract class Binding:
        def address: HttpServerAddress
        def close(gracePeriod: Duration)(using Frame): Unit < Async
        def close(using Frame): Unit < Async    = close(30.seconds)
        def closeNow(using Frame): Unit < Async = close(Duration.Zero)
        def await(using Frame): Unit < Async
    end Binding

    /** Platform abstraction for WebSocket client connections.
      *
      * Separate from `Client` because WebSocket has a fundamentally different lifecycle — it upgrades an HTTP connection to a persistent
      * bidirectional channel rather than following request-response semantics.
      */
    trait WebSocketClient:
        def connect[A, S](
            url: HttpUrl,
            headers: HttpHeaders,
            config: WebSocketConfig
        )(
            f: WebSocket => A < S
        )(using Frame): A < (S & Async & Abort[HttpException])
    end WebSocketClient

end HttpBackend
