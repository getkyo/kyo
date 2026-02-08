package kyo

/** Minimal backend abstraction for HTTP client and server I/O.
  *
  * Backend implementations only handle raw transport: creating connections and binding servers. All protocol-level behavior (redirect
  * following, retry, timeout, connection pooling, routing, filtering) lives in shared HttpClient/HttpServer code.
  *
  * Platform-specific implementations:
  *   - JVM: NettyBackend (Netty 4.2 with epoll/kqueue)
  *   - JS: FetchBackend (browser/Node.js Fetch API)
  */
abstract class Backend:

    /** Create a factory for raw transport connections. No pooling — that's handled by shared code. */
    def connectionFactory(maxResponseSizeBytes: Int, daemon: Boolean)(using AllowUnsafe): Backend.ConnectionFactory

    /** Bind a server to a port and start accepting requests. */
    def server(
        config: HttpServer.Config,
        handler: Backend.ServerHandler
    )(using Frame): Backend.Server < Async

end Backend

object Backend:

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
    end ConnectionFactory

    /** A single transport connection. Handles one request at a time. */
    abstract class Connection:

        /** Send a buffered request and return a buffered response. */
        def send(request: HttpRequest[HttpBody.Bytes])(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError])

        /** Send a request and stream the response. */
        def stream(request: HttpRequest[?])(using Frame): HttpResponse[HttpBody.Streamed] < (Async & Scope & Abort[HttpError])

        /** Whether the underlying transport connection is still alive. */
        def isAlive: Boolean

        /** Graceful close. */
        def close(using Frame): Unit < Async

        /** Synchronous fire-and-forget close for pool cleanup. */
        private[kyo] def closeAbruptly(): Unit
    end Connection

    /** Running HTTP server instance. */
    abstract class Server:
        def port: Int
        def host: String
        def stop(gracePeriod: Duration)(using Frame): Unit < Async
        def await(using Frame): Unit < Async
    end Server

    /** Handler interface that backends call to dispatch requests. Built by shared HttpServer code from Router + Filter. */
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

end Backend
