package kyo

/** Minimal transport abstractions for HTTP client and server I/O.
  *
  * Backend implementations handle only raw transport: creating connections and binding servers. All protocol-level behavior (redirect
  * following, retry, timeout, connection pooling, routing, filtering) lives in shared `HttpClient`/`HttpServer` code. Users interact with
  * `HttpClient` and `HttpServer` directly — Backend contains extension points for custom transport implementations.
  *
  * Client and server transports are separate traits so the type system enforces platform capabilities: a platform that only supports HTTP
  * clients (e.g. Scala Native with libcurl) implements only `Backend.Client`, and attempting to use `HttpServer` without a `Backend.Server`
  * is a compile-time error.
  *
  * Platform-specific implementations:
  *   - JVM: NettyClientBackend / NettyServerBackend (Netty 4.2 with epoll/kqueue) — client + server
  *   - JS: FetchClientBackend (Fetch API) — client; NodeServerBackend (Node.js http) — server
  *   - Native: CurlBackend (libcurl) — client only
  *
  * @see
  *   [[kyo.HttpClient]]
  * @see
  *   [[kyo.HttpServer]]
  */
object Backend:

    /** Client transport provider. Implementations create raw connections to remote hosts. */
    trait Client:

        /** Create a factory for raw transport connections. No pooling — that's handled by shared code. */
        def connectionFactory(maxResponseSizeBytes: Int, daemon: Boolean)(using AllowUnsafe): ConnectionFactory

    end Client

    /** Server transport provider. Implementations bind to a port and accept incoming requests.
      *
      * TODO: Review server() params — `tcpFastOpen` and `flushConsolidationLimit` are Netty-specific. The goal is that backends are
      * swappable with the closest behavior possible; these params should eventually be transport-generic or moved to backend-specific
      * configuration.
      */
    trait Server:

        /** Bind a server to a port and start accepting requests. */
        def server(
            port: Int,
            host: String,
            maxContentLength: Int,
            backlog: Int,
            keepAlive: Boolean,
            tcpFastOpen: Boolean,
            flushConsolidationLimit: Int,
            handler: ServerHandler
        )(using Frame): Server.Binding < Async

    end Server

    object Server:

        /** Running HTTP server instance bound to a port. */
        abstract class Binding:
            def port: Int
            def host: String
            def close(gracePeriod: Duration)(using Frame): Unit < Async
            def close(using Frame): Unit < Async    = close(30.seconds)
            def closeNow(using Frame): Unit < Async = close(Duration.Zero)
            def await(using Frame): Unit < Async
        end Binding

    end Server

    /** Factory for creating raw connections to remote hosts. */
    abstract class ConnectionFactory:

        /** Open a new connection to the given host:port. */
        def connect(
            host: String,
            port: Int,
            ssl: Boolean,
            connectTimeout: Maybe[Duration]
        )(using Frame): Connection < (Async & Abort[HttpError])

        /** Shut down transport resources (event loops, thread pools). */
        def close(gracePeriod: Duration)(using Frame): Unit < Async

        def close(using Frame): Unit < Async = close(30.seconds)

        def closeNow(using Frame): Unit < Async = close(Duration.Zero)
    end ConnectionFactory

    /** A single transport connection. Handles one request at a time. */
    abstract class Connection:

        /** Send a buffered request and return a buffered response. */
        def send(request: HttpRequest[HttpBody.Bytes])(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError])

        /** Send a request and stream the response. */
        def stream(request: HttpRequest[?])(using Frame): HttpResponse[HttpBody.Streamed] < (Async & Scope & Abort[HttpError])

        /** Whether the underlying transport connection is still alive. */
        def isAlive(using AllowUnsafe): Boolean

        /** Graceful close. */
        def close(using Frame): Unit < Async

        /** Synchronous fire-and-forget close for pool cleanup. */
        private[kyo] def closeAbruptly()(using AllowUnsafe): Unit
    end Connection

    /** Handler interface that backends call to dispatch requests.
      *
      * This indirection decouples backends from the routing/filter stack. Backends only need to parse HTTP on the wire and call these
      * methods — they don't import or depend on HttpServer, HttpRouter, or HttpFilter. The shared HttpServer.init builds a ServerHandler
      * that wires together the router, filter chain, and cookie config, then passes it to Backend.Server.server(). This keeps each backend
      * focused on transport.
      */
    abstract class ServerHandler:

        /** Fast-path rejection check (404, 405) before reading the body. Returns Absent if the route exists. */
        def reject(method: HttpRequest.Method, path: String): Maybe[HttpResponse[HttpBody.Bytes]]

        /** Whether the matched handler expects a streaming request body. */
        def isStreaming(method: HttpRequest.Method, path: String): Boolean

        /** Handle a buffered request. Called after the full body has been read. */
        def handle(request: HttpRequest[HttpBody.Bytes])(using Frame): HttpResponse[?] < Async

        /** Handle a streaming request. Called immediately when headers arrive; body arrives via the stream. */
        def handleStreaming(request: HttpRequest[HttpBody.Streamed])(using Frame): HttpResponse[?] < Async
    end ServerHandler

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    private[kyo] object Unsafe:

        /** Launch an async computation in a new fiber from an unsafe context.
          *
          * Fiber.Unsafe.init only wraps an already-resolved value — it doesn't schedule work on the async runtime. This method uses
          * Fiber.initUnscoped to actually dispatch the computation on the scheduler, stripping the resulting Sync effect via evalOrThrow.
          */
        def launchFiber[A](v: => A < Async)(using AllowUnsafe, Frame): Fiber[A, Any] =
            Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(v))

    end Unsafe

end Backend
