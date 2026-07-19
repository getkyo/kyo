package kyo

import kyo.*
import kyo.internal.codec.OpenApiGenerator
import kyo.internal.server.HttpRouter
import kyo.internal.server.UnsafeServerDispatch
import kyo.internal.transport.NetConfigTranslation
import kyo.net.NetException

/** HTTP server that binds one or more handlers to a port and manages the server lifecycle.
  *
  * `HttpServer.init` returns a server managed by `Scope`, it shuts down automatically when the enclosing scope exits. Use
  * `HttpServer.initUnscoped` when you need manual lifecycle control and must close the server explicitly via `close()`. Both forms accept
  * one or more `HttpHandler` instances as varargs and an optional `HttpServerConfig`.
  *
  * When `HttpServerConfig.openApi` is configured, the server automatically generates an OpenAPI 3.x spec from all registered handlers and
  * serves it at the configured path (default: `/openapi.json`).
  *
  * The `initWith` variants combine `init` and a continuation, they bind the server and pass it to a function, which is useful for keeping
  * the server reference local to the block that uses it.
  *
  * Note: Port 0 tells the OS to assign any available port. After binding, the actual port is available via `server.port`. This is the
  * recommended approach for tests where port collisions would be a problem.
  *
  * A bind failure (for example the address is already in use) is an expected, recoverable condition: `init`, `initUnscoped`, and their
  * `initWith` variants surface it as `Abort.fail(HttpBindException)`, so a caller can recover it with `Abort.run[HttpBindException]` rather
  * than handling it as a defect.
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

    def init(handlers: HttpHandler[?, ?, ?]*)(using Frame): HttpServer < (Async & Scope & Abort[HttpBindException]) =
        init(HttpServerConfig.default)(handlers*)

    def init(port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(using
        Frame
    ): HttpServer < (Async & Scope & Abort[HttpBindException]) =
        init(HttpServerConfig.default.port(port).host(host))(handlers*)

    def init(config: HttpServerConfig)(handlers: HttpHandler[?, ?, ?]*)(using
        Frame
    ): HttpServer < (Async & Scope & Abort[HttpBindException]) =
        Scope.acquireRelease(initUnscoped(config)(handlers*))(_.closeNow)

    def initWith[A, S](handlers: HttpHandler[?, ?, ?]*)(f: HttpServer => A < S)(using
        Frame
    ): A < (S & Async & Scope & Abort[HttpBindException]) =
        init(handlers*).map(f)

    def initWith[A, S](port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(f: HttpServer => A < S)(using
        Frame
    ): A < (S & Async & Scope & Abort[HttpBindException]) =
        init(port, host)(handlers*).map(f)

    def initWith[A, S](config: HttpServerConfig)(handlers: HttpHandler[?, ?, ?]*)(f: HttpServer => A < S)(using
        Frame
    ): A < (S & Async & Scope & Abort[HttpBindException]) =
        init(config)(handlers*).map(f)

    // --- Unscoped init methods ---

    def initUnscoped(handlers: HttpHandler[?, ?, ?]*)(using Frame): HttpServer < (Async & Abort[HttpBindException]) =
        initUnscoped(HttpServerConfig.default)(handlers*)

    def initUnscoped(port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(using
        Frame
    ): HttpServer < (Async & Abort[HttpBindException]) =
        initUnscoped(HttpServerConfig.default.port(port).host(host))(handlers*)

    def initUnscoped(config: HttpServerConfig)(handlers: HttpHandler[?, ?, ?]*)(using
        Frame
    ): HttpServer < (Async & Abort[HttpBindException]) =
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
        val serverFilter =
            if config.autoFilters then HttpFilter.Factory.composedServer
            else HttpFilter.noop
        val filteredHandlers =
            if serverFilter eq HttpFilter.noop then allHandlers
            else allHandlers.map(h => HttpHandler.withFilter(h, serverFilter))
        Sync.Unsafe.defer {
            val transport   = kyo.net.NetPlatform.transport
            val listenFiber = Unsafe.init(transport, config, filteredHandlers)
            Abort.run[NetException](listenFiber.safe.get).map {
                case Result.Success(server) => server.safe
                case Result.Failure(netEx) =>
                    val bindTarget = config.unixSocket match
                        case Present(path) => path
                        case Absent        => config.host
                    Abort.fail(HttpBindException(bindTarget, config.port, new java.io.IOException(netEx.getMessage)))
                case Result.Panic(t) =>
                    throw t
            }
        }
    end initUnscoped

    def initUnscopedWith[A, S](handlers: HttpHandler[?, ?, ?]*)(f: HttpServer => A < S)(using
        Frame
    ): A < (S & Async & Abort[HttpBindException]) =
        initUnscoped(handlers*).map(f)

    def initUnscopedWith[A, S](port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(f: HttpServer => A < S)(using
        Frame
    ): A < (S & Async & Abort[HttpBindException]) =
        initUnscoped(port, host)(handlers*).map(f)

    def initUnscopedWith[A, S](config: HttpServerConfig)(handlers: HttpHandler[?, ?, ?]*)(f: HttpServer => A < S)(using
        Frame
    ): A < (S & Async & Abort[HttpBindException]) =
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
        def init(
            transport: kyo.net.Transport,
            config: HttpServerConfig,
            handlers: Seq[HttpHandler[?, ?, ?]]
        )(using AllowUnsafe, Frame): Fiber.Unsafe[Unsafe, Abort[NetException]] =
            val router = HttpRouter(handlers, config.cors)
            // Track every accepted connection so the server can close them on shutdown: the transport listener owns only
            // the listening socket, so an accepted keep-alive connection would otherwise stay open until a 60s idle timer
            // fires (it leaks whenever the peer keeps its side pooled rather than sending an EOF). The shared registry is
            // the same mechanism HttpClientBackend uses for the connections it creates.
            val registry = new kyo.internal.ConnectionRegistry[kyo.net.Connection]
            def tracked(conn: kyo.net.Connection): Unit =
                // Prune closed entries on accept (no per-connection close hook), then register this one. register closes
                // the connection itself and returns false when a shutdown races this accept, so the connection is
                // neither served nor left open, and a failing close is contained inside the registry rather than
                // surfacing on the accept path.
                registry.pruneClosed(_.isOpen)
                if registry.register(conn)(_.close()) then
                    // Pass the connection's close signal so a handler parked on a foreign await is interrupted when the
                    // connection closes (kyo.net.Connection.onClosing, completed in closeFn's win branch), plus a close
                    // hook the idle timer routes through so an idle-close also fires that signal (and flushes the
                    // outbound tail) instead of racing the WritePump re-entry.
                    UnsafeServerDispatch.serve(
                        router,
                        conn.inbound,
                        conn.outbound,
                        config,
                        Present(conn.onClosing),
                        Present(() => conn.close())
                    )
                else
                    (
                )
                end if
            end tracked
            val netConfig = NetConfigTranslation.toNetConfig(config.transportConfig)
            val listenFiber = (config.unixSocket, config.tls) match
                case (Present(path), _) =>
                    transport.listenUnix(path, config.backlog, netConfig)(tracked)
                case (Absent, Present(tls)) =>
                    NetConfigTranslation.listenTls(
                        transport,
                        config.host,
                        config.port,
                        config.backlog,
                        tls,
                        config.transportConfig
                    )(tracked)
                case _ =>
                    transport.listen(config.host, config.port, config.backlog, netConfig)(tracked)
            listenFiber.map(listener => new ListenerUnsafe(listener, transport, registry))
        end init
    end Unsafe

    // --- Private implementations ---

    /** Unsafe implementation wrapping a Listener from Transport, plus the registry of accepted connections it owns. The server owns its
      * transport, so closing the server also closes that transport to release its driver/pool.
      */
    final private class ListenerUnsafe(
        listener: kyo.net.Listener,
        transport: kyo.net.Transport,
        registry: kyo.internal.ConnectionRegistry[kyo.net.Connection]
    )(using allow: AllowUnsafe) extends Unsafe:
        private val closedPromise            = Promise.Unsafe.init[Unit, Any]()
        private val httpAddress: HttpAddress = NetConfigTranslation.toHttpAddress(listener.address)

        def port: Int = httpAddress match
            case HttpAddress.Tcp(_, p) => p
            case HttpAddress.Unix(_)   => -1

        def host: String = httpAddress match
            case HttpAddress.Tcp(h, _) => h
            case HttpAddress.Unix(_)   => "localhost"

        def address: HttpAddress = httpAddress

        def closeFiber(gracePeriod: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
            listener.close()       // stop accepting new connections first
            registry.markClosing() // any accept racing the close now closes itself in `tracked` instead of being orphaned

            def forceCloseAndComplete(): Unit =
                registry.closeAll(_.close())
                // The transport is NOT closed here. It is process-shared across every client and server using the same settings, so closing it
                // would take every co-tenant's connections down with this server. What this server owns is its listener (closed above, which
                // releases the bound port) and its accepted connections (closed just above), and those are what shutting it down must reclaim.
                discard(closedPromise.completeDiscard(Result.succeed(())))
            end forceCloseAndComplete

            if gracePeriod <= Duration.Zero then
                // closeNow: force-close every accepted connection at once, the path the Scope finalizer (`_.closeNow`)
                // takes. Accepted keep-alive connections otherwise outlive the listening socket, held open until the
                // 60s idle timer fires.
                forceCloseAndComplete()
            else
                // Graceful: let in-flight connections run for the grace period, then force-close whatever remains.
                discard(Clock.live.unsafe.sleep(gracePeriod).onComplete(_ => forceCloseAndComplete()))
            end if
            closedPromise
        end closeFiber

        def awaitFiber: Fiber.Unsafe[Unit, Any] = closedPromise
    end ListenerUnsafe

end HttpServer
