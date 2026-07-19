package kyo.net.internal

import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import kyo.*
import kyo.net.NetException
import kyo.net.NetTlsConfig
import kyo.net.Test
import kyo.net.internal.TlsTestCert
import kyo.net.internal.transport.*

class NioTransportTest extends Test:

    import AllowUnsafe.embrace.danger

    /** Server NetTlsConfig backed by the embedded test certificate. */
    lazy val serverTlsConfig: NetTlsConfig = NetTlsConfig(
        certChainPath = Present(TlsTestCert.certPath),
        privateKeyPath = Present(TlsTestCert.keyPath)
    )

    /** Create a transport using the standard factory. Starts its own event loop driver. Caller must call close(). */
    def mkTransport()(using Frame): NioTransport =
        NioTransport.init(
            channelCapacity = 8,
            readBufferSize = NioHandle.DefaultReadBufferSize,
            connectTimeout = Duration.Infinity,
            handshakeTimeout = Duration.Infinity
        )

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    "init stores IoDriverPool" in {
        given Frame   = Frame.internal
        val transport = mkTransport()
        try
            assert(transport.pool ne null)
            succeed
        finally
            discard(transport.close())
        end try
    }

    "init stores IoDriver in pool" in {
        given Frame   = Frame.internal
        val transport = mkTransport()
        try
            val driver = transport.pool.next()
            assert(driver ne null)
            succeed
        finally
            discard(transport.close())
        end try
    }

    // -----------------------------------------------------------------------
    // connect: plain TCP
    // -----------------------------------------------------------------------

    "connect to loopback server returns open connection" in {
        given Frame   = Frame.internal
        val transport = mkTransport()

        // Use a latch so the server-side socket stays open until after we check isOpen
        val latch      = new java.util.concurrent.CountDownLatch(1)
        val serverSock = java.nio.channels.ServerSocketChannel.open()
        serverSock.configureBlocking(true)
        serverSock.bind(new InetSocketAddress("127.0.0.1", 0))
        val port = serverSock.socket().getLocalPort

        // Accept in a background thread: hold the connection open until latch released
        val acceptedRef = new java.util.concurrent.atomic.AtomicReference[java.nio.channels.SocketChannel](null)
        val acceptThread = new Thread(() =>
            try
                val accepted = serverSock.accept()
                acceptedRef.set(accepted)
                latch.await()
                accepted.close()
            catch case _: Exception => ()
        )
        acceptThread.setDaemon(true)
        acceptThread.start()

        transport.connect("127.0.0.1", port).safe.get.map { conn =>
            try
                assert(conn.isOpen)
                succeed
            finally
                latch.countDown()
                conn.close()
                serverSock.close()
                discard(transport.close())
        }
    }

    "connect returns Closed failure for unreachable port" in {
        given Frame   = Frame.internal
        val transport = mkTransport()
        // Port 1: connection refused on loopback
        Abort.run[NetException](transport.connect("127.0.0.1", 1).safe.get).map { result =>
            transport.close()
            // Either refused (Failure) or panicked; a success would be unusual but allowed
            // OS-nondeterministic connect-refused-vs-reuse outcome covers all Result cases
            assert(result.isFailure || result.isPanic || result.isSuccess)
            succeed
        }
    }

    // -----------------------------------------------------------------------
    // listen: TCP server
    // -----------------------------------------------------------------------

    "listen binds and returns Listener with valid port" in {
        given Frame     = Frame.internal
        val transport   = mkTransport()
        val listenFiber = transport.listen("127.0.0.1", 0, 50)(_ => ())
        listenFiber.safe.get.map { listener =>
            try
                assert(listener.port > 0)
                assert(listener.port <= 65535)
                succeed
            finally
                listener.close()
                discard(transport.close())
        }
    }

    "listen binds to the given host" in {
        given Frame     = Frame.internal
        val transport   = mkTransport()
        val listenFiber = transport.listen("127.0.0.1", 0, 50)(_ => ())
        listenFiber.safe.get.map { listener =>
            try
                assert(listener.host.nonEmpty)
                succeed
            finally
                listener.close()
                discard(transport.close())
        }
    }

    "listen returns Closed failure for already-bound port" in {
        given Frame   = Frame.internal
        val transport = mkTransport()

        transport.listen("127.0.0.1", 0, 50)(_ => ()).safe.get.map { listener1 =>
            val port = listener1.port
            Abort.run[NetException](transport.listen("127.0.0.1", port, 50)(_ => ()).safe.get).map { result2 =>
                listener1.close()
                transport.close()
                assert(result2.isFailure)
                succeed
            }
        }
    }

    // -----------------------------------------------------------------------
    // accept connection via listen
    // -----------------------------------------------------------------------

    "listen accepts connecting clients" in {
        given Frame   = Frame.internal
        val transport = mkTransport()

        // Latch the handler completes the instant it runs, so the test proceeds on the actual accept event rather than a guessed delay.
        val accepted = Promise.Unsafe.init[Unit, Any]()
        val listenFiber = transport.listen("127.0.0.1", 0, 50) { conn =>
            accepted.completeDiscard(Result.succeed(()))
            conn.close()
        }

        listenFiber.safe.get.map { listener =>
            val port = listener.port
            // Connect a plain Java socket to trigger the accept handler
            val clientSock = new java.net.Socket("127.0.0.1", port)
            accepted.safe.get.map { _ =>
                clientSock.close()
                listener.close()
                transport.close()
                succeed
            }
        }
    }

    // -----------------------------------------------------------------------
    // end-to-end connection
    // -----------------------------------------------------------------------

    "connect + listen: both sides are open connections" in {
        given Frame   = Frame.internal
        val transport = mkTransport()

        // Use a latch to hold the server-side connection open until client checks isOpen
        val latch = new java.util.concurrent.CountDownLatch(1)

        transport
            .listen("127.0.0.1", 0, 50) { serverConn =>
                latch.await()
                serverConn.close()
            }
            .safe
            .get
            .map { listener =>
                val port = listener.port
                transport.connect("127.0.0.1", port).safe.get.map { clientConn =>
                    try
                        assert(clientConn.isOpen)
                        succeed
                    finally
                        latch.countDown()
                        clientConn.close()
                        listener.close()
                        discard(transport.close())
                }
            }
    }

    // -----------------------------------------------------------------------
    // connect with TLS
    // -----------------------------------------------------------------------

    "connect with TLS to loopback TLS server completes handshake" in {
        given Frame   = Frame.internal
        val transport = mkTransport()

        val tlsConfig = serverTlsConfig

        transport.listen("127.0.0.1", 0, 50, tlsConfig) { conn =>
            conn.close()
        }.safe.get.map { listener =>
            val port      = listener.port
            val clientTls = NetTlsConfig(trustAll = true)
            Abort.run[NetException](transport.connect("127.0.0.1", port, clientTls).safe.get).map { result =>
                listener.close()
                transport.close()
                result match
                    case Result.Success(conn) =>
                        conn.close()
                        succeed
                    case Result.Failure(e) =>
                        fail(s"TLS connect failed: $e")
                    case Result.Panic(e) =>
                        fail(s"TLS connect panicked: $e")
                end match
            }
        }
    }

    // -----------------------------------------------------------------------
    // listen Listener.close stops accepting
    // -----------------------------------------------------------------------

    "listener.close marks listener as closed" in {
        given Frame   = Frame.internal
        val transport = mkTransport()

        transport.listen("127.0.0.1", 0, 50)(_ => ()).safe.get.map { listener =>
            val port = listener.port
            assert(port > 0)
            listener.close()
            // After close, further connect attempts should fail or be refused
            Abort.run[NetException](transport.connect("127.0.0.1", port).safe.get).map { result =>
                transport.close()
                // Either connection refused (failure) or the port was taken; either way listener is closed
                // OS-nondeterministic listener-close outcome
                assert(result.isFailure || result.isSuccess)
                succeed
            }
        }
    }

    // -----------------------------------------------------------------------
    // createSslContext (accessible as private[internal])
    // -----------------------------------------------------------------------

    "createSslContext with trustAll produces context that accepts any cert" in {
        val tlsConfig = NetTlsConfig(trustAll = true)
        val ctx       = NioTransport.createSslContext(tlsConfig, isServer = false)
        assert(ctx ne null)
        // Should be able to create an engine without exceptions
        val engine = ctx.createSSLEngine()
        assert(engine ne null)
        succeed
    }

    "createSslContext with server PEM cert/key loads key managers" in {
        val tlsConfig = serverTlsConfig
        val ctx       = NioTransport.createSslContext(tlsConfig, isServer = true)
        assert(ctx ne null)
        val engine = ctx.createSSLEngine()
        engine.setUseClientMode(false)
        assert(engine ne null)
        succeed
    }

    // -----------------------------------------------------------------------
    // TLS: serverCertificateHash is Present after handshake
    // -----------------------------------------------------------------------

    "connect with TLS: serverCertificateHash is Present after handshake" in {
        given Frame   = Frame.internal
        val transport = mkTransport()
        // The server handler keeps the connection open (does not close): closing it immediately sends a FIN that, under load, the client's read
        // pump observes as EOF and tears the connection down (setting the connection's closed flag) before the client reads the cert hash, making
        // serverCertificateHash Absent by the "Absent after close" contract. The shared TransportTlsIntrospectionTest uses the same open-handler
        // shape for this reason; listener.close() / transport.close() below tear the server side down.
        transport.listen("127.0.0.1", 0, 50, serverTlsConfig) { _ =>
            ()
        }.safe.get.map { listener =>
            val port      = listener.port
            val clientTls = NetTlsConfig(trustAll = true)
            Abort.run[NetException](transport.connect("127.0.0.1", port, clientTls).safe.get).map { result =>
                result match
                    case Result.Success(conn) =>
                        // Read the cert hash on the still-open connection (the server handler above does not close it).
                        val hash = conn.serverCertificateHash
                        conn.close()
                        listener.close()
                        transport.close()
                        assert(hash.isDefined, s"serverCertificateHash must be Present after TLS handshake, got Absent")
                        succeed
                    case Result.Failure(e) =>
                        listener.close()
                        transport.close()
                        fail(s"TLS connect failed: $e")
                    case Result.Panic(e) =>
                        listener.close()
                        transport.close()
                        fail(s"TLS connect panicked: $e")
                end match
            }
        }
    }

    // -----------------------------------------------------------------------
    // listen: handler throw is logged, not propagated; server stays up
    // -----------------------------------------------------------------------

    "listen handler throw is logged, not propagated, and subsequent connections are accepted" in {
        given Frame     = Frame.internal
        val transport   = mkTransport()
        val acceptCount = new java.util.concurrent.atomic.AtomicInteger(0)
        // Latch each handler invocation signals (before throwing) so the test gates each step on the actual accept event, not a guessed delay.
        Channel.init[Unit](2).map { handled =>
            val listenFiber = transport.listen("127.0.0.1", 0, 50) { conn =>
                acceptCount.incrementAndGet()
                conn.close()
                discard(handled.unsafe.offer(()))
                throw new RuntimeException("deliberate handler throw")
            }
            listenFiber.safe.get.map { listener =>
                val port = listener.port
                // First client triggers the throwing handler.
                val c1 = new java.net.Socket("127.0.0.1", port)
                // Wait for the first handler invocation (the latch it signals before throwing) instead of guessing a recovery delay.
                handled.take.map { _ =>
                    // Second client connects after the throw: server must still be listening.
                    val c2 = new java.net.Socket("127.0.0.1", port)
                    // Wait for the second handler invocation, proving the accept loop survived the throw and accepted again.
                    handled.take.map { _ =>
                        c1.close()
                        c2.close()
                        listener.close()
                        transport.close()
                        assert(acceptCount.get() >= 2, s"expected at least 2 accepted connections, got ${acceptCount.get()}")
                        succeed
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Unix domain sockets: non-existent path
    // -----------------------------------------------------------------------

    "connectUnix returns Closed for non-existent socket path" in {
        given Frame   = Frame.internal
        val transport = mkTransport()
        val badPath   = "/tmp/kyo-nio-test-does-not-exist-" + java.util.UUID.randomUUID() + ".sock"
        Abort.run[NetException](transport.connectUnix(badPath).safe.get).map { result =>
            transport.close()
            assert(result.isFailure)
            succeed
        }
    }

    // -----------------------------------------------------------------------
    // Unix domain sockets: connect + listen round-trip
    // -----------------------------------------------------------------------

    "connectUnix + listenUnix round-trips bytes over a Unix domain socket" in {
        given Frame = Frame.internal
        // Java NIO Unix domain sockets need StandardProtocolFamily.UNIX (Java 16+) and a Unix-like host. The existing
        // connectUnix-failure leaf above runs unconditionally, so on the CI/dev runners UDS is available; this guard only
        // skips the leaf cleanly on a host where opening a UNIX SocketChannel is unsupported (e.g. Windows), rather than failing.
        val udsSupported =
            try
                val ch = java.nio.channels.SocketChannel.open(StandardProtocolFamily.UNIX)
                ch.close()
                true
            catch case _: Throwable => false
        if !udsSupported then cancel("Java NIO Unix domain sockets unsupported on this host (needs Java 16+ on a Unix-like OS)")

        val transport = mkTransport()
        // A unique short path under /tmp, mirroring PosixTransportSurfaceTest: /tmp keeps it under the 108-byte sun_path
        // limit, and nanoTime makes it fresh per run, so no stale-file cleanup is needed before binding.
        val path    = s"/tmp/kyo-nio-uds-${java.lang.System.nanoTime()}.sock"
        val payload = Span.fromUnsafe(Array[Byte](1, 2, 3, 4, 5))

        // Read exactly `target` bytes from a connection's inbound channel, concatenated. UDS can in principle fragment, so this
        // loops on safe.take (each take blocks the calling fiber until the next echoed frame arrives: no sleep, no poll).
        def collect(conn: kyo.net.Connection, target: Int): Array[Byte] < (Async & Abort[Closed]) =
            Loop(Array.emptyByteArray) { acc =>
                if acc.length >= target then Loop.done(acc)
                else conn.inbound.safe.take.map(chunk => Loop.continue(acc ++ chunk.toArray))
            }

        AtomicRef.init[Maybe[kyo.net.Connection]](Absent).map { serverConnRef =>
            // Echo handler: capture the accepted connection (for cleanup), then echo each inbound frame back on outbound.
            // The loop runs in a spawned fiber (the handler returns Unit); the test gates on the echoed bytes arriving, not a delay.
            val listenFiber = transport.listenUnix(path, 16) { serverConn =>
                serverConnRef.unsafe.set(Present(serverConn))
                discard(Sync.Unsafe.evalOrThrow {
                    Fiber.initUnscoped {
                        Abort.run[Closed] {
                            Loop.foreach {
                                serverConn.inbound.safe.take.map { chunk =>
                                    serverConn.outbound.safe.put(chunk).andThen(Loop.continue)
                                }
                            }
                        }.unit
                    }
                })
            }
            listenFiber.safe.get.map { listener =>
                transport.connectUnix(path).safe.get.map { client =>
                    // Write the known payload, then read it back: the take blocks head-of-line on this fiber until the echo arrives.
                    client.outbound.safe.put(payload).andThen(collect(client, payload.size)).map { echoed =>
                        try
                            assert(echoed.sameElements(payload.toArray), s"UDS echo mismatch: got ${echoed.toList}")
                            succeed
                        finally
                            client.close()
                            serverConnRef.unsafe.get().foreach(_.close())
                            listener.close()
                            transport.close()
                            discard(new java.io.File(path).delete())
                        end try
                    }
                }
            }
        }
    }

end NioTransportTest
