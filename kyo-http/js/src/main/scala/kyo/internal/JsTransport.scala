package kyo.internal

import kyo.*
import kyo.internal.transport.*
import kyo.scheduler.IOPromise
import scala.scalajs.js

/** JS TCP transport delegating to Node.js `net` and `tls` modules.
  *
  * All connections are managed as Node.js socket objects. Plain TCP sockets use `net.connect` / `net.createServer`; TLS sockets use
  * `tls.connect` / `tls.createServer`. The handshake is handled entirely by Node.js — callers receive a connection only after the TLS
  * `secureConnect` / `secureConnection` event fires.
  *
  * Data flow is pull-based: each socket is paused immediately after creation, and `resume()` is called only when a read is pending. This
  * prevents unbounded buffering of incoming data.
  *
  * Note: Unix-domain sockets do not support TCP_NODELAY — the `setNoDelay` call is skipped for those connections.
  */
final private[kyo] class JsTransport private (
    val pool: IoDriverPool[JsHandle],
    private val channelCapacity: Int
) extends Transport[JsHandle]:

    def connect(host: String, port: Int)(using AllowUnsafe, Frame): Fiber.Unsafe[Connection[JsHandle], Abort[Closed]] =
        val socket = js.Dynamic.global.require("net").connect(port, host)
        connectSocket(socket, host, port, tcpNoDelay = true, connectEvent = "connect")
    end connect

    def listen(host: String, port: Int, backlog: Int)(
        handler: Connection[JsHandle] => Unit < Async
    )(using AllowUnsafe, Frame): Fiber.Unsafe[Listener, Abort[Closed]] =
        val server = js.Dynamic.global.require("net").createServer()
        listenServer(server, host, port, backlog, tcpNoDelay = true, connectionEvent = "connection", handler)
    end listen

    def connect(host: String, port: Int, tls: HttpTlsConfig)(using AllowUnsafe, Frame): Fiber.Unsafe[Connection[JsHandle], Abort[Closed]] =
        val opts = js.Dynamic.literal(host = host, port = port)
        opts.rejectUnauthorized = !tls.trustAll
        tls.sniHostname match
            case Present(sni) => opts.servername = sni
            case Absent       => opts.servername = host
        val socket = js.Dynamic.global.require("tls").connect(opts)
        // TLS sockets emit "secureConnect" after handshake (not "connect" which fires on raw TCP)
        connectSocket(socket, host, port, tcpNoDelay = false, connectEvent = "secureConnect")
    end connect

    def listen(host: String, port: Int, backlog: Int, tls: HttpTlsConfig)(
        handler: Connection[JsHandle] => Unit < Async
    )(using AllowUnsafe, Frame): Fiber.Unsafe[Listener, Abort[Closed]] =
        val serverOpts = js.Dynamic.literal()
        tls.certChainPath match
            case Present(p) => serverOpts.cert = js.Dynamic.global.require("fs").readFileSync(p, "utf8")
            case Absent     => ()
        tls.privateKeyPath match
            case Present(p) => serverOpts.key = js.Dynamic.global.require("fs").readFileSync(p, "utf8")
            case Absent     => ()
        val server = js.Dynamic.global.require("tls").createServer(serverOpts)
        // TLS servers emit "secureConnection" after handshake (not "connection" which fires on raw TCP)
        listenServer(server, host, port, backlog, tcpNoDelay = false, connectionEvent = "secureConnection", handler)
    end listen

    // -- shared helpers --

    private def connectSocket(socket: js.Dynamic, host: String, port: Int, tcpNoDelay: Boolean, connectEvent: String)(
        using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[Connection[JsHandle], Abort[Closed]] =
        val promise = new IOPromise[Closed, Connection[JsHandle]]
        val driver  = pool.next()

        // Pause immediately - kyo controls data flow
        discard(socket.pause())

        discard(socket.once(
            connectEvent,
            { () =>
                if tcpNoDelay then discard(socket.setNoDelay(true))
                val handle     = JsHandle.init(socket, driver)
                val connection = Connection.init(handle, driver, channelCapacity)
                connection.start()
                promise.completeDiscard(Result.succeed(connection))
            }: js.Function0[Unit]
        ))

        discard(socket.once(
            "error",
            { (err: js.Dynamic) =>
                val msg = if js.typeOf(err.message) == "string" then err.message.toString else "unknown error"
                promise.completeDiscard(Result.fail(Closed("JsTransport", summon[Frame], s"connect failed to $host:$port: $msg")))
            }: js.Function1[js.Dynamic, Unit]
        ))

        promise.asInstanceOf[Fiber.Unsafe[Connection[JsHandle], Abort[Closed]]]
    end connectSocket

    private def listenServer(
        server: js.Dynamic,
        host: String,
        port: Int,
        backlog: Int,
        tcpNoDelay: Boolean,
        connectionEvent: String,
        handler: Connection[JsHandle] => Unit < Async
    )(using AllowUnsafe, Frame): Fiber.Unsafe[Listener, Abort[Closed]] =
        val promise  = new IOPromise[Closed, Listener]
        val listener = new JsListener(server, HttpAddress.Tcp(host, port))

        discard(server.on(
            connectionEvent,
            { (socket: js.Dynamic) =>
                discard(socket.pause())
                if tcpNoDelay then discard(socket.setNoDelay(true))

                val connDriver = pool.next()
                val handle     = JsHandle.init(socket, connDriver)
                val connection = Connection.init(handle, connDriver, channelCapacity)
                connection.start()

                // Spawn handler fiber
                // Connection lifecycle managed by pumps via closeFn
                // Do NOT close connection when handler returns - UnsafeServerDispatch returns
                // immediately and manages lifecycle via idle timeout / parser EOF / channel close
                val handlerFiber = Sync.Unsafe.evalOrThrow {
                    Fiber.initUnscoped {
                        Abort.run(handler(connection)).map { result =>
                            result match
                                case Result.Panic(e) =>
                                    Log.live.unsafe.error(s"Connection handler panic", e)
                                case _ => ()
                            end match
                        }
                    }
                }
                discard(handlerFiber)
            }: js.Function1[js.Dynamic, Unit]
        ))

        discard(server.on(
            "error",
            { (err: js.Dynamic) =>

                val msg = if js.typeOf(err.message) == "string" then err.message.toString else "unknown error"
                promise.completeDiscard(Result.fail(Closed("JsTransport", summon[Frame], s"listen failed on $host:$port: $msg")))
            }: js.Function1[js.Dynamic, Unit]
        ))

        discard(server.listen(
            port,
            host,
            backlog,
            { () =>

                val addr       = server.address()
                val actualPort = addr.port.asInstanceOf[Int]
                val actualHost = addr.address.asInstanceOf[String]
                listener.setAddress(actualPort, actualHost)
                promise.completeDiscard(Result.succeed(listener))
            }: js.Function0[Unit]
        ))

        promise.asInstanceOf[Fiber.Unsafe[Listener, Abort[Closed]]]
    end listenServer

    def connectUnix(path: String)(using AllowUnsafe, Frame): Fiber.Unsafe[Connection[JsHandle], Abort[Closed]] =
        val promise = new IOPromise[Closed, Connection[JsHandle]]
        val driver  = pool.next()

        val net    = js.Dynamic.global.require("net")
        val socket = net.createConnection(js.Dynamic.literal(path = path))

        // Pause immediately - kyo controls data flow
        discard(socket.pause())

        discard(socket.once(
            "connect",
            { () =>
                // Unix sockets do not support TCP_NODELAY — skip setNoDelay
                val handle     = JsHandle.init(socket, driver)
                val connection = Connection.init(handle, driver, channelCapacity)
                connection.start()
                promise.completeDiscard(Result.succeed(connection))
            }: js.Function0[Unit]
        ))

        discard(socket.once(
            "error",
            { (err: js.Dynamic) =>

                val msg = if js.typeOf(err.message) == "string" then err.message.toString else "unknown error"
                promise.completeDiscard(Result.fail(Closed("JsTransport", summon[Frame], s"connectUnix failed to $path: $msg")))
            }: js.Function1[js.Dynamic, Unit]
        ))

        promise.asInstanceOf[Fiber.Unsafe[Connection[JsHandle], Abort[Closed]]]
    end connectUnix

    def listenUnix(path: String, backlog: Int)(
        handler: Connection[JsHandle] => Unit < Async
    )(using AllowUnsafe, Frame): Fiber.Unsafe[Listener, Abort[Closed]] =
        val promise = new IOPromise[Closed, Listener]

        val net    = js.Dynamic.global.require("net")
        val server = net.createServer()

        val listener = new JsListener(server, HttpAddress.Unix(path))

        discard(server.on(
            "connection",
            { (socket: js.Dynamic) =>
                discard(socket.pause())
                // Unix sockets do not support TCP_NODELAY — skip setNoDelay

                val connDriver = pool.next()
                val handle     = JsHandle.init(socket, connDriver)
                val connection = Connection.init(handle, connDriver, channelCapacity)
                connection.start()

                val handlerFiber = Sync.Unsafe.evalOrThrow {
                    Fiber.initUnscoped {
                        Abort.run(handler(connection)).map { result =>
                            result match
                                case Result.Panic(e) =>
                                    Log.live.unsafe.error(s"Connection handler panic", e)
                                case _ => ()
                            end match
                        }
                    }
                }
                discard(handlerFiber)
            }: js.Function1[js.Dynamic, Unit]
        ))

        discard(server.on(
            "error",
            { (err: js.Dynamic) =>

                val msg = if js.typeOf(err.message) == "string" then err.message.toString else "unknown error"
                promise.completeDiscard(Result.fail(Closed("JsTransport", summon[Frame], s"listenUnix failed on $path: $msg")))
            }: js.Function1[js.Dynamic, Unit]
        ))

        discard(server.listen(
            js.Dynamic.literal(path = path, backlog = backlog),
            { () =>

                listener.setAddress(-1, path)
                promise.completeDiscard(Result.succeed(listener))
            }: js.Function0[Unit]
        ))

        promise.asInstanceOf[Fiber.Unsafe[Listener, Abort[Closed]]]
    end listenUnix

    def close()(using AllowUnsafe, Frame): Unit =
        pool.close()

end JsTransport

/** Factory for `JsTransport`. Creates a pool of `JsIoDriver` instances (usually just one, since JS is single-threaded). */
private[kyo] object JsTransport:
    def init(poolSize: Int, channelCapacity: Int)(using AllowUnsafe, Frame): JsTransport =
        val drivers = Array.tabulate[IoDriver[JsHandle]](poolSize)(i => JsIoDriver.init())
        val pool    = IoDriverPool.init(drivers)
        pool.start()
        new JsTransport(pool, channelCapacity)
    end init
end JsTransport

/** Active server-side listener wrapping a Node.js TCP or Unix-domain server. Port and host are set once in the listen callback and
  * subsequently read-only. An `AtomicBoolean` guards the closed flag for consistency with other platform implementations even though JS is
  * single-threaded.
  */
final private class JsListener(
    private val server: js.Dynamic,
    private var _address: HttpAddress
) extends Listener:
    // JS is single-threaded, but closed flag uses atomic for consistency with other listeners
    private val closedFlag = new java.util.concurrent.atomic.AtomicBoolean(false)

    // Set once in listen callback, read later. JS single-threaded so vars are safe.
    private var _port: Int    = 0
    private var _host: String = ""

    def setAddress(port: Int, host: String): Unit =
        _port = port
        _host = host
        _address match
            case _: HttpAddress.Tcp => _address = HttpAddress.Tcp(host, port)
            case _                  => ()
    end setAddress

    def port: Int            = _port
    def host: String         = _host
    def address: HttpAddress = _address

    def isClosed: Boolean = closedFlag.get()

    def close()(using AllowUnsafe, Frame): Unit =
        if closedFlag.compareAndSet(false, true) then
            discard(server.close())
        end if
    end close
end JsListener
