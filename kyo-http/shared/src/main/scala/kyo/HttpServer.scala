package kyo

import kyo.*
import kyo.internal.HttpPlatformTransport
import kyo.internal.codec.OpenApiGenerator
import kyo.internal.server.HttpRouter
import kyo.internal.server.UnsafeServerDispatch
import kyo.internal.transport.Transport

/** HTTP server that binds one or more handlers to a port and manages the server lifecycle.
  *
  * `HttpServer.init` returns a server managed by `Scope` — it shuts down automatically when the enclosing scope exits. Use
  * `HttpServer.initUnscoped` when you need manual lifecycle control and must close the server explicitly via `close()`. Both forms accept
  * one or more `HttpHandler` instances as varargs and an optional `HttpServerConfig`.
  *
  * When `HttpServerConfig.openApi` is configured, the server automatically generates an OpenAPI 3.x spec from all registered handlers and
  * serves it at the configured path (default: `/openapi.json`).
  *
  * The `initWith` variants combine `init` and a continuation — they bind the server and pass it to a function, which is useful for keeping
  * the server reference local to the block that uses it.
  *
  * Note: Port 0 tells the OS to assign any available port. After binding, the actual port is available via `server.port`. This is the
  * recommended approach for tests where port collisions would be a problem.
  *
  * WARNING: Binding to `0.0.0.0` (the default host) exposes the server on all network interfaces. Restrict to `127.0.0.1` for
  * localhost-only services.
  *
  * @see
  *   [[kyo.HttpHandler]] The endpoint implementations to register
  * @see
  *   [[kyo.HttpServerConfig]] Controls port, host, content limits, and optional features
  * @see
  *   [[kyo.HttpAddress]] The address type returned by `server.address`
  * @see
  *   [[kyo.HttpTlsConfig]] TLS termination configuration
  */
opaque type HttpServer = HttpServer.Unsafe

object HttpServer:

    extension (self: HttpServer)
        /** Returns the address the server is bound to. */
        def address: HttpAddress = self.address

        /** Returns the port the server is listening on. Returns -1 for Unix sockets. */
        def port: Int = self.port

        /** Returns the host the server is bound to. */
        def host: String = self.host

        /** Closes the server with a grace period for in-flight requests. */
        def close(gracePeriod: Duration)(using Frame): Unit < Async =
            Sync.Unsafe.defer(self.closeFiber(gracePeriod).safe.get)

        /** Closes the server with a default grace period (30 seconds). */
        def close(using Frame): Unit < Async = close(30.seconds)

        /** Closes the server immediately without waiting for in-flight requests. */
        def closeNow(using Frame): Unit < Async = close(Duration.Zero)

        /** Awaits until the server closes. */
        def await(using Frame): Unit < Async =
            Sync.Unsafe.defer(self.awaitFiber.safe.get)

        /** Returns the underlying unsafe server instance. */
        def unsafe: Unsafe = self
    end extension

    // --- Scoped init methods ---

    def init(handlers: HttpHandler[?, ?, ?]*)(using Frame): HttpServer < (Async & Scope) =
        init(HttpServerConfig.default)(handlers*)

    def init(port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(using Frame): HttpServer < (Async & Scope) =
        init(HttpServerConfig.default.port(port).host(host))(handlers*)

    def init(config: HttpServerConfig)(handlers: HttpHandler[?, ?, ?]*)(using Frame): HttpServer < (Async & Scope) =
        Scope.acquireRelease(initUnscoped(config)(handlers*))(_.closeNow)

    def initWith[A, S](handlers: HttpHandler[?, ?, ?]*)(f: HttpServer => A < S)(using Frame): A < (S & Async & Scope) =
        init(handlers*).map(f)

    def initWith[A, S](port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(f: HttpServer => A < S)(using
        Frame
    ): A < (S & Async & Scope) =
        init(port, host)(handlers*).map(f)

    def initWith[A, S](config: HttpServerConfig)(handlers: HttpHandler[?, ?, ?]*)(f: HttpServer => A < S)(using
        Frame
    ): A < (S & Async & Scope) =
        init(config)(handlers*).map(f)

    // --- Unscoped init methods ---

    def initUnscoped(handlers: HttpHandler[?, ?, ?]*)(using Frame): HttpServer < Async =
        initUnscoped(HttpServerConfig.default)(handlers*)

    def initUnscoped(port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(using Frame): HttpServer < Async =
        initUnscoped(HttpServerConfig.default.port(port).host(host))(handlers*)

    def initUnscoped(config: HttpServerConfig)(handlers: HttpHandler[?, ?, ?]*)(using Frame): HttpServer < Async =
        val allHandlers = config.openApi match
            case Present(ep) =>
                val spec = OpenApiGenerator.generate(
                    handlers,
                    OpenApiGenerator.Config(ep.title, ep.version, ep.description)
                )
                val json      = HttpOpenApi.toJson(spec)
                val jsonBytes = Span.fromUnsafe(json.getBytes("UTF-8"))
                handlers :+ HttpHandler.init(HttpRoute.getRaw(ep.path).response(_.bodyBinary)) { _ =>
                    HttpResponse.ok(jsonBytes).addHeader("Content-Type", "application/json")
                }
            case Absent =>
                handlers
        Sync.Unsafe.defer {
            val listenFiber = Unsafe.init(HttpPlatformTransport.transport, config, allHandlers)
            Abort.run[Closed](listenFiber.safe.get).map {
                case Result.Success(server) => server.safe
                case Result.Failure(closed) =>
                    val bindTarget = config.unixSocket match
                        case Present(path) => path
                        case Absent        => config.host
                    throw HttpBindException(bindTarget, config.port, new java.io.IOException(closed.getMessage))
                case Result.Panic(t) => throw t
            }
        }
    end initUnscoped

    def initUnscopedWith[A, S](handlers: HttpHandler[?, ?, ?]*)(f: HttpServer => A < S)(using Frame): A < (S & Async) =
        initUnscoped(handlers*).map(f)

    def initUnscopedWith[A, S](port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(f: HttpServer => A < S)(using
        Frame
    ): A < (S & Async) =
        initUnscoped(port, host)(handlers*).map(f)

    def initUnscopedWith[A, S](config: HttpServerConfig)(handlers: HttpHandler[?, ?, ?]*)(f: HttpServer => A < S)(using
        Frame
    ): A < (S & Async) =
        initUnscoped(config)(handlers*).map(f)

    // --- Unsafe API ---

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    abstract class Unsafe:
        /** Returns the port the server is listening on. Returns -1 for Unix sockets. */
        def port: Int

        /** Returns the host the server is bound to. */
        def host: String

        /** Returns the address the server is bound to. */
        def address: HttpAddress

        /** Closes the server with a grace period for in-flight requests. Returns a fiber that completes when closed. */
        def closeFiber(gracePeriod: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any]

        /** Returns a fiber that completes when the server closes. */
        def awaitFiber: Fiber.Unsafe[Unit, Any]

        /** Returns the safe wrapper for this unsafe server. */
        final def safe: HttpServer = this
    end Unsafe

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:
        /** Creates an unsafe HTTP server using the given transport.
          *
          * @param transport
          *   The transport layer to use for connections
          * @param config
          *   Server configuration
          * @param handlers
          *   HTTP handlers to register
          * @return
          *   A fiber that completes with the unsafe server once bound
          */
        def init[H](
            transport: Transport[H],
            config: HttpServerConfig,
            handlers: Seq[HttpHandler[?, ?, ?]]
        )(using AllowUnsafe, Frame): Fiber.Unsafe[Unsafe, Abort[Closed]] =
            val router = HttpRouter(handlers, config.cors)
            val listenFiber = (config.unixSocket, config.tls) match
                case (Present(path), _) =>
                    transport.listenUnix(path, config.backlog) { conn =>
                        UnsafeServerDispatch.serve(router, conn.inbound, conn.outbound, config)
                    }
                case (Absent, Present(tls)) =>
                    transport.listen(config.host, config.port, config.backlog, tls) { conn =>
                        UnsafeServerDispatch.serve(router, conn.inbound, conn.outbound, config)
                    }
                case _ =>
                    transport.listen(config.host, config.port, config.backlog) { conn =>
                        UnsafeServerDispatch.serve(router, conn.inbound, conn.outbound, config)
                    }
            listenFiber.map { listener =>
                new ListenerUnsafe(listener)
            }
        end init
    end Unsafe

    // --- Private implementations ---

    /** Unsafe implementation wrapping a Listener from Transport. */
    final private class ListenerUnsafe(
        listener: kyo.internal.transport.Listener
    )(using allow: AllowUnsafe) extends Unsafe:
        private val closedPromise = Promise.Unsafe.init[Unit, Any]()

        def port: Int = listener.address match
            case HttpAddress.Tcp(_, p) => p
            case HttpAddress.Unix(_)   => -1

        def host: String = listener.address match
            case HttpAddress.Tcp(h, _) => h
            case HttpAddress.Unix(_)   => "localhost"

        def address: HttpAddress = listener.address

        def closeFiber(gracePeriod: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
            listener.close()
            discard(closedPromise.completeDiscard(Result.succeed(())))
            closedPromise
        end closeFiber

        def awaitFiber: Fiber.Unsafe[Unit, Any] = closedPromise
    end ListenerUnsafe

end HttpServer
