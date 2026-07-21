package kyo.net.internal

import kyo.*
import kyo.net.Connection as NetConnection
import kyo.net.Listener as NetListener
import kyo.net.NetAddress
import kyo.net.NetTlsConfig
import kyo.net.internal.transport.*
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
  *
  * TLS introspection: `Connection.serverCertificateHash` is wired through `installCertHashFn`, which reads the leaf peer-certificate DER
  * via Node's `tls.TLSSocket.getPeerCertificate(true).raw` and SHA-256-hashes it with `crypto.createHash("sha256")`. Used by SCRAM-PLUS
  * channel binding (RFC 5929 tls-server-end-point).
  */
final private[kyo] class JsTransport private (
    val pool: IoDriverPool[JsHandle],
    private val channelCapacity: Int
) extends TransportImpl[JsHandle]:

    def connect(host: String, port: Int)(using AllowUnsafe, Frame): Fiber.Unsafe[NetConnection.Unsafe, Abort[Closed]] =
        val socket = js.Dynamic.global.require("net").connect(port, host)
        connectSocket(socket, host, port, tcpNoDelay = true, connectEvent = "connect")
    end connect

    def listen(host: String, port: Int, backlog: Int)(
        handler: NetConnection.Unsafe => Unit < Async
    )(using AllowUnsafe, Frame): Fiber.Unsafe[NetListener.Unsafe, Abort[Closed]] =
        val server = js.Dynamic.global.require("net").createServer()
        listenServer(server, host, port, backlog, tcpNoDelay = true, connectionEvent = "connection", handler)
    end listen

    def connect(host: String, port: Int, tls: NetTlsConfig)(using AllowUnsafe, Frame): Fiber.Unsafe[NetConnection.Unsafe, Abort[Closed]] =
        val opts = js.Dynamic.literal(host = host, port = port)
        // rejectUnauthorized drives certificate-chain validation only; hostname check is a
        // separate concern routed through checkServerIdentity.
        opts.rejectUnauthorized = !tls.trustAll
        if !tls.hostnameVerification then
            // No-op identity check: cert chain still validated when rejectUnauthorized=true,
            // but SAN/CN vs servername mismatch is ignored (verify-ca semantics).
            opts.checkServerIdentity = ({ (_: js.Any, _: js.Any) => js.undefined }: js.Function2[js.Any, js.Any, js.Any])
        end if
        tls.sniHostname match
            case Present(sni) => opts.servername = sni
            case Absent       => opts.servername = host
        // Custom CA — Node's tls.connect loads this as the only trust anchor when present.
        tls.caCertPath match
            case Present(path) =>
                opts.ca = js.Dynamic.global.require("fs").readFileSync(path, "utf8")
            case Absent => ()
        end match
        val socket = js.Dynamic.global.require("tls").connect(opts)
        // TLS sockets emit "secureConnect" after handshake (not "connect" which fires on raw TCP)
        connectSocket(socket, host, port, tcpNoDelay = false, connectEvent = "secureConnect")
    end connect

    def listen(host: String, port: Int, backlog: Int, tls: NetTlsConfig)(
        handler: NetConnection.Unsafe => Unit < Async
    )(using AllowUnsafe, Frame): Fiber.Unsafe[NetListener.Unsafe, Abort[Closed]] =
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
    ): Fiber.Unsafe[NetConnection.Unsafe, Abort[Closed]] =
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
                // Wire upgrade function so upgradeToTls dispatches to this transport.
                connection.upgradeFn = (tls, frame) =>
                    given Frame = frame
                    Sync.Unsafe.defer {
                        given AllowUnsafe = AllowUnsafe.embrace.danger
                        upgradeToTls(connection, tls, channelCapacity).safe
                    }.map { fiber =>
                        fiber.use { newUnsafeConn =>
                            newUnsafeConn.safe
                        }
                    }
                // For TLS handshakes (secureConnect), install certHashFn so SCRAM-PLUS channel
                // binding can compute tls-server-end-point (RFC 5929) from the peer cert.
                if connectEvent == "secureConnect" then installCertHashFn(connection, socket)
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

        promise.asInstanceOf[Fiber.Unsafe[NetConnection.Unsafe, Abort[Closed]]]
    end connectSocket

    /** Install the certHashFn on `connection` by reading the leaf peer certificate from the post-handshake TLS socket and SHA-256-hashing
      * its DER bytes (RFC 5929 tls-server-end-point). Used by SCRAM-PLUS channel binding.
      *
      * Node's `tls.TLSSocket.getPeerCertificate(true)` returns an object with a `.raw` Buffer holding the DER bytes; Node's `crypto`
      * `createHash("sha256").update(buf).digest()` returns a 32-byte Buffer.
      */
    private def installCertHashFn(connection: Connection[JsHandle], tlsSocket: js.Dynamic): Unit =
        connection.certHashFn = () =>
            val cert = tlsSocket.getPeerCertificate(true)
            if js.isUndefined(cert) || cert == null || js.isUndefined(cert.raw) then Absent
            else
                val cryptoModule = js.Dynamic.global.require("crypto")
                val digestBuffer = cryptoModule.createHash("sha256").update(cert.raw).digest()
                val typed        = digestBuffer.asInstanceOf[js.typedarray.Uint8Array]
                val len          = typed.length
                val out          = new Array[Byte](len)
                var i            = 0
                while i < len do
                    out(i) = typed(i).toByte
                    i += 1
                Present(Span.from(out))
            end if
    end installCertHashFn

    private def listenServer(
        server: js.Dynamic,
        host: String,
        port: Int,
        backlog: Int,
        tcpNoDelay: Boolean,
        connectionEvent: String,
        handler: NetConnection.Unsafe => Unit < Async
    )(using AllowUnsafe, Frame): Fiber.Unsafe[NetListener.Unsafe, Abort[Closed]] =
        val promise  = new IOPromise[Closed, NetListener.Unsafe]
        val listener = new JsListener(server, NetAddress.Tcp(host, port))

        discard(server.on(
            connectionEvent,
            { (socket: js.Dynamic) =>
                discard(socket.pause())
                if tcpNoDelay then discard(socket.setNoDelay(true))

                val connDriver = pool.next()
                val handle     = JsHandle.init(socket, connDriver)
                val connection = Connection.init(handle, connDriver, channelCapacity)
                // Wire upgrade function so the server-side connection can be upgraded to TLS.
                connection.upgradeFn = (tls, frame) =>
                    given Frame = frame
                    Sync.Unsafe.defer {
                        given AllowUnsafe = AllowUnsafe.embrace.danger
                        upgradeToTls(connection, tls, channelCapacity).safe
                    }.map { fiber =>
                        fiber.use { newUnsafeConn =>
                            newUnsafeConn.safe
                        }
                    }
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

        promise.asInstanceOf[Fiber.Unsafe[NetListener.Unsafe, Abort[Closed]]]
    end listenServer

    def connectUnix(path: String)(using AllowUnsafe, Frame): Fiber.Unsafe[NetConnection.Unsafe, Abort[Closed]] =
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
                // Wire upgrade function so upgradeToTls dispatches to this transport.
                connection.upgradeFn = (tls, frame) =>
                    given Frame = frame
                    Sync.Unsafe.defer {
                        given AllowUnsafe = AllowUnsafe.embrace.danger
                        upgradeToTls(connection, tls, channelCapacity).safe
                    }.map { fiber =>
                        fiber.use { newUnsafeConn =>
                            newUnsafeConn.safe
                        }
                    }
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

        promise.asInstanceOf[Fiber.Unsafe[NetConnection.Unsafe, Abort[Closed]]]
    end connectUnix

    def listenUnix(path: String, backlog: Int)(
        handler: NetConnection.Unsafe => Unit < Async
    )(using AllowUnsafe, Frame): Fiber.Unsafe[NetListener.Unsafe, Abort[Closed]] =
        val promise = new IOPromise[Closed, NetListener.Unsafe]

        val net    = js.Dynamic.global.require("net")
        val server = net.createServer()

        val listener = new JsListener(server, NetAddress.Unix(path))

        discard(server.on(
            "connection",
            { (socket: js.Dynamic) =>
                discard(socket.pause())
                // Unix sockets do not support TCP_NODELAY — skip setNoDelay

                val connDriver = pool.next()
                val handle     = JsHandle.init(socket, connDriver)
                val connection = Connection.init(handle, connDriver, channelCapacity)
                // Wire upgrade function so the server-side connection can be upgraded to TLS.
                connection.upgradeFn = (tls, frame) =>
                    given Frame = frame
                    Sync.Unsafe.defer {
                        given AllowUnsafe = AllowUnsafe.embrace.danger
                        upgradeToTls(connection, tls, channelCapacity).safe
                    }.map { fiber =>
                        fiber.use { newUnsafeConn =>
                            newUnsafeConn.safe
                        }
                    }
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

        promise.asInstanceOf[Fiber.Unsafe[NetListener.Unsafe, Abort[Closed]]]
    end listenUnix

    def close()(using AllowUnsafe, Frame): Unit =
        pool.close()

    def upgradeToTls(
        conn: NetConnection.Unsafe,
        tls: kyo.net.NetTlsConfig,
        channelCapacity: Int
    )(using AllowUnsafe, Frame): Fiber.Unsafe[NetConnection.Unsafe, Abort[Closed]] =
        val promise = new IOPromise[Closed, Connection[JsHandle]]

        // Safety: the caller must not have I/O in flight during upgrade. For Postgres sslmode=prefer,
        // the protocol guarantees this: SSLRequest is sent, one-byte response is read, then upgrade is
        // called. The plaintext socket is paused at this point (no "data" events pending). JS is
        // single-threaded so no concurrent read can arrive between the response delivery and this call.
        val jsConn = conn.asInstanceOf[Connection[JsHandle]]
        val handle = jsConn.handle
        val socket = handle.socket // existing net.Socket

        // Detach closes channels and pauses+cancels the socket without destroying it.
        // Any bytes the ReadPump had already staged but caller had not consumed are returned.
        val preRead = jsConn.detachForUpgrade()

        // Pre-read bytes: if the peer sent data before detachForUpgrade drained the inbound channel,
        // push them back into the socket so the TLS engine sees them as the first bytes.
        preRead match
            case Present(chunks) if chunks.nonEmpty =>
                val totalLen = chunks.map(_.size).sum
                val buf      = new Array[Byte](totalLen)
                var off      = 0
                chunks.foreach { span =>
                    val arr = span.toArray
                    java.lang.System.arraycopy(arr, 0, buf, off, arr.length)
                    off += arr.length
                }
                val nodeBuffer = js.Dynamic.global.Buffer.from(
                    js.typedarray.byteArray2Int8Array(buf).buffer
                )
                discard(socket.unshift(nodeBuffer))
            case _ => ()
        end match

        // Remove the JsHandle's permanent listeners from the plaintext socket. They were registered
        // by JsHandle.init and would intercept TLS handshake bytes if left in place after the
        // TLSSocket takes ownership of the underlying socket's data stream.
        discard(socket.removeAllListeners())

        // The underlying socket was paused by detachForUpgrade. Resume it so the TLS layer can
        // read the handshake bytes (ClientHello / ServerHello). The TLS layer manages its own
        // internal flow; we will pause the TLSSocket's application-data stream after handshake.
        discard(socket.resume())

        val tlsModule = js.Dynamic.global.require("tls")
        val fsModule  = js.Dynamic.global.require("fs")

        // Determine upgrade direction from the config:
        //   - Server side: certChainPath + privateKeyPath are set (STARTTLS accept)
        //   - Client side: no cert/key (STARTTLS initiate)
        val isServerSide = tls.certChainPath.isDefined && tls.privateKeyPath.isDefined

        val tlsSocket =
            if isServerSide then
                // Server-side STARTTLS: wrap the existing socket as a TLS server socket.
                // Node.js requires tls.TLSSocket constructor with isServer=true.
                val opts = js.Dynamic.literal()
                opts.isServer = true
                tls.certChainPath match
                    case Present(p) => opts.cert = fsModule.readFileSync(p, "utf8")
                    case Absent     => ()
                tls.privateKeyPath match
                    case Present(p) => opts.key = fsModule.readFileSync(p, "utf8")
                    case Absent     => ()
                // Construct TLSSocket directly with the existing socket and server opts
                js.Dynamic.newInstance(tlsModule.selectDynamic("TLSSocket"))(socket, opts)
            else
                // Client-side STARTTLS: use tls.connect({ socket }) which drives the TLS handshake.
                val opts = js.Dynamic.literal()
                opts.socket = socket
                val sni = tls.sniHostname.getOrElse("localhost")
                opts.servername = sni
                opts.host = sni
                // rejectUnauthorized drives certificate-chain validation only; hostname check is
                // a separate concern routed through checkServerIdentity.
                opts.rejectUnauthorized = !tls.trustAll
                if !tls.hostnameVerification then
                    // No-op identity check: cert chain still validated when rejectUnauthorized=true,
                    // but SAN/CN vs servername mismatch is ignored (verify-ca semantics).
                    opts.checkServerIdentity = ({ (_: js.Any, _: js.Any) => js.undefined }: js.Function2[js.Any, js.Any, js.Any])
                end if
                tls.caCertPath match
                    case Present(path) =>
                        opts.ca = fsModule.readFileSync(path, "utf8")
                    case Absent => ()
                end match
                tlsModule.connect(opts)
            end if
        end tlsSocket

        // Server-side tls.TLSSocket (created via new tls.TLSSocket(..., {isServer:true}))
        // emits 'secure' once the handshake completes.
        // Client-side tls.TLSSocket (created via tls.connect({socket})) emits 'secureConnect'.
        val handshakeEvent = if isServerSide then "secure" else "secureConnect"

        val driver = pool.next()

        discard(tlsSocket.once(
            handshakeEvent,
            { () =>
                // Pause the TLS socket now that the handshake is done — kyo controls data flow.
                // (We cannot pause before the handshake as that blocks TLS record delivery.)
                discard(tlsSocket.pause())
                val newHandle = JsHandle.init(tlsSocket, driver)
                val newConn   = Connection.init(newHandle, driver, channelCapacity)
                // Wire upgrade function on the new TLS connection so further upgrade attempts
                // are routed back through this transport (which will then fail with a TLS-on-TLS error).
                newConn.upgradeFn = (tls2, frame2) =>
                    given Frame = frame2
                    Sync.Unsafe.defer {
                        given AllowUnsafe = AllowUnsafe.embrace.danger
                        upgradeToTls(newConn, tls2, channelCapacity).safe
                    }.map { fiber =>
                        fiber.use { newUnsafeConn =>
                            newUnsafeConn.safe
                        }
                    }
                // Install certHashFn so SCRAM-PLUS channel binding (RFC 5929
                // tls-server-end-point) can read the peer-cert SHA-256.
                installCertHashFn(newConn, tlsSocket)
                newConn.start()
                promise.completeDiscard(Result.succeed(newConn))
            }: js.Function0[Unit]
        ))

        discard(tlsSocket.once(
            "error",
            { (err: js.Dynamic) =>
                val msg = if js.typeOf(err.message) == "string" then err.message.toString else "TLS upgrade error"
                promise.completeDiscard(Result.fail(Closed("JsTransport", summon[Frame], s"TLS upgrade failed: $msg")))
            }: js.Function1[js.Dynamic, Unit]
        ))

        promise.asInstanceOf[Fiber.Unsafe[NetConnection.Unsafe, Abort[Closed]]]
    end upgradeToTls

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
    private var _address: NetAddress
) extends ListenerImpl:
    // JS is single-threaded, but closed flag uses atomic for consistency with other listeners
    private val closedFlag = new java.util.concurrent.atomic.AtomicBoolean(false)

    // Set once in listen callback, read later. JS single-threaded so vars are safe.
    private var _port: Int    = 0
    private var _host: String = ""

    def setAddress(port: Int, host: String): Unit =
        _port = port
        _host = host
        _address match
            case _: NetAddress.Tcp => _address = NetAddress.Tcp(host, port)
            case _                 => ()
    end setAddress

    def port: Int           = _port
    def host: String        = _host
    def address: NetAddress = _address

    def isClosed: Boolean = closedFlag.get()

    def close()(using AllowUnsafe, Frame): Unit =
        if closedFlag.compareAndSet(false, true) then
            discard(server.close())
        end if
    end close
end JsListener
