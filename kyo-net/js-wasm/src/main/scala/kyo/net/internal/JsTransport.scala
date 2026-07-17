package kyo.net.internal

import kyo.*
import kyo.net.Connection as NetConnection
import kyo.net.Listener as NetListener
import kyo.net.NetAddress
import kyo.net.NetAlreadyDetachedException
import kyo.net.NetBindException
import kyo.net.NetConnectException
import kyo.net.NetConnectionClosedException
import kyo.net.NetConnectTimeoutException
import kyo.net.NetDnsResolutionException
import kyo.net.NetException
import kyo.net.NetNotUpgradableException
import kyo.net.NetStdioAlreadyOpenException
import kyo.net.NetTlsConfig
import kyo.net.NetTlsHandshakeException
import kyo.net.NetUnixConnectException
import kyo.net.internal.transport.*
import kyo.scheduler.IOPromise
import scala.scalajs.js

/** JS TCP transport delegating to Node.js `net` and `tls` modules.
  *
  * All connections are managed as Node.js socket objects. Plain TCP sockets use `net.connect` / `net.createServer`; TLS sockets use
  * `tls.connect` / `tls.createServer`. The handshake is handled entirely by Node.js; callers receive a connection only after the TLS
  * `secureConnect` / `secureConnection` event fires.
  *
  * Data flow is pull-based: each socket is paused immediately after creation, and `resume()` is called only when a read is pending. This
  * prevents unbounded buffering of incoming data.
  *
  * Note: Unix-domain sockets do not support TCP_NODELAY, so the `setNoDelay` call is skipped for those connections.
  *
  * TLS introspection: `Connection.serverCertificateHash` is wired through `installCertHashFn`, which reads the leaf peer-certificate DER
  * via Node's `tls.TLSSocket.getPeerCertificate(true).raw` and SHA-256-hashes it with `crypto.createHash("sha256")`. Used by SCRAM-PLUS
  * channel binding (RFC 5929 tls-server-end-point).
  */
final private[kyo] class JsTransport private (
    val pool: IoDriverPool[JsHandle],
    private val channelCapacity: Int,
    private val connectTimeout: Duration,
    private val handshakeTimeout: Duration
) extends TransportImpl[JsHandle]:

    // Process-wide guard: exactly one stdio connection at a time (fd 0/1 are process-global).
    // Unsafe: created at object initialization with no ambient AllowUnsafe; the danger bridge builds it here and its accesses run under the
    // caller's AllowUnsafe.
    private val stdioClaimed = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger)

    /** JS terminates TLS with Node's `tls` module, so it serves only the "node" implementation. A connection pinning any other
      * [[NetTlsConfig.tlsProvider]] fails closed (see `connect(tls)`/`listen(tls)`/`upgradeToTls`). The cross-backend test matrix reads this to
      * skip non-node cells against the node backend.
      */
    override private[net] def supportedTlsProviders: Set[String] = Set("node")

    /** The fail-closed explanation for a [[NetTlsConfig.tlsProvider]] pin other than "node", shared by the three TLS entry points so all reject a
      * non-node pin identically. Carried as the cause of a [[NetTlsHandshakeException]].
      */
    private def rejectNonNodeProvider(tls: NetTlsConfig): String =
        s"pinned TLS provider '${tls.tlsProvider.getOrElse("")}' is not supported by the JS transport (only 'node')"

    /** A Node error's `message` string, or empty when absent. The message is Node's own; it is carried as a leaf cause, never authored here. */
    private def errMessage(err: js.Dynamic): String =
        if js.typeOf(err.message) == "string" then err.message.toString else ""

    /** A Node error's `code` string (ENOTFOUND, ECONNREFUSED, ...), or empty when absent. Used only to classify the failure mode. */
    private def errCode(err: js.Dynamic): String =
        if js.typeOf(err.code) == "string" then err.code.toString else ""

    private def isDnsCode(code: String): Boolean =
        code == "ENOTFOUND" || code == "EAI_AGAIN" || code == "EAI_NONAME" || code == "EAI_FAIL" || code == "EAI_NODATA"

    private def isTlsCode(code: String): Boolean =
        code.startsWith("ERR_TLS") || code.startsWith("ERR_SSL") || code.startsWith("CERT_") ||
            code.startsWith("UNABLE_TO_") || code.startsWith("SELF_SIGNED") || code.startsWith("DEPTH_ZERO") ||
            code == "ERR_TLS_CERT_ALTNAME_INVALID"

    /** Map a Node connect-stage error to the matching [[NetException]] leaf: a name-resolution code to [[NetDnsResolutionException]], a TLS code
      * to [[NetTlsHandshakeException]], a Unix target (`port < 0`) to [[NetUnixConnectException]], otherwise [[NetConnectException]]. The leaf
      * carries Node's own message; the code only selects the leaf.
      */
    private def connectError(err: js.Dynamic, host: String, port: Int)(using Frame): NetException =
        val code = errCode(err)
        val msg  = errMessage(err)
        if isDnsCode(code) then NetDnsResolutionException(host, msg)
        else if isTlsCode(code) then NetTlsHandshakeException(host, port, msg)
        else if port < 0 then NetUnixConnectException(host, msg)
        else NetConnectException(host, port, msg)
        end if
    end connectError

    /** Map a Node listen-stage error to its leaf: a name-resolution code to [[NetDnsResolutionException]], otherwise [[NetBindException]]. */
    private def listenError(err: js.Dynamic, host: String, port: Int)(using Frame): NetException =
        val code = errCode(err)
        val msg  = errMessage(err)
        if isDnsCode(code) then NetDnsResolutionException(host, msg) else NetBindException(host, port, msg)
    end listenError

    def connect(host: String, port: Int)(using AllowUnsafe, Frame): Fiber.Unsafe[NetConnection, Abort[NetException]] =
        val socket = js.Dynamic.global.require("net").connect(port, host)
        connectSocket(socket, host, port, tcpNoDelay = true, connectEvent = "connect")
    end connect

    def listen(host: String, port: Int, backlog: Int)(
        handler: NetConnection => Unit
    )(using AllowUnsafe, Frame): Fiber.Unsafe[NetListener, Abort[NetException]] =
        val server = js.Dynamic.global.require("net").createServer()
        listenServer(server, host, port, backlog, tcpNoDelay = true, connectionEvent = "connection", handler)
    end listen

    def connect(host: String, port: Int, tls: NetTlsConfig)(using AllowUnsafe, Frame): Fiber.Unsafe[NetConnection, Abort[NetException]] =
        // Honor a NetTlsConfig.tlsProvider pin: JS terminates TLS with Node's tls module, so it serves only the "node" implementation. A pin to
        // any other provider fails closed rather than silently using Node under a different provider's name (config truthfulness).
        if tls.tlsProvider.exists(_ != "node") then
            Fiber.Unsafe.fromResult(Result.fail(NetTlsHandshakeException(host, port, rejectNonNodeProvider(tls))))
        // Verifying client with no reference identity: fail closed before connecting. An empty host gives the TLS layer nothing to check the
        // server certificate's name against, so a chain-valid certificate with no name bound would otherwise be accepted (RFC 9525 6.1, the
        // Go/rustls "no servername -> reject" rule). This matches SslEngineProvider/BoringSslProvider/SystemOpenSslProvider so the same
        // NetTlsConfig + host reaches the identical accept/reject decision on all four providers.
        else if !tls.trustAll && tls.hostnameVerification && host.isEmpty then
            Fiber.Unsafe.fromResult(Result.fail(NetTlsHandshakeException(
                host,
                port,
                "verifying client has no reference identity: a hostname is required to verify the server certificate (set trustAll or " +
                    "hostnameVerification = false to opt out of name verification)"
            )))
        else
            val opts = js.Dynamic.literal(host = host, port = port)
            // rejectUnauthorized drives certificate-chain validation only; hostname check is a
            // separate concern routed through checkServerIdentity.
            opts.rejectUnauthorized = !tls.trustAll
            // Constrain the negotiated TLS version: NetTlsConfig.minVersion/maxVersion map onto Node's tls minVersion/maxVersion so a caller
            // asking for TLS1.3-only cannot silently negotiate TLS1.2 (CWE-326).
            applyVersionBounds(opts, tls)
            if !tls.hostnameVerification then
                // No-op identity check: cert chain still validated when rejectUnauthorized=true,
                // but SAN/CN vs servername mismatch is ignored (verify-ca semantics).
                opts.checkServerIdentity = ({ (_: js.Any, _: js.Any) => js.undefined }: js.Function2[js.Any, js.Any, js.Any])
            end if
            tls.sniHostname match
                case Present(sni) => opts.servername = sni
                case Absent       => opts.servername = host
            // Custom CA: Node's tls.connect loads this as the only trust anchor when present.
            tls.caCertPath match
                case Present(path) =>
                    opts.ca = js.Dynamic.global.require("fs").readFileSync(path, "utf8")
                case Absent => ()
            end match
            // Client certificate for mutual TLS: present it when the server requests one. A no-op for the common (no client cert) client.
            (tls.certChainPath, tls.privateKeyPath) match
                case (Present(certPath), Present(keyPath)) =>
                    val fs = js.Dynamic.global.require("fs")
                    opts.cert = fs.readFileSync(certPath, "utf8")
                    opts.key = fs.readFileSync(keyPath, "utf8")
                case _ => ()
            end match
            val socket = js.Dynamic.global.require("tls").connect(opts)
            // TLS sockets emit "secureConnect" after handshake (not "connect" which fires on raw TCP)
            connectSocket(socket, host, port, tcpNoDelay = false, connectEvent = "secureConnect")
        end if
    end connect

    def listen(host: String, port: Int, backlog: Int, tls: NetTlsConfig)(
        handler: NetConnection => Unit
    )(using AllowUnsafe, Frame): Fiber.Unsafe[NetListener, Abort[NetException]] =
        // Honor a NetTlsConfig.tlsProvider pin: JS terminates TLS with Node's tls module, so a server pinned to any non-"node" provider fails
        // closed rather than silently serving with Node under another provider's name (config truthfulness).
        if tls.tlsProvider.exists(_ != "node") then
            return Fiber.Unsafe.fromResult(Result.fail(NetTlsHandshakeException(host, port, rejectNonNodeProvider(tls))))
        val serverOpts = js.Dynamic.literal()
        // Constrain the negotiated TLS version on the server side too: a server pinned to TLS1.3 must reject a TLS1.2 client (CWE-326).
        applyVersionBounds(serverOpts, tls)
        tls.certChainPath match
            case Present(p) => serverOpts.cert = js.Dynamic.global.require("fs").readFileSync(p, "utf8")
            case Absent     => ()
        tls.privateKeyPath match
            case Present(p) => serverOpts.key = js.Dynamic.global.require("fs").readFileSync(p, "utf8")
            case Absent     => ()
        applyServerClientAuth(serverOpts, tls)
        val server = js.Dynamic.global.require("tls").createServer(serverOpts)
        // One deadline per accepted connection: a client that completed the TCP accept but stalls the TLS handshake (sends nothing / a partial
        // ClientHello and never finishes) never fires "secureConnection", so the accepted Node socket would linger indefinitely, pinning the fd
        // and its buffers (a slowloris handshake-stall DoS, CWE-400). When handshakeTimeout is finite, arm a Clock-driven timer as each raw
        // socket arrives ("connection", which a TLS server emits on TCP accept before the handshake) and destroy the socket if the handshake has
        // not completed by the deadline; "secureConnection" (success) and "tlsClientError" (failed handshake) disarm it. handshakeTimeout =
        // Infinity arms no timer (preserving the original behavior).
        armServerHandshakeDeadlines(server)
        // TLS servers emit "secureConnection" after handshake (not "connection" which fires on raw TCP)
        listenServer(server, host, port, backlog, tcpNoDelay = false, connectionEvent = "secureConnection", handler)
    end listen

    // -- shared helpers --

    // Map NetTlsConfig.minVersion/maxVersion onto Node's tls minVersion/maxVersion option keys. Node accepts the protocol strings
    // "TLSv1.2"/"TLSv1.3"; setting both bounds means the negotiated version is constrained to the configured range, so a TLS1.3-only config
    // cannot fall back to TLS1.2 (CWE-326). Applied to every Node tls option object the transport builds (connect, listen, both upgrade arms).
    private def applyVersionBounds(opts: js.Dynamic, tls: NetTlsConfig) =
        opts.minVersion = nodeTlsVersion(tls.minVersion)
        opts.maxVersion = nodeTlsVersion(tls.maxVersion)

    private def nodeTlsVersion(v: NetTlsConfig.Version) = v match
        case NetTlsConfig.Version.TLS12 => "TLSv1.2"
        case NetTlsConfig.Version.TLS13 => "TLSv1.3"

    // Honor NetTlsConfig.clientAuth on a Node TLS server options object (used by both listen(tls) and the STARTTLS server upgrade). requestCert
    // asks the client for a certificate; rejectUnauthorized rejects a client that presents none or one the trust store + ca does not validate.
    // Required rejects; Optional requests but does not reject; None leaves the defaults (no client cert requested). The presented client
    // certificate is validated against trustStorePath, falling back to caCertPath.
    private def applyServerClientAuth(opts: js.Dynamic, tls: NetTlsConfig): Unit =
        tls.clientAuth match
            case NetTlsConfig.ClientAuth.Required =>
                opts.requestCert = true
                opts.rejectUnauthorized = true
            case NetTlsConfig.ClientAuth.Optional =>
                opts.requestCert = true
                opts.rejectUnauthorized = false
            case NetTlsConfig.ClientAuth.None => ()
        end match
        tls.trustStorePath.orElse(tls.caCertPath).foreach { p =>
            opts.ca = js.Dynamic.global.require("fs").readFileSync(p, "utf8")
        }
    end applyServerClientAuth

    // Property name under which each accepted raw socket carries a one-shot "handshake settled" guard, so the deadline timer and the
    // handshake-resolved events (secureConnection / tlsClientError) are mutually exclusive: whichever sets it first wins, the other is a no-op.
    // Prefixed to avoid clashing with any Node socket field.
    private val handshakeSettledProp = "__kyoHandshakeSettled"

    /** Arm a `Clock`-driven handshake deadline on every connection a TLS [[server]] accepts. A `tls.Server` emits "connection" with the raw
      * `net.Socket` on TCP accept (before the handshake), "secureConnection" with the wrapping `tls.TLSSocket` on a successful handshake, and
      * "tlsClientError" with the `tls.TLSSocket` on a failed handshake; the `TLSSocket._parent` is the raw socket the "connection" event delivered.
      *
      *   - "connection": start `Clock.live.unsafe.sleep(handshakeTimeout)` (a timer fiber on the clock executor, never a blocked carrier). When
      *     the deadline elapses, the timer's continuation claims the per-socket guard and, if it wins (the handshake has not resolved),
      *     `socket.destroy()` reaps the stalled connection (closing the fd and releasing its buffers), the same teardown Node runs on any
      *     destroyed socket.
      *   - "secureConnection" / "tlsClientError": the handshake resolved, so claim the guard so the timer's later continuation is a no-op (it sees
      *     the guard already set and does not destroy a healthy or already-failed connection).
      *
      * The guard is the single source of truth (rather than relying on interrupting the timer fiber): on JS the timer's continuation always runs
      * on the macrotask scheduler, so a settled guard is what makes it skip the destroy. Called only on the TLS `listen` path and only when
      * `handshakeTimeout` is finite; `Duration.Infinity` arms nothing.
      */
    private def armServerHandshakeDeadlines(server: js.Dynamic)(using AllowUnsafe, Frame): Unit =
        if handshakeTimeout.isFinite then
            // Claim the per-socket guard. Returns true the first time for a given socket, false thereafter, so exactly one of (deadline, handshake
            // outcome) proceeds.
            def claim(rawSocket: js.Dynamic): Boolean =
                if js.isUndefined(rawSocket) || rawSocket == null then false
                else if js.isUndefined(rawSocket.selectDynamic(handshakeSettledProp)) then
                    rawSocket.updateDynamic(handshakeSettledProp)(true)
                    true
                else false
            discard(server.on(
                "connection",
                { (rawSocket: js.Dynamic) =>
                    val timer = Clock.live.unsafe.sleep(handshakeTimeout)
                    timer.onComplete { _ =>
                        if claim(rawSocket) then
                            Log.live.unsafe.warn(s"JsTransport server TLS handshake timed out after ${handshakeTimeout.show}")
                            discard(rawSocket.destroy())
                    }
                }: js.Function1[js.Dynamic, Unit]
            ))
            discard(server.on(
                "secureConnection",
                { (tlsSocket: js.Dynamic) => discard(claim(tlsSocket.selectDynamic("_parent"))) }: js.Function1[js.Dynamic, Unit]
            ))
            discard(server.on(
                "tlsClientError",
                { (_: js.Dynamic, tlsSocket: js.Dynamic) =>
                    if !js.isUndefined(tlsSocket) && tlsSocket != null then discard(claim(tlsSocket.selectDynamic("_parent")))
                }: js.Function2[js.Dynamic, js.Dynamic, Unit]
            ))
    end armServerHandshakeDeadlines

    /** Arm a `Clock`-driven deadline for one in-flight client TCP connect. When `connectTimeout` is finite (and the target is a TCP host:port,
      * not a Unix socket whose `port < 0` has no typed timeout leaf), schedule `Clock.live.unsafe.sleep(d).onComplete(...)` (a timer fiber on the
      * clock executor, never a blocked carrier) and fail `promise` with `NetConnectTimeoutException(host, port, connectTimeout)` when the
      * deadline fires. `promise.completeDiscard` completes the promise at most once, so the deadline and the Node connect/error outcome are
      * mutually exclusive. This is the close-cause discrimination: the deadline arm is the only producer of the typed timeout leaf, so a
      * deadline-fired close surfaces `NetConnectTimeoutException` while an OS-failure close surfaces `NetConnectException` through `connectError`.
      * When the connect outcome completes `promise` first it disarms the timer by interrupting the timer fiber, so the timer never fires.
      */
    private def armConnectDeadline(
        promise: IOPromise[NetException, Connection[JsHandle]],
        host: String,
        port: Int
    )(using AllowUnsafe, Frame): Unit =
        if port >= 0 && connectTimeout.isFinite then
            val timer = Clock.live.unsafe.sleep(connectTimeout)
            timer.onComplete { _ =>
                promise.completeDiscard(Result.fail(NetConnectTimeoutException(host, port, connectTimeout)))
            }
            // Disarm: when the connect outcome completes `promise` first, interrupt the timer fiber so it never fires.
            promise.onComplete { _ =>
                timer.interruptDiscard(Result.Panic(Closed("JsTransport", summon[Frame], "connect completed before deadline")))
            }
    end armConnectDeadline

    private def connectSocket(socket: js.Dynamic, host: String, port: Int, tcpNoDelay: Boolean, connectEvent: String)(
        using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[NetConnection, Abort[NetException]] =
        val promise = new IOPromise[NetException, Connection[JsHandle]]
        val driver  = pool.next()

        // Arm the connect-deadline: when connectTimeout is finite, a Clock-driven timer fails `promise` with the typed
        // NetConnectTimeoutException if the Node socket neither connects nor errors first. The deadline arm and the OS outcome race on the same
        // `promise` (completeDiscard, at most once), so a deadline-fired close surfaces the timeout leaf and an OS-failure close surfaces
        // NetConnectException through `connectError`: the close cause is discriminated by which arm completes `promise` first.
        armConnectDeadline(promise, host, port)

        // Pause immediately - kyo controls data flow
        discard(socket.pause())

        discard(socket.once(
            connectEvent,
            { () =>
                if tcpNoDelay then discard(socket.setNoDelay(true))
                val handle     = JsHandle.init(socket, driver)
                val connection = Connection.init(handle, driver, channelCapacity)
                // Wire upgrade function so upgradeToTls dispatches to this transport.
                connection.upgradeFn = Present { (tls, frame) =>
                    given Frame = frame
                    upgradeToTls(connection, tls, channelCapacity)
                }
                // For TLS handshakes (secureConnect), install certHashFn so SCRAM-PLUS channel
                // binding can compute tls-server-end-point (RFC 5929) from the peer cert.
                if connectEvent == "secureConnect" then installCertHashFn(connection, socket)
                // closeReasonFn is deliberately NOT wired here: the connection keeps the default Connection.CloseReason.Active. The posix and NIO
                // transports report CleanClose vs Truncated (RFC 8446 6.1) by observing the peer's close_notify alert at the TLS record layer,
                // which they drive directly (BoringSSL/OpenSSL/SSLEngine). JS delegates TLS termination to Node, whose tls.TLSSocket abstracts the
                // record layer away: empirically (Node v23) a clean close (close_notify then FIN) and a truncation (bare FIN, no close_notify)
                // surface as the identical `end` + `close(hadError=false)` events, and no public or stable-private signal distinguishes them. So
                // the truncation distinction cannot be reported on the Node backend; this is a validated platform exception, not a missing wiring.
                if connection.start() then
                    promise.completeDiscard(Result.succeed(connection))
                else
                    // The connection raced to a terminal/Upgrading state before start (a close won); it must not be handed out as open.
                    promise.completeDiscard(Result.fail(NetConnectionClosedException("start")))
                end if
            }: js.Function0[Unit]
        ))

        discard(socket.once(
            "error",
            { (err: js.Dynamic) =>
                promise.completeDiscard(Result.fail(connectError(err, host, port)))
            }: js.Function1[js.Dynamic, Unit]
        ))

        // Fiber.Unsafe[A, S] is an opaque alias over IOPromiseBase[Any, A < (Async & S)] (kyo.Fiber.scala), structurally different from this
        // plainly-constructed, invariant IOPromise[NetException, Connection[JsHandle]], even though both erase to the same runtime object;
        // the alias is transparent only inside kyo.Fiber's own defining scope, so exposing this promise as the locked Transport.connect
        // return needs this erased-boundary cast. Safe: the promise completes only with the NetException/Connection values above.
        promise.asInstanceOf[Fiber.Unsafe[NetConnection, Abort[NetException]]]
    end connectSocket

    /** Install the certHashFn on `connection` by reading the leaf peer certificate from the post-handshake TLS socket and SHA-256-hashing
      * its DER bytes (RFC 5929 tls-server-end-point). Used by SCRAM-PLUS channel binding.
      *
      * Node's `tls.TLSSocket.getPeerCertificate(true)` returns an object with a `.raw` Buffer holding the DER bytes; Node's `crypto`
      * `createHash("sha256").update(buf).digest()` returns a 32-byte Buffer.
      */
    private def installCertHashFn(connection: Connection[JsHandle], tlsSocket: js.Dynamic): Unit =
        connection.certHashFn = Present { () =>
            val cert = tlsSocket.getPeerCertificate(true)
            if js.isUndefined(cert) || cert == null || js.isUndefined(cert.raw) then Absent
            else
                val cryptoModule = js.Dynamic.global.require("crypto")
                val digestBuffer = cryptoModule.createHash("sha256").update(cert.raw).digest()
                // Node's Hash.digest() returns a Buffer, and Buffer extends Uint8Array in the Node runtime; js.Dynamic erases that to an
                // untyped JS value with no static Scala.js type, so recovering the typed Uint8Array view needs this narrowing cast. Safe per
                // Node's documented Buffer/Uint8Array relationship; it cannot dissolve without a typed facade for Node's crypto Hash object.
                val typed = digestBuffer.asInstanceOf[js.typedarray.Uint8Array]
                val len   = typed.length
                val out   = new Array[Byte](len)
                var i     = 0
                while i < len do
                    out(i) = typed(i).toByte
                    i += 1
                Present(Span.from(out))
            end if
        }
    end installCertHashFn

    private def listenServer(
        server: js.Dynamic,
        host: String,
        port: Int,
        backlog: Int,
        tcpNoDelay: Boolean,
        connectionEvent: String,
        handler: NetConnection => Unit
    )(using AllowUnsafe, Frame): Fiber.Unsafe[NetListener, Abort[NetException]] =
        val promise  = new IOPromise[NetException, NetListener]
        val listener = new JsListener(server, NetAddress.Tcp(host, port))

        discard(server.on(
            connectionEvent,
            { (socket: js.Dynamic) =>
                discard(socket.pause())
                if tcpNoDelay then discard(socket.setNoDelay(true))

                val connDriver = pool.next()
                val handle     = JsHandle.init(socket, connDriver)
                val connection = Connection.init(handle, connDriver, channelCapacity)
                // Accepted connection: a STARTTLS upgrade through the public upgradeToTls runs in the TLS server role (upgradeToTls reads
                // isServerOrigin).
                connection.isServerOrigin = true
                // Wire upgrade function so the server-side connection can be upgraded to TLS.
                connection.upgradeFn = Present { (tls, frame) =>
                    given Frame = frame
                    upgradeToTls(connection, tls, channelCapacity)
                }
                if connection.start() then
                    // Spawn the handler in its own carrier fiber. The connection lifecycle is managed by its pumps.
                    // The handler is fire-and-forget; a throw from the handler is logged and does not propagate here.
                    discard(Fiber.Unsafe.init {
                        // Contain ANY throw from the user handler (not just NonFatal): a throw must never escape to the carrier, abort the process, or
                        // stall the accept loop. The containment is uniform across all backends (posix, NIO, node) so a throwing handler is a single
                        // contained-connection event everywhere, never a process-level fault.
                        try handler(connection)
                        catch case e: Throwable => Log.live.unsafe.error(s"Connection handler panic", e)
                    })
                else
                    // The connection raced to a terminal/Upgrading state before start (a close won); it is not usable, so the handler is never spawned.
                    Log.live.unsafe.info(s"JsTransport accepted connection closed before start; handler not spawned")
                end if
            }: js.Function1[js.Dynamic, Unit]
        ))

        discard(server.on(
            "error",
            { (err: js.Dynamic) =>
                promise.completeDiscard(Result.fail(listenError(err, host, port)))
            }: js.Function1[js.Dynamic, Unit]
        ))

        discard(server.listen(
            port,
            host,
            backlog,
            { () =>

                val addr = server.address()
                // Node's net.Server#address() returns {port, family, address} for a bound TCP server per the documented Node API; js.Dynamic
                // erases that shape to untyped JS values, so recovering the typed Int/String fields needs these narrowing casts. Safe per
                // Node's documented return shape; they cannot dissolve without a typed facade for Node's net address object.
                val actualPort = addr.port.asInstanceOf[Int]
                val actualHost = addr.address.asInstanceOf[String]
                listener.setAddress(actualPort, actualHost)
                promise.completeDiscard(Result.succeed(listener))
            }: js.Function0[Unit]
        ))

        // Fiber.Unsafe[A, S] is an opaque alias over IOPromiseBase[Any, A < (Async & S)] (kyo.Fiber.scala), structurally different from this
        // plainly-constructed, invariant IOPromise[NetException, NetListener], even though both erase to the same runtime object; the alias
        // is transparent only inside kyo.Fiber's own defining scope, so exposing this promise as the locked Transport.listen return needs
        // this erased-boundary cast. Safe: the promise completes only with the NetException/NetListener values above.
        promise.asInstanceOf[Fiber.Unsafe[NetListener, Abort[NetException]]]
    end listenServer

    def connectUnix(path: String)(using AllowUnsafe, Frame): Fiber.Unsafe[NetConnection, Abort[NetException]] =
        val promise = new IOPromise[NetException, Connection[JsHandle]]
        val driver  = pool.next()

        val net    = js.Dynamic.global.require("net")
        val socket = net.createConnection(js.Dynamic.literal(path = path))

        // Pause immediately - kyo controls data flow
        discard(socket.pause())

        discard(socket.once(
            "connect",
            { () =>
                // Unix sockets do not support TCP_NODELAY: skip setNoDelay
                val handle     = JsHandle.init(socket, driver)
                val connection = Connection.init(handle, driver, channelCapacity)
                // Wire upgrade function so upgradeToTls dispatches to this transport.
                connection.upgradeFn = Present { (tls, frame) =>
                    given Frame = frame
                    upgradeToTls(connection, tls, channelCapacity)
                }
                if connection.start() then
                    promise.completeDiscard(Result.succeed(connection))
                else
                    // The connection raced to a terminal/Upgrading state before start (a close won); it must not be handed out as open.
                    promise.completeDiscard(Result.fail(NetConnectionClosedException("start")))
                end if
            }: js.Function0[Unit]
        ))

        discard(socket.once(
            "error",
            { (err: js.Dynamic) =>
                // port = -1: a Unix socket has no port, so connectError routes failures to NetUnixConnectException.
                promise.completeDiscard(Result.fail(connectError(err, path, -1)))
            }: js.Function1[js.Dynamic, Unit]
        ))

        // Fiber.Unsafe[A, S] is an opaque alias over IOPromiseBase[Any, A < (Async & S)] (kyo.Fiber.scala), structurally different from this
        // plainly-constructed, invariant IOPromise[NetException, Connection[JsHandle]], even though both erase to the same runtime object;
        // the alias is transparent only inside kyo.Fiber's own defining scope, so exposing this promise as the locked
        // Transport.connectUnix return needs this erased-boundary cast. Safe: the promise completes only with the NetException/Connection
        // values above.
        promise.asInstanceOf[Fiber.Unsafe[NetConnection, Abort[NetException]]]
    end connectUnix

    override def stdio()(using AllowUnsafe, Frame): Fiber.Unsafe[NetConnection, Abort[NetException]] =
        if !stdioClaimed.compareAndSet(false, true) then
            // Exactly one stdio per process: fds 0/1 are process-global, so double-ownership is rejected.
            Fiber.Unsafe.fromResult(Result.fail(NetStdioAlreadyOpenException()))
        else
            val driver = pool.next()
            // A duplex shim over the two Node streams: reads route to process.stdin, writes to process.stdout.
            // JsHandle/JsIoDriver expect a single socket-like object, so the shim presents one whose read events
            // come from stdin and whose write goes to stdout. destroy() is a no-op: the process owns fds 0/1.
            val shim       = stdioShim()
            val handle     = JsHandle.init(shim, driver)
            val connection = Connection.init(handle, driver, channelCapacity)
            if connection.start() then
                Fiber.Unsafe.fromResult(Result.succeed(connection: NetConnection))
            else
                // Unreachable: Connection.init registers nothing a concurrent close could reach before start() runs immediately above.
                // Surfaced as a typed failure (not a Panic) since the return type already supports it and the shape here is eager/synchronous,
                // unlike the deferred PosixTransport.stdio() (see PosixTransport.scala:197 above).
                Fiber.Unsafe.fromResult(Result.fail(NetConnectionClosedException("start")))
            end if
    end stdio

    /** Build the stdin/stdout duplex shim. The readable side (`on`, `pause`, `resume`) delegates to `process.stdin`; the writable side
      * (`write`, `once`/`removeListener` for drain/close/error) delegates to `process.stdout`. `destroyed` is always false and `destroy` is a
      * no-op so neither process fd is ever closed.
      */
    private def stdioShim()(using AllowUnsafe): js.Dynamic =
        val process = js.Dynamic.global.process
        val stdin   = process.stdin
        val stdout  = process.stdout
        // The set of read-side events JsHandle/JsIoDriver subscribe to on the readable stream. Node passes the
        // event name as a JS string; `toString` returns it unchanged, so it is compared as a Scala String.
        def isReadEvent(event: js.Any): Boolean =
            val name = event.toString
            name == "data" || name == "end"
        def isReadLifecycle(event: js.Any): Boolean =
            val name = event.toString
            isReadEvent(event) || name == "close" || name == "error"
        val shim = js.Dynamic.literal()
        shim.destroyed = false
        shim.on = ({ (event: js.Any, fn: js.Any) =>
            if isReadLifecycle(event) then discard(stdin.on(event, fn))
            else discard(stdout.on(event, fn))
            shim
        }: js.Function2[js.Any, js.Any, js.Any])
        shim.once = ({ (event: js.Any, fn: js.Any) =>
            if isReadEvent(event) then discard(stdin.once(event, fn))
            else discard(stdout.once(event, fn))
            shim
        }: js.Function2[js.Any, js.Any, js.Any])
        shim.removeListener = ({ (event: js.Any, fn: js.Any) =>
            if isReadEvent(event) then discard(stdin.removeListener(event, fn))
            else discard(stdout.removeListener(event, fn))
            shim
        }: js.Function2[js.Any, js.Any, js.Any])
        shim.pause = ({ () =>
            discard(stdin.pause()); shim
        }: js.Function0[js.Any])
        shim.resume = ({ () =>
            discard(stdin.resume()); shim
        }: js.Function0[js.Any])
        shim.write = ({ (chunk: js.Any) =>
            stdout.write(chunk)
        }: js.Function1[js.Any, js.Any])
        shim.destroy = ({ () =>
            // The process owns fds 0/1; never close them. Tearing down stdio closes only the channels/registration.
            shim
        }: js.Function0[js.Any])
        shim
    end stdioShim

    def listenUnix(path: String, backlog: Int)(
        handler: NetConnection => Unit
    )(using AllowUnsafe, Frame): Fiber.Unsafe[NetListener, Abort[NetException]] =
        val promise = new IOPromise[NetException, NetListener]

        val net    = js.Dynamic.global.require("net")
        val server = net.createServer()

        val listener = new JsListener(server, NetAddress.Unix(path))

        discard(server.on(
            "connection",
            { (socket: js.Dynamic) =>
                discard(socket.pause())
                // Unix sockets do not support TCP_NODELAY: skip setNoDelay

                val connDriver = pool.next()
                val handle     = JsHandle.init(socket, connDriver)
                val connection = Connection.init(handle, connDriver, channelCapacity)
                // Accepted connection: a STARTTLS upgrade through the public upgradeToTls runs in the TLS server role (upgradeToTls reads
                // isServerOrigin).
                connection.isServerOrigin = true
                // Wire upgrade function so the server-side connection can be upgraded to TLS.
                connection.upgradeFn = Present { (tls, frame) =>
                    given Frame = frame
                    upgradeToTls(connection, tls, channelCapacity)
                }
                if connection.start() then
                    // Spawn the handler in its own carrier fiber. Fire-and-forget; a throw is logged and does not propagate.
                    discard(Fiber.Unsafe.init {
                        // Contain ANY throw from the user handler (not just NonFatal): a throw must never escape to the carrier, abort the process, or
                        // stall the accept loop. The containment is uniform across all backends (posix, NIO, node) so a throwing handler is a single
                        // contained-connection event everywhere, never a process-level fault.
                        try handler(connection)
                        catch case e: Throwable => Log.live.unsafe.error(s"Connection handler panic", e)
                    })
                else
                    // The connection raced to a terminal/Upgrading state before start (a close won); it is not usable, so the handler is never spawned.
                    Log.live.unsafe.info(s"JsTransport accepted connection closed before start; handler not spawned")
                end if
            }: js.Function1[js.Dynamic, Unit]
        ))

        discard(server.on(
            "error",
            { (err: js.Dynamic) =>
                // port = -1: a Unix listener has no port, so listenError yields NetBindException for the bind-stage failure.
                promise.completeDiscard(Result.fail(listenError(err, path, -1)))
            }: js.Function1[js.Dynamic, Unit]
        ))

        discard(server.listen(
            js.Dynamic.literal(path = path, backlog = backlog),
            { () =>

                listener.setAddress(-1, path)
                promise.completeDiscard(Result.succeed(listener))
            }: js.Function0[Unit]
        ))

        // Fiber.Unsafe[A, S] is an opaque alias over IOPromiseBase[Any, A < (Async & S)] (kyo.Fiber.scala), structurally different from this
        // plainly-constructed, invariant IOPromise[NetException, NetListener], even though both erase to the same runtime object; the alias
        // is transparent only inside kyo.Fiber's own defining scope, so exposing this promise as the locked Transport.listen (Unix) return
        // needs this erased-boundary cast. Safe: the promise completes only with the NetException/NetListener values above.
        promise.asInstanceOf[Fiber.Unsafe[NetListener, Abort[NetException]]]
    end listenUnix

    def close()(using AllowUnsafe, Frame): Unit =
        pool.close()

    def upgradeToTls(
        conn: NetConnection,
        tls: kyo.net.NetTlsConfig,
        channelCapacity: Int
    )(using AllowUnsafe, Frame): Fiber.Unsafe[NetConnection, Abort[NetException]] =
        // The SNI host the upgrade engine verifies against; also the host reported by any handshake failure (an upgrade has no fresh port, so -1).
        val upgradeHost = tls.sniHostname.getOrElse("")
        // Honor a NetTlsConfig.tlsProvider pin: JS upgrades via Node's tls module, so a pin to any non-"node" provider fails closed.
        if tls.tlsProvider.exists(_ != "node") then
            return Fiber.Unsafe.fromResult(Result.fail(NetTlsHandshakeException(upgradeHost, -1, rejectNonNodeProvider(tls))))
        // A non-upgradable connection (e.g. the in-memory connection, a plain kyo.net.Connection with no JsHandle) must abort, matching the posix
        // transport, rather than throwing a ClassCastException on the downcast below.
        // Guarded narrowing: the left `!conn.isInstanceOf[Connection[?]]` short-circuits the `||`, so `asInstanceOf[Connection[?]]` on the
        // right only evaluates once `conn` is confirmed a Connection; safe by construction. NetConnection has no public accessor for the
        // wrapped `handle`, so this internal narrowing is how the module reads it before the JsHandle check.
        if !conn.isInstanceOf[Connection[?]] || !conn.asInstanceOf[Connection[?]].handle.isInstanceOf[JsHandle] then
            // Chaining the cast detaches this call from the method's expected type, so Fiber.Unsafe.fromResult[E, A, S] resolves its phantom
            // effect row standalone: E infers from the narrower NetNotUpgradableException the Result carries, giving
            // Fiber.Unsafe[Nothing, Abort[NetNotUpgradableException]]. That view already conforms to the declared
            // Fiber.Unsafe[NetConnection, Abort[NetException]] by variance alone (Fiber.Unsafe is covariant in A and contravariant in S, and
            // Abort is contravariant in E), so nothing here crosses the opaque-alias boundary: the value is already a Fiber.Unsafe and the
            // cast is not required to obtain one. It is kept for uniformity with the module's other Fiber.Unsafe boundary casts, which
            // recover the opaque alias from a plain IOPromise and do need it.
            return Fiber.Unsafe.fromResult(Result.fail(NetNotUpgradableException()))
                .asInstanceOf[Fiber.Unsafe[NetConnection, Abort[NetException]]]
        end if
        // Verifying client with no reference identity: fail closed before upgrading, matching connect(tls) and the posix/NIO STARTTLS upgrade
        // path. A STARTTLS client upgrade (a connected, non-server-origin connection) derives its reference identity from sniHostname; with none,
        // a chain-valid certificate with no bound name is never an acceptable silent outcome (RFC 9525 6.1; CWE-295). Without this the client
        // branch defaulted the servername to "localhost" and silently verified against it. Server-side upgrades present a certificate and do not
        // verify peer identity, so this guard is client-side only.
        // Safe: the guard above already confirmed conn.isInstanceOf[Connection[?]] && ....handle.isInstanceOf[JsHandle] before this point
        // (the guard's return is the only path past it), so this narrowing to Connection[JsHandle] cannot fail.
        val clientUpgradeNoIdentity =
            !conn.asInstanceOf[Connection[JsHandle]].isServerOrigin && !tls.trustAll && tls.hostnameVerification &&
                tls.sniHostname.getOrElse("").isEmpty
        if clientUpgradeNoIdentity then
            // Chaining the cast detaches this call from the method's expected type, so Fiber.Unsafe.fromResult[E, A, S] resolves its phantom
            // effect row standalone: E infers from the narrower NetTlsHandshakeException the Result carries, giving
            // Fiber.Unsafe[Nothing, Abort[NetTlsHandshakeException]]. That view already conforms to the declared
            // Fiber.Unsafe[NetConnection, Abort[NetException]] by variance alone (Fiber.Unsafe is covariant in A and contravariant in S, and
            // Abort is contravariant in E), so nothing here crosses the opaque-alias boundary: the value is already a Fiber.Unsafe and the
            // cast is not required to obtain one. It is kept for uniformity with the module's other Fiber.Unsafe boundary casts, which
            // recover the opaque alias from a plain IOPromise and do need it.
            return Fiber.Unsafe.fromResult(Result.fail(NetTlsHandshakeException(
                upgradeHost,
                -1,
                "verifying client has no reference identity: upgradeToTls requires sniHostname to verify the server certificate (set trustAll " +
                    "or hostnameVerification = false to opt out of name verification)"
            ))).asInstanceOf[Fiber.Unsafe[NetConnection, Abort[NetException]]]
        end if
        val promise = new IOPromise[NetException, Connection[JsHandle]]

        // Safety: the caller must not have I/O in flight during upgrade. For Postgres sslmode=prefer,
        // the protocol guarantees this: SSLRequest is sent, one-byte response is read, then upgrade is
        // called. The plaintext socket is paused at this point (no "data" events pending). JS is
        // single-threaded so no concurrent read can arrive between the response delivery and this call.
        // Safe: the guard above already confirmed conn.isInstanceOf[Connection[?]] && ....handle.isInstanceOf[JsHandle] before this point
        // (the guard's return is the only path past it), so this narrowing to Connection[JsHandle] cannot fail.
        val jsConn = conn.asInstanceOf[Connection[JsHandle]]
        val handle = jsConn.handle
        val socket = handle.socket // existing net.Socket

        // One upgrade per connection: win the one-shot claim BEFORE arming any shared upgrade state (the owner hook and abandon thunk
        // below, the listener rewiring). A second upgradeToTls call on the same connection would otherwise overwrite the in-flight
        // upgrade's `upgradeAbandon` with a thunk over its own about-to-fail promise (permanently disarming close()'s only route to the
        // first upgrade's socket) and, via the unguarded detach fallthrough, strip the listeners and re-wrap a socket the first upgrade
        // still owns. The loser must touch nothing, so it returns its own failed fiber without ever installing the destroy hook.
        if !jsConn.claimUpgrade() then
            // Same erased-boundary uniformity cast as the guards above (the value conforms by variance alone).
            return Fiber.Unsafe.fromResult(Result.fail(NetAlreadyDetachedException()))
                .asInstanceOf[Fiber.Unsafe[NetConnection, Abort[NetException]]]
        end if

        // `promise` owns the detached socket for the whole upgrade. detachForUpgrade gives up the plaintext connection's ownership WITHOUT
        // destroying the socket (the upgrade reuses it), and the TLSSocket below only takes ownership on a successful handshake, so every
        // non-success settlement must release it here or the socket is held forever: Node keeps the fd open, the peer never sees a FIN, and the
        // connection sits in CLOSE_WAIT. Mirrors the NIO floor's closeQuietly(handle.channel) owner arm. destroy() is the same release
        // JsIoDriver.closeHandle performs for an ordinary plaintext teardown, guarded identically so a socket Node already destroyed (the TLS
        // "error" arm below, a peer reset) is not destroyed twice.
        promise.onComplete {
            case Result.Success(_) =>
                // The upgraded connection's JsHandle owns the TLSSocket, which owns this socket: releasing it here would tear down a live
                // connection the caller was just handed.
                ()
            case _ =>
                // Node's net.Socket#destroyed is a documented boolean property; js.Dynamic erases that to an untyped JS value, so recovering
                // the typed Boolean needs this narrowing cast. Safe per Node's documented property type; it cannot dissolve without a typed
                // facade for Node's net.Socket.
                if !socket.destroyed.asInstanceOf[Boolean] then discard(socket.destroy())
        }
        // Route a close() of the plaintext connection to that owner: settling `promise` runs the same release a handshake failure takes, and once
        // the upgrade has succeeded the promise is complete and this is inherently a no-op, leaving the upgraded connection's socket untouched.
        // Armed BEFORE the detach, so no close() can observe the connection Upgrading without an owner to hand itself to. Without it a close() (a
        // scope teardown, a transport-level sweep) cannot reach a detached socket at all: Connection.closeFn never takes an Upgrading handle.
        jsConn.upgradeAbandon = Present(() => promise.interruptDiscard(Result.Failure(NetConnectionClosedException("close"))))

        // Detach closes channels and pauses+cancels the socket without destroying it.
        // Any bytes the ReadPump had already staged but caller had not consumed are returned.
        val preRead = jsConn.detachForUpgrade()
        if preRead.isEmpty then
            // The claim was won but the connection reached a terminal state before the detach (a close raced or preceded this call):
            // nothing was detached and the close path owns the socket. Fail typed; the owner hook above releases through its
            // destroyed-guarded destroy, a no-op for a socket the close already destroyed.
            promise.completeDiscard(Result.fail(NetAlreadyDetachedException()))
            return promise.asInstanceOf[Fiber.Unsafe[NetConnection, Abort[NetException]]]
        end if

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

        // The TLS role follows the connection's TCP origin: an accepted connection (isServerOrigin) upgrades as the TLS server, a connected one
        // as the client (STARTTLS initiate). The origin is authoritative: a config heuristic ("has a cert+key therefore server") would
        // misclassify a mutual-TLS client that presents its own client certificate, upgrading it in the server role.
        val isServerSide = jsConn.isServerOrigin

        val tlsSocket =
            if isServerSide then
                // Server-side STARTTLS: wrap the existing socket as a TLS server socket.
                // Node.js requires tls.TLSSocket constructor with isServer=true.
                val opts = js.Dynamic.literal()
                opts.isServer = true
                // Constrain the negotiated TLS version on the upgraded server socket (CWE-326).
                applyVersionBounds(opts, tls)
                tls.certChainPath match
                    case Present(p) => opts.cert = fsModule.readFileSync(p, "utf8")
                    case Absent     => ()
                tls.privateKeyPath match
                    case Present(p) => opts.key = fsModule.readFileSync(p, "utf8")
                    case Absent     => ()
                // Mutual TLS: honor clientAuth on the upgraded server socket, matching listen(tls) and the posix/NIO server STARTTLS path.
                applyServerClientAuth(opts, tls)
                // Construct TLSSocket directly with the existing socket and server opts
                js.Dynamic.newInstance(tlsModule.selectDynamic("TLSSocket"))(socket, opts)
            else
                // Client-side STARTTLS: use tls.connect({ socket }) which drives the TLS handshake.
                val opts = js.Dynamic.literal()
                opts.socket = socket
                val sni = tls.sniHostname.getOrElse("localhost")
                opts.servername = sni
                opts.host = sni
                // Constrain the negotiated TLS version on the upgraded client socket (CWE-326).
                applyVersionBounds(opts, tls)
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
                // Client certificate for mutual TLS: present it when the server requests one. A no-op for the common (no client cert) client.
                (tls.certChainPath, tls.privateKeyPath) match
                    case (Present(certPath), Present(keyPath)) =>
                        opts.cert = fsModule.readFileSync(certPath, "utf8")
                        opts.key = fsModule.readFileSync(keyPath, "utf8")
                    case _ => ()
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
                // Pause the TLS socket now that the handshake is done: kyo controls data flow.
                // (We cannot pause before the handshake as that blocks TLS record delivery.)
                discard(tlsSocket.pause())
                val newHandle = JsHandle.init(tlsSocket, driver)
                val newConn   = Connection.init(newHandle, driver, channelCapacity)
                // Preserve the upgrade role on the new TLS connection so a further upgrade does not silently flip client/server.
                newConn.isServerOrigin = isServerSide
                // Wire upgrade function on the new TLS connection so further upgrade attempts
                // are routed back through this transport (which will then fail with a TLS-on-TLS error).
                newConn.upgradeFn = Present { (tls2, frame2) =>
                    given Frame = frame2
                    upgradeToTls(newConn, tls2, channelCapacity)
                }
                // Install certHashFn so SCRAM-PLUS channel binding (RFC 5929
                // tls-server-end-point) can read the peer-cert SHA-256.
                installCertHashFn(newConn, tlsSocket)
                if newConn.start() then
                    // Checked complete, mirroring the NIO completeConnect: the abandon path can settle `promise` (and destroy the raw
                    // socket) while this handshake-completion event was already queued on the Node event loop. Discarding the lost
                    // completion would leave this freshly built connection's pumps parked forever on a socket nobody references; close
                    // the orphan instead. The caller sees the settlement it already observed.
                    if !promise.complete(Result.succeed(newConn)) then
                        newConn.close()
                else
                    // The upgraded connection raced to a terminal/Upgrading state before start (a close won); it must not be handed out as open.
                    promise.completeDiscard(Result.fail(NetConnectionClosedException("start")))
                end if
            }: js.Function0[Unit]
        ))

        discard(tlsSocket.once(
            "error",
            { (err: js.Dynamic) =>
                promise.completeDiscard(Result.fail(NetTlsHandshakeException(upgradeHost, -1, errMessage(err))))
            }: js.Function1[js.Dynamic, Unit]
        ))

        // Fiber.Unsafe[A, S] is an opaque alias over IOPromiseBase[Any, A < (Async & S)] (kyo.Fiber.scala), structurally different from this
        // plainly-constructed, invariant IOPromise[NetException, Connection[JsHandle]], even though both erase to the same runtime object;
        // the alias is transparent only inside kyo.Fiber's own defining scope, so exposing this promise as the locked
        // Transport.upgradeToTls return needs this erased-boundary cast. Safe: the promise completes only with the NetException/Connection
        // values above.
        promise.asInstanceOf[Fiber.Unsafe[NetConnection, Abort[NetException]]]
    end upgradeToTls

end JsTransport

/** Factory for `JsTransport`. Creates a pool of `JsIoDriver` instances (usually just one, since JS is single-threaded). */
private[kyo] object JsTransport:
    def init(poolSize: Int, channelCapacity: Int, connectTimeout: Duration, handshakeTimeout: Duration)(using
        AllowUnsafe,
        Frame
    ): JsTransport =
        // Obtain each driver through the capability-probed registry rather than constructing JsIoDriver
        // directly. The JS registry holds only NodeBackend, so the selected driver is the same JsIoDriver used
        // before the registry existed; -Dkyo.net.backend can force/observe the selection.
        val drivers =
            Array.tabulate[IoDriver[JsHandle]](poolSize)(i =>
                kyo.net.internal.backend.IoBackendPlatform.driver(kyo.net.TransportConfig.default)
            )
        val pool = IoDriverPool.init(drivers)
        pool.start()
        new JsTransport(pool, channelCapacity, connectTimeout, handshakeTimeout)
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
    // Unsafe: created at construction with no ambient AllowUnsafe; the danger bridge builds it here and its accesses run under the caller's
    // AllowUnsafe.
    private val closedFlag = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger)

    // Write-once address fields: `_address` (constructor), `_port`, and `_host` are written exactly once, in `setAddress` from the listen
    // callback, and read-only thereafter. The Node event loop is single-threaded, so the write happens-before every later read on the same loop
    // and the bare `var`s need no fence (this mirrors the write-once address fields on PosixListener / NioListener, for cross-platform parity).
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

    def isClosed(using AllowUnsafe): Boolean = closedFlag.get()

    def close()(using AllowUnsafe, Frame): Unit =
        if closedFlag.compareAndSet(false, true) then
            discard(server.close())
        end if
    end close
end JsListener
