package kyo.net

import kyo.*
import kyo.net.internal.JsTransport
import scala.scalajs.js as sjs

/** TLS version-enforcement and reference-identity coverage for the JS (Node.js) transport.
  *
  * These exercise behavior that is JS-specific: the mapping from [[NetTlsConfig.minVersion]]/[[NetTlsConfig.maxVersion]] and the empty-host
  * reference-identity decision onto the Node `tls` option objects that [[kyo.net.internal.JsTransport]] builds in `connect` and `listen`. The
  * posix/NIO providers carry the same controls in their own provider sources with their own tests; these assert the Node-tls surface reaches
  * the identical accept/reject decision so all four providers converge.
  *
  * The version tests use a real Node `tls` peer capped at a single protocol version on the other end of an in-process loopback pair, so the
  * version constraint is genuinely unsatisfiable when the two sides disagree (the handshake cannot fall back). The empty-host test uses the
  * kyo client against a real kyo TLS server. Every step is gated on a connection-fiber or a `Promise` completion fired from the Node
  * `listening`/`secureConnect` callback; there is no sleep or wall-clock timeout used as synchronization.
  */
class JsTransportTlsTest extends Test:

    import AllowUnsafe.embrace.danger

    // Self-signed certificate for CN=localhost with SAN=DNS:localhost,IP:127.0.0.1 (the canonical TlsTestCertShared fixture).
    private val localhostCertPem: String = TlsTestCertShared.certPem

    private val localhostKeyPem: String = TlsTestCertShared.keyPem

    private val tls      = sjs.Dynamic.global.require("tls")
    private val fs       = sjs.Dynamic.global.require("fs")
    private val os       = sjs.Dynamic.global.require("os")
    private val nodePath = sjs.Dynamic.global.require("path")

    private def writeTempPem(content: String, name: String): String =
        val dir  = os.tmpdir().asInstanceOf[String]
        val path = nodePath.join(dir, name).asInstanceOf[String]
        fs.writeFileSync(path, content)
        path
    end writeTempPem

    private lazy val localhostCertPath: String = writeTempPem(localhostCertPem, "kyo-js-tlsver-cert.pem")
    private lazy val localhostKeyPath: String  = writeTempPem(localhostKeyPem, "kyo-js-tlsver-key.pem")

    /** Start a real Node `tls` server pinned to a single protocol version (`minVersion == maxVersion == version`). The server accepts a single
      * connection, echoes the first chunk back, then closes. Returns the bound port through a `Promise` completed from the Node `listening`
      * callback so the test stays gated on callbacks rather than a sleep.
      */
    private def startPinnedTlsServer(version: String)(using Frame): (sjs.Dynamic, Int) < Async =
        Promise.init[Int, Any].map { portPromise =>
            Sync.Unsafe.defer {
                val opts = sjs.Dynamic.literal(
                    cert = fs.readFileSync(localhostCertPath, "utf8"),
                    key = fs.readFileSync(localhostKeyPath, "utf8"),
                    minVersion = version,
                    maxVersion = version
                )
                val server = tls.createServer(
                    opts,
                    { (socket: sjs.Dynamic) =>
                        discard(socket.on(
                            "data",
                            { (chunk: sjs.Any) =>
                                discard(socket.write(chunk))
                            }: sjs.Function1[sjs.Any, Unit]
                        ))
                        // A failed handshake surfaces here on the server; swallow so the process does not abort.
                        discard(socket.on("error", { (_: sjs.Any) => () }: sjs.Function1[sjs.Any, Unit]))
                    }: sjs.Function1[sjs.Dynamic, Unit]
                )
                discard(server.on("error", { (_: sjs.Any) => () }: sjs.Function1[sjs.Any, Unit]))
                discard(server.listen(
                    0,
                    "127.0.0.1",
                    { () =>
                        val port = server.address().port.asInstanceOf[Int]
                        portPromise.unsafe.completeDiscard(Result.succeed(port))
                    }: sjs.Function0[Unit]
                ))
                server
            }.map(server => portPromise.get.map(port => (server, port)))
        }
    end startPinnedTlsServer

    /** Drive a real Node `tls` client with the given version floor against `port`, completing the returned `Promise` with `true` on
      * `secureConnect` and `false` on `error`. The kyo TLS server under test sits on the other end. Gated entirely on the Node callbacks.
      */
    private def pinnedTlsClientConnects(port: Int, minVersion: String)(using Frame): Boolean < Async =
        Promise.init[Boolean, Any].map { result =>
            Sync.Unsafe.defer {
                val opts = sjs.Dynamic.literal(
                    host = "127.0.0.1",
                    port = port,
                    minVersion = minVersion,
                    rejectUnauthorized = false
                )
                val socket = tls.connect(opts)
                discard(socket.once(
                    "secureConnect",
                    { () =>
                        discard(socket.destroy())
                        result.unsafe.completeDiscard(Result.succeed(true))
                    }: sjs.Function0[Unit]
                ))
                discard(socket.once(
                    "error",
                    { (_: sjs.Any) =>
                        result.unsafe.completeDiscard(Result.succeed(false))
                    }: sjs.Function1[sjs.Any, Unit]
                ))
            }.andThen(result.get)
        }
    end pinnedTlsClientConnects

    "client minVersion is enforced against a TLS1.2-pinned server (rejects the silent downgrade)" in {
        val transport = NetPlatform.transport
        // Client demands TLS1.3 only; the real Node server can speak only TLS1.2. With minVersion mapped onto Node's tls options there is no
        // common version, so the handshake must be rejected. If the client minVersion were dropped, Node would negotiate TLS1.2, and the
        // connection would silently succeed (CWE-326).
        val clientTls13 = NetTlsConfig(
            trustAll = true,
            sniHostname = Present("localhost"),
            minVersion = NetTlsConfig.Version.TLS13,
            maxVersion = NetTlsConfig.Version.TLS13
        )
        for
            serverAndPort <- startPinnedTlsServer("TLSv1.2")
            (server, port) = serverAndPort
            result <- Abort.run[NetException](transport.connectTls("127.0.0.1", port, clientTls13).safe.get)
        yield
            discard(server.close())
            assert(result.isFailure, s"a TLS1.3-only client must be rejected by a TLS1.2-only server, got: $result")
        end for
    }

    "client minVersion permits the handshake when the pinned server matches" in {
        val transport = NetPlatform.transport
        // Control arm: same TLS1.3 floor, but the server speaks TLS1.3, so the version constraint is satisfiable and the handshake succeeds.
        // This proves the rejection above is the version mismatch, not minVersion mapping breaking every handshake.
        val clientTls13 = NetTlsConfig(
            trustAll = true,
            sniHostname = Present("localhost"),
            minVersion = NetTlsConfig.Version.TLS13,
            maxVersion = NetTlsConfig.Version.TLS13
        )
        for
            serverAndPort <- startPinnedTlsServer("TLSv1.3")
            (server, port) = serverAndPort
            result <- Abort.run[NetException](transport.connectTls("127.0.0.1", port, clientTls13).safe.get)
        yield
            discard(server.close())
            assert(result.isSuccess, s"a TLS1.3 client against a TLS1.3 server must succeed, got: $result")
        end for
    }

    "server maxVersion is enforced against a TLS1.3-demanding client" in {
        val transport = NetPlatform.transport
        // kyo TLS server capped at TLS1.2; a real Node client demanding a TLS1.3 floor must be rejected once maxVersion is mapped onto the
        // server's tls options. If the server maxVersion were dropped, the server would allow TLS1.3 and the client would succeed.
        val serverTls12 = NetTlsConfig(
            certChainPath = Present(localhostCertPath),
            privateKeyPath = Present(localhostKeyPath),
            minVersion = NetTlsConfig.Version.TLS12,
            maxVersion = NetTlsConfig.Version.TLS12
        )
        for
            listener <- transport.listenTls("127.0.0.1", 0, 128, serverTls12) { serverConn =>
                // Drain so a successful handshake does not leave the socket hanging; ignore failures.
                discard(Sync.Unsafe.evalOrThrow {
                    Fiber.initUnscoped {
                        Abort.run[Closed](serverConn.inbound.safe.take.unit).unit
                    }
                })
            }.safe.get
            port = listener.port
            connected <- pinnedTlsClientConnects(port, "TLSv1.3")
        yield
            listener.close()
            assert(!connected, "a TLS1.3-demanding client must be rejected by a TLS1.2-capped server")
        end for
    }

    "verifying client with an empty host fails closed before connecting" in {
        val transport = NetPlatform.transport
        // Verifying client (hostnameVerification = true, trustAll = false) with an empty host has no reference identity to check the server
        // certificate against. It must fail closed, matching SslEngineProvider/BoringSslProvider/SystemOpenSslProvider. Passing the
        // empty host to Node as the servername would let identity fall back to Node's default checkServerIdentity (RFC 9525 6.1 gap).
        val serverTls = NetTlsConfig(
            certChainPath = Present(localhostCertPath),
            privateKeyPath = Present(localhostKeyPath)
        )
        val verifyingClient = NetTlsConfig(
            caCertPath = Present(localhostCertPath),
            hostnameVerification = true,
            trustAll = false
        )
        for
            listener <- transport.listenTls("127.0.0.1", 0, 128, serverTls) { serverConn =>
                discard(Sync.Unsafe.evalOrThrow {
                    Fiber.initUnscoped {
                        Abort.run[Closed](serverConn.inbound.safe.take.unit).unit
                    }
                })
            }.safe.get
            port = listener.port
            result <- Abort.run[NetException](transport.connectTls("", port, verifyingClient).safe.get)
        yield
            listener.close()
            assert(result.isFailure, s"a verifying client with an empty host must fail closed, got: $result")
        end for
    }

    "verifying client with a matching host still connects" in {
        val transport = NetPlatform.transport
        // Control arm for the empty-host fail-closed: the same verifying client with a real reference identity must still connect, proving the
        // fail-closed is scoped to the missing-identity case and not a blanket rejection of verifying clients.
        val serverTls = NetTlsConfig(
            certChainPath = Present(localhostCertPath),
            privateKeyPath = Present(localhostKeyPath)
        )
        val verifyingClient = NetTlsConfig(
            caCertPath = Present(localhostCertPath),
            hostnameVerification = true,
            trustAll = false,
            sniHostname = Present("localhost")
        )
        for
            // Listen on "localhost" (not 127.0.0.1): the verifying client must connect by NAME for hostname verification, and Node resolves
            // "localhost" to ::1 first on some hosts (verbatim DNS result order). Binding and connecting through the same name makes both
            // sides share one resolution, so the leaf does not depend on which address family the host lists first.
            listener <- transport.listenTls("localhost", 0, 128, serverTls) { serverConn =>
                discard(Sync.Unsafe.evalOrThrow {
                    Fiber.initUnscoped {
                        Abort.run[Closed](serverConn.inbound.safe.take.unit).unit
                    }
                })
            }.safe.get
            port = listener.port
            result <- Abort.run[NetException](transport.connectTls("localhost", port, verifyingClient).safe.get)
        yield
            listener.close()
            assert(result.isSuccess, s"a verifying client with a matching host must connect, got: $result")
        end for
    }

    private val serverTlsMaterial = NetTlsConfig(
        certChainPath = Present(localhostCertPath),
        privateKeyPath = Present(localhostKeyPath)
    )

    /** Open a raw Node TCP socket to `port` (completing the TCP accept) and then send NOTHING, so the TLS handshake never starts. Returns a
      * `Promise[Boolean]` completed `true` when the socket is closed by the far end (the server's handshake-deadline reap destroying it) and a
      * thunk that destroys the client socket for teardown. The close event is the deterministic latch: no sleep is used to detect the reap.
      */
    private def stalledRawClient(port: Int)(using Frame): (Boolean < Async, () => Unit) =
        import AllowUnsafe.embrace.danger
        val net    = sjs.Dynamic.global.require("net")
        val closed = Sync.Unsafe.evalOrThrow(Promise.init[Boolean, Any])
        val socket = net.connect(port, "127.0.0.1")
        discard(socket.on("connect", { () => () }: sjs.Function0[Unit]))
        // The server reaping the stalled handshake destroys the accepted socket; the client observes that as "close".
        discard(socket.on("close", { (_: sjs.Any) => closed.unsafe.completeDiscard(Result.succeed(true)) }: sjs.Function1[sjs.Any, Unit]))
        discard(socket.on("error", { (_: sjs.Any) => () }: sjs.Function1[sjs.Any, Unit]))
        (closed.get, () => discard(socket.destroy()))
    end stalledRawClient

    "a stalled server TLS handshake is reaped after the deadline (socket destroyed)" in {
        // A JsTransport with a finite handshakeTimeout. A raw TCP client completes the accept but never sends a ClientHello, so Node's
        // "secureConnection" never fires and the accepted socket would linger forever. The deadline timer destroys it; the client's "close" is
        // the deterministic latch (no sleep waits for the timeout). Without a deadline the socket would linger and "close"
        // would never fire, hanging the test (the symptom of the unreaped stall).
        import AllowUnsafe.embrace.danger
        val transport =
            JsTransport.init(poolSize = 1)
        for
            listener <- transport.listenTls("127.0.0.1", 0, 128, serverTlsMaterial.copy(handshakeTimeout = 150.millis)) { _ => () }.safe.get
            port                    = listener.port
            (reaped, destroyClient) = stalledRawClient(port)
            wasReaped <- reaped
        yield
            destroyClient()
            listener.close()
            assert(wasReaped, "the stalled TLS handshake must be reaped (the accepted socket destroyed) after the deadline")
        end for
    }

    "a handshake that completes within the deadline is NOT reaped (timer disarmed)" in {
        // Control arm: the same finite deadline, but a real kyo TLS client completes the handshake well within it. The connection must work
        // normally (echo round-trip), proving the deadline timer is disarmed on a successful handshake and does not reap a healthy connection.
        import AllowUnsafe.embrace.danger
        val transport =
            JsTransport.init(poolSize = 1)
        val clientTls = NetTlsConfig(trustAll = true, sniHostname = Present("localhost"))
        for
            listener <- transport.listenTls("127.0.0.1", 0, 128, serverTlsMaterial.copy(handshakeTimeout = 2.seconds)) { serverConn =>
                discard(Sync.Unsafe.evalOrThrow {
                    Fiber.initUnscoped {
                        Abort.run[Closed](serverConn.inbound.safe.take.map(chunk => serverConn.outbound.safe.put(chunk))).unit
                    }
                })
            }.safe.get
            port = listener.port
            client <- transport.connectTls("127.0.0.1", port, clientTls).safe.get
            _      <- client.outbound.safe.put(Span.from("ping".getBytes("UTF-8")))
            echo   <- client.inbound.safe.take
        yield
            client.close()
            listener.close()
            assert(
                new String(echo.toArrayUnsafe, "UTF-8") == "ping",
                s"the completed handshake's connection must work; got ${echo.size} bytes"
            )
        end for
    }

    "a stalled server TLS handshake is not reaped when handshakeTimeout is Infinity" in {
        // With handshakeTimeout = Infinity the server arms no deadline timer: a stalled handshake parks forever and is not reaped. A bounded
        // observation window (an Async suspension, not a thread block) is the no-reap ceiling; with Infinity the client's "close" must not fire.
        import AllowUnsafe.embrace.danger
        val transport =
            JsTransport.init(poolSize = 1)
        for
            listener <- transport.listenTls("127.0.0.1", 0, 128, serverTlsMaterial.copy(handshakeTimeout = Duration.Infinity)) { _ =>
                ()
            }.safe.get
            port                    = listener.port
            (reaped, destroyClient) = stalledRawClient(port)
            // Race the reap signal against a bounded window: if the window wins, the stall was NOT reaped (the expected Infinity behavior).
            outcome <- Async.race(reaped.map(_ => true), Async.sleep(400.millis).andThen(false))
        yield
            destroyClient()
            listener.close()
            assert(!outcome, "a stalled handshake must not be reaped when handshakeTimeout is Infinity")
        end for
    }

    // Same leak the posix and NIO backends had: a socket whose TLS handshake never completed never becomes a connection this transport knows
    // about, and Node's server.close() stops accepting without releasing it, so nothing reclaimed it and the process-shared transport is never
    // closed. Removing the tracking makes this leaf fail with a timeout, which is the leak: the peer stays connected to a socket nobody owns.
    //
    // Pinned with handshakeTimeout = Infinity so no deadline can do the reclaiming instead, and the peer is held open across the assertion,
    // since closing it would end the handshake by itself and the leaf would stop testing the discharge.
    "closing a listener releases accepted sockets whose handshake never settled" in {
        val transport = JsTransport.init(poolSize = 1)
        val unbounded = serverTlsMaterial.copy(handshakeTimeout = Duration.Infinity)
        for
            listener <- transport.listenTls("127.0.0.1", 0, 128, unbounded) { _ => () }.safe.get
            client   <- transport.connect("127.0.0.1", listener.port).safe.get
            _        <- Sync.defer(listener.close())
            outcome  <- Abort.run[Timeout](Async.timeout(3.seconds)(Abort.run[Closed](client.inbound.safe.take)))
            _        <- Sync.defer(client.close())
        yield assert(
            outcome.isSuccess,
            s"closing the listener must destroy its unsettled accepted socket, got $outcome"
        )
        end for
    }

end JsTransportTlsTest
