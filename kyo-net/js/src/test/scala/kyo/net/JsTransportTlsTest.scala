package kyo.net

import kyo.*
import kyo.net.internal.JsListener
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
  *
  * Every test builds a [[kyo.net.internal.JsTransport]] by name. `NetPlatform.transport` selects the koffi posix transport wherever its native
  * loads, and TLS there runs through BoringSSL, so it would neither reach the Node mapping under test nor run on a host without BoringSSL.
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
        val transport = JsTransport.init(poolSize = 1)
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
        val transport = JsTransport.init(poolSize = 1)
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
            // This handshake succeeds, so the result carries a live connection. Node's server.close() only stops accepting and never
            // releases an established socket, so closing the connection here is the only thing that reclaims either end.
            result.foreach(_.close())
            discard(server.close())
            assert(result.isSuccess, s"a TLS1.3 client against a TLS1.3 server must succeed, got: $result")
        end for
    }

    "server maxVersion is enforced against a TLS1.3-demanding client" in {
        val transport = JsTransport.init(poolSize = 1)
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
        val transport = JsTransport.init(poolSize = 1)
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
        val transport = JsTransport.init(poolSize = 1)
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
            // Bind and connect on the 127.0.0.1 literal so both sides pin IPv4 with no DNS lookup: localhost can resolve ::1-first and a
            // split family would miss the server's ephemeral port. Verification checks sniHostname "localhost" against the cert SAN, not the connect host.
            listener <- transport.listenTls("127.0.0.1", 0, 128, serverTls) { serverConn =>
                discard(Sync.Unsafe.evalOrThrow {
                    Fiber.initUnscoped {
                        Abort.run[Closed](serverConn.inbound.safe.take.unit).unit
                    }
                })
            }.safe.get
            port = listener.port
            result <- Abort.run[NetException](transport.connectTls("127.0.0.1", port, verifyingClient).safe.get)
        yield
            // This handshake succeeds, so the result carries a live connection. A listener close only reclaims sockets still mid-handshake,
            // never one already handed to the accept handler, so closing the client here is what releases both ends (its FIN tears down the
            // accepted side).
            result.foreach(_.close())
            listener.close()
            assert(result.isSuccess, s"a verifying client with a matching host must connect, got: $result")
        end for
    }

    // Node exposes no getter for TCP_NODELAY. net.Socket.setNoDelay records the applied value under its private `kSetNoDelay` symbol, and only
    // after forwarding it to a handle that implements it, which for a TLSSocket is the TCP handle under the TLS layer. Reading that symbol is
    // the one deterministic observation of the option; its absence fails the leaf rather than passing it.
    private def noDelayOf(conn: Connection): Maybe[Boolean] =
        val socket = conn.asInstanceOf[kyo.net.internal.transport.Connection[kyo.net.internal.JsHandle]].handle.socket
        val syms   = sjs.Dynamic.global.Object.getOwnPropertySymbols(socket).asInstanceOf[sjs.Array[sjs.Dynamic]]
        Maybe.fromOption(syms.find(sym => sjs.special.strictEquals(sym.description, "kSetNoDelay")))
            .map(sym => sjs.Dynamic.global.Reflect.get(socket, sym).asInstanceOf[Boolean])
    end noDelayOf

    "TLS connections disable Nagle on both the connecting and the accepted socket" in {
        import AllowUnsafe.embrace.danger
        val transport = JsTransport.init(poolSize = 1)
        for
            accepted <- Promise.init[Connection, Any]
            listener <- transport.listenTls("127.0.0.1", 0, 128, serverTlsMaterial) { serverConn =>
                accepted.unsafe.completeDiscard(Result.succeed(serverConn))
            }.safe.get
            client <- transport.connectTls("127.0.0.1", listener.port, NetTlsConfig(trustAll = true)).safe.get
            server <- accepted.get
        yield
            val clientNoDelay = noDelayOf(client)
            val serverNoDelay = noDelayOf(server)
            client.close()
            server.close()
            listener.close()
            assert(clientNoDelay == Present(true), s"connecting TLS socket TCP_NODELAY: $clientNoDelay")
            assert(serverNoDelay == Present(true), s"accepted TLS socket TCP_NODELAY: $serverNoDelay")
        end for
    }

    private val serverTlsMaterial = NetTlsConfig(
        certChainPath = Present(localhostCertPath),
        privateKeyPath = Present(localhostKeyPath)
    )

    "the Node transport reports no TLS close reason: a clean peer close reads Active, and the transport says so" in {
        // Node's tls.TLSSocket surfaces a close_notify-then-FIN and a bare FIN identically, so the transport cannot tell CleanClose from
        // Truncated and must not claim to. Both halves are pinned on the transport itself: the status after a clean peer close, and the
        // capability a caller consults before trusting an Active status at the close.
        val clientTls = NetTlsConfig(trustAll = true, sniHostname = Present("localhost"))
        onFrozenClock { transport =>
            for
                accepted <- Promise.init[Connection, Any]
                listener <- transport.listenTls("127.0.0.1", 0, 128, serverTlsMaterial) { serverConn =>
                    accepted.unsafe.completeDiscard(Result.succeed(serverConn))
                }.safe.get
                client <- transport.connectTls("127.0.0.1", listener.port, clientTls).safe.get
                server <- accepted.get
                _ = server.close() // Node sends close_notify, then the FIN
                _ <- Abort.run[Closed](Loop.foreach(client.inbound.safe.take.map(_ => Loop.continue)))
            yield
                val reason = client.status
                client.close()
                listener.close()
                assert(
                    reason == Connection.Status.Active,
                    s"the Node transport observes no close reason, so the status must stay Active; got $reason"
                )
                assert(!transport.reportsTlsCloseReason, "the Node transport must declare that it cannot report the TLS close reason")
            end for
        }
    }

    /** Runs `f` over a transport whose clock is controlled and never advanced, so no connection's `peerCloseGrace` can end during it: a
      * graceful close that completes did so because Node reported the output flushed, never because the grace destroyed the socket.
      */
    private def onFrozenClock[A](f: JsTransport => A < (Async & Abort[NetException]))(using Frame): A < (Async & Abort[NetException]) =
        Clock.withTimeControl { _ =>
            Clock.get.map(clock => f(JsTransport.init(poolSize = 1, clock = clock)))
        }

    /** A raw Node client (`tls` or `net`) to `port` that collects every byte the server sends, completed when the server's end reaches it.
      * The `end` event is the latch (Node emits it once the peer's FIN follows the last byte), with `close` and `error` as the settle for a
      * server that destroyed the socket instead; whatever arrived by then is the answer, so a short read is a failed assertion, not a hang.
      */
    private def rawClientReadsUntilEnd(port: Int, overTls: Boolean)(using Frame): Array[Byte] < Async =
        Promise.init[Array[Byte], Any].map { received =>
            Sync.Unsafe.defer {
                var collected = Array.emptyByteArray
                val socket    =
                    if overTls then tls.connect(sjs.Dynamic.literal(host = "127.0.0.1", port = port, rejectUnauthorized = false))
                    else sjs.Dynamic.global.require("net").connect(port, "127.0.0.1")
                discard(socket.on(
                    "data",
                    { (chunk: sjs.Dynamic) =>
                        val u8  = chunk.asInstanceOf[sjs.typedarray.Uint8Array]
                        val arr = new Array[Byte](u8.length)
                        var i   = 0
                        while i < arr.length do
                            arr(i) = u8(i).toByte
                            i += 1
                        collected = collected ++ arr
                    }: sjs.Function1[sjs.Dynamic, Unit]
                ))
                val settle: sjs.Function1[sjs.Any, Unit] = (_: sjs.Any) => received.unsafe.completeDiscard(Result.succeed(collected))
                discard(socket.on("end", settle))
                discard(socket.on("close", settle))
                discard(socket.on("error", settle))
            }.andThen(received.get)
        }

    /** A server that writes `head` and then `body` to each accepted connection and closes it at once: a response head, a body chunk and the
      * close that follows a failed stream, queued back to back so the write pump issues both writes and the close on one event-loop turn.
      */
    private def writeThenCloseHandler(head: Array[Byte], body: Array[Byte]): Connection => Unit = serverConn =>
        discard(Sync.Unsafe.evalOrThrow {
            Fiber.initUnscoped {
                Abort.run[Closed] {
                    serverConn.outbound.safe.put(Span.from(head)).andThen(serverConn.outbound.safe.put(Span.from(body)))
                }.map(_ => serverConn.close())
            }
        })

    private val responseHead: Array[Byte] = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes("UTF-8")
    private val responseBody: Array[Byte] = "abc".getBytes("UTF-8")

    "a TLS connection closed right after two writes delivers every byte before the peer's end" in {
        // Node encrypts a tls.TLSSocket write at once but hands the ciphertext to the underlying socket only when no earlier write is still
        // in flight there, and a write's completion lands on a later event-loop turn even on loopback. The second of two back-to-back writes
        // therefore waits in Node's TLS output until the first completes; a close that destroys the socket on the same turn discards it,
        // invisibly to the write pump (socket.write already accepted it) and to every caller. A graceful close must end the writable side
        // and let Node flush before the socket goes away.
        val expected = responseHead ++ responseBody
        onFrozenClock { transport =>
            for
                listener <- transport.listenTls("127.0.0.1", 0, 128, serverTlsMaterial)(
                    writeThenCloseHandler(responseHead, responseBody)
                ).safe.get
                received <- rawClientReadsUntilEnd(listener.port, overTls = true)
            yield
                listener.close()
                assert(
                    received.toSeq == expected.toSeq,
                    s"the peer must read all ${expected.length} bytes written before the close over TLS; got ${received.length}"
                )
            end for
        }
    }

    "a plaintext connection closed right after two writes delivers every byte before the peer's end" in {
        // The plain-socket sibling: a net.Socket write reaches the kernel synchronously when it can, so this arm holds today and pins that
        // the graceful close keeps the plain path whole.
        val expected = responseHead ++ responseBody
        onFrozenClock { transport =>
            for
                listener <- transport.listen("127.0.0.1", 0, 128)(writeThenCloseHandler(responseHead, responseBody)).safe.get
                received <- rawClientReadsUntilEnd(listener.port, overTls = false)
            yield
                listener.close()
                assert(
                    received.toSeq == expected.toSeq,
                    s"the peer must read all ${expected.length} bytes written before the close; got ${received.length}"
                )
            end for
        }
    }

    /** Open a raw Node TCP socket to `port` (completing the TCP accept) and then send NOTHING, so the TLS handshake never starts. Returns the
      * promise completed `true` when the far end closes the socket (the server's handshake-deadline reap destroying it) and the socket itself.
      */
    private def stalledRawClient(port: Int)(using Frame): (Promise[Boolean, Any], sjs.Dynamic) =
        import AllowUnsafe.embrace.danger
        val net    = sjs.Dynamic.global.require("net")
        val closed = Sync.Unsafe.evalOrThrow(Promise.init[Boolean, Any])
        val socket = net.connect(port, "127.0.0.1")
        discard(socket.on("connect", { () => () }: sjs.Function0[Unit]))
        // The server reaping the stalled handshake destroys the accepted socket; the client observes that as "close".
        discard(socket.on("close", { (_: sjs.Any) => closed.unsafe.completeDiscard(Result.succeed(true)) }: sjs.Function1[sjs.Any, Unit]))
        discard(socket.on("error", { (_: sjs.Any) => () }: sjs.Function1[sjs.Any, Unit]))
        (closed, socket)
    end stalledRawClient

    /** Whether Node completes a client TLS handshake over `socket`, a raw socket already connected to a TLS listener. */
    private def handshakeOver(socket: sjs.Dynamic)(using Frame): Boolean < Async =
        Promise.init[Boolean, Any].map { done =>
            Sync.Unsafe.defer {
                val secure = tls.connect(sjs.Dynamic.literal(socket = socket, rejectUnauthorized = false, servername = "localhost"))
                discard(secure.on("secureConnect", { () => done.unsafe.completeDiscard(Result.succeed(true)) }: sjs.Function0[Unit]))
                discard(secure.on(
                    "error",
                    { (_: sjs.Any) => done.unsafe.completeDiscard(Result.succeed(false)) }: sjs.Function1[sjs.Any, Unit]
                ))
            }.andThen(done.get)
        }

    /** Runs `f` over a transport whose deadlines are on a controlled clock, closing its driver when the leaf's scope ends. */
    private def onJsClock[A](f: (JsTransport, Clock.TimeControl) => A < (Async & Abort[NetException | Closed] & Scope))(using
        Frame
    ): A < (Async & Abort[NetException | Closed] & Scope) =
        import AllowUnsafe.embrace.danger
        Clock.withTimeControl { tc =>
            Clock.get.map { clock =>
                val transport = JsTransport.init(poolSize = 1, clock = clock)
                Scope.ensure(Sync.Unsafe.defer(Test.closeDrivers(transport))).andThen(f(transport, tc))
            }
        }
    end onJsClock

    "a stalled server TLS handshake is reaped at the deadline (socket destroyed), not one tick before" in {
        // A raw TCP client completes the accept but never sends a ClientHello, so Node's "secureConnection" never fires and the accepted socket
        // would linger forever. The deadline destroys it; the client's "close" is the latch.
        onJsClock { (transport, tc) =>
            val timeout = 150.millis
            for
                listener <- transport.listenTls("127.0.0.1", 0, 128, serverTlsMaterial.copy(handshakeTimeout = timeout))(_ => ()).safe.get
                _        <- Scope.ensure(Sync.defer(listener.close()))
                (reaped, socket) = stalledRawClient(listener.port)
                _         <- tc.awaitPendingSleepers(1)
                _         <- tc.advance(timeout.minusOrZero(1.millis))
                _         <- tc.awaitPendingSleepers(1)
                _         <- tc.advance(1.millis)
                wasReaped <- reaped.get
            yield
                discard(socket.destroy())
                assert(wasReaped, "the stalled TLS handshake must be reaped (the accepted socket destroyed) at the deadline")
            end for
        }
    }

    "a handshake that completes within the deadline is NOT reaped past it (timer disarmed)" in {
        onJsClock { (transport, tc) =>
            val clientTls = NetTlsConfig(trustAll = true, sniHostname = Present("localhost"), handshakeTimeout = Duration.Infinity)
            for
                handled  <- Promise.init[Unit, Any]
                listener <- transport.listenTls("127.0.0.1", 0, 128, serverTlsMaterial.copy(handshakeTimeout = 30.seconds)) { serverConn =>
                    discard(Sync.Unsafe.evalOrThrow {
                        Fiber.initUnscoped {
                            Abort.run[Closed](serverConn.inbound.safe.take.map(chunk => serverConn.outbound.safe.put(chunk))).unit
                        }
                    })
                    handled.unsafe.completeDiscard(Result.succeed(()))
                }.safe.get
                _      <- Scope.ensure(Sync.defer(listener.close()))
                client <- transport.connectTls("127.0.0.1", listener.port, clientTls).safe.get
                _      <- Scope.ensure(Sync.defer(client.close()))
                // The handler runs after the server claimed its handshake guard, so the deadline can no longer destroy the socket.
                _    <- handled.get
                _    <- tc.advance(30.seconds + 1.millis)
                _    <- client.outbound.safe.put(Span.from("ping".getBytes("UTF-8")))
                echo <- Abort.run[Closed](client.inbound.safe.take)
            yield echo match
                case Result.Success(bytes) =>
                    assert(
                        new String(bytes.toArrayUnsafe, "UTF-8") == "ping",
                        s"the completed handshake's connection must work; got ${bytes.size} bytes"
                    )
                case other =>
                    fail(s"the completed handshake's echo did not arrive, so the connection did not round-trip: $other")
            end for
        }
    }

    "a stalled server TLS handshake is not reaped when handshakeTimeout is Infinity: it completes a year later" in {
        onJsClock { (transport, tc) =>
            for
                listener <-
                    transport.listenTls("127.0.0.1", 0, 128, serverTlsMaterial.copy(handshakeTimeout = Duration.Infinity))(_ => ()).safe.get
                _ <- Scope.ensure(Sync.defer(listener.close()))
                (_, subject) = stalledRawClient(listener.port)
                // The server registered the accepted socket, the moment a deadline would be armed.
                _         <- untilTurn(Sync.defer(listener.asInstanceOf[JsListener].pendingAcceptHandshakeCount > 0))
                _         <- tc.advance(365.days)
                completed <- handshakeOver(subject)
            yield
                discard(subject.destroy())
                assert(completed, "with handshakeTimeout = Infinity the stalled handshake must still complete after any stall")
            end for
        }
    }

    // Same leak the posix and NIO backends had: a socket whose TLS handshake never completed never becomes a connection this transport knows
    // about, and Node's server.close() stops accepting without releasing it, so nothing reclaimed it and the process-shared transport is never
    // closed. Removing the tracking makes this leaf hang to its cap, which is the leak: the peer stays connected to a socket nobody owns.
    //
    // Pinned with handshakeTimeout = Infinity so no deadline can do the reclaiming instead, and the peer is held open across the assertion,
    // since closing it would end the handshake by itself and the leaf would stop testing the discharge.
    "closing a listener releases accepted sockets whose handshake never settled" in {
        val transport = JsTransport.init(poolSize = 1)
        val unbounded = serverTlsMaterial.copy(handshakeTimeout = Duration.Infinity)
        for
            listener <- transport.listenTls("127.0.0.1", 0, 128, unbounded) { _ => () }.safe.get
            client   <- transport.connect("127.0.0.1", listener.port).safe.get
            // Barrier: the client's connect resolving proves only that IT saw the TCP handshake. Node's server-side "connection" event can
            // fire afterwards, in the same libuv turn, so closing here without waiting could sweep an empty list and let the leaf pass on
            // server.close() dropping a backlogged connection instead, whether or not the reclaim works at all.
            _ <- untilTurn(Sync.defer(listener.asInstanceOf[JsListener].pendingAcceptHandshakeCount > 0))
            _ <- Sync.defer(listener.close())
            // The discharge runs inside close() and must have emptied the registry, not merely stopped accepting.
            remaining = listener.asInstanceOf[JsListener].pendingAcceptHandshakeCount
            outcome <- Abort.run[Closed](client.inbound.safe.take)
            _       <- Sync.defer(client.close())
        yield
            val released = outcome match
                case Result.Success(span) => span.isEmpty
                case Result.Failure(_)    => true
                case _                    => false
            assert(remaining == 0, s"the listener close must discharge every unsettled accepted socket, $remaining left")
            assert(released, s"closing the listener must destroy its unsettled accepted socket, got $outcome")
        end for
    }

end JsTransportTlsTest
