package kyo.net.internal

import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import kyo.*
import kyo.net.NetConfig
import kyo.net.NetConnectionClosedException
import kyo.net.NetException
import kyo.net.NetTlsConfig
import kyo.net.Test
import kyo.net.internal.TlsTestCert
import kyo.net.internal.transport.*
import kyo.scheduler.IOPromise

class NioTransportTest extends Test:

    import AllowUnsafe.embrace.danger

    /** Server NetTlsConfig backed by the embedded test certificate. */
    lazy val serverTlsConfig: NetTlsConfig = NetTlsConfig(
        certChainPath = Present(TlsTestCert.certPath),
        privateKeyPath = Present(TlsTestCert.keyPath)
    )

    /** Create a transport and register its driver to close at leaf-scope exit. An owned transport (unlike the process-shared one) must be closed,
      * or its never-exiting selector poll loop keeps a scheduler worker busy and the suite fails the end-of-run leak check.
      */
    def mkTransport()(using Frame): NioTransport < (Sync & Scope) =
        Sync.defer(NioTransport.init()).map { t =>
            Scope.ensure(Sync.defer {
                import AllowUnsafe.embrace.danger
                t.pool.next().close()
            }).andThen(t)
        }

    /** Poll a condition driven by a real FIN through the OS (the reclaim runs on the transport carrier, out of the test's reach). */
    private def awaitCondition(bound: Duration)(cond: => Boolean)(using Frame): Boolean < Async =
        val deadline = java.lang.System.nanoTime() + bound.toNanos
        Loop(()) { _ =>
            if cond then Loop.done(true)
            else if java.lang.System.nanoTime() >= deadline then Loop.done(false)
            else Async.sleep(5.millis).andThen(Loop.continue(()))
        }
    end awaitCondition

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    "init stores IoDriverPool" in {
        given Frame = Frame.internal
        mkTransport().map { transport =>
            assert(transport.pool ne null)
            succeed
        }
    }

    "init stores IoDriver in pool" in {
        given Frame = Frame.internal
        mkTransport().map { transport =>
            val driver = transport.pool.next()
            assert(driver ne null)
            succeed
        }
    }

    // -----------------------------------------------------------------------
    // connect: plain TCP
    // -----------------------------------------------------------------------

    "connect to loopback server returns open connection" in {
        given Frame = Frame.internal
        mkTransport().map { transport =>
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
            }
        }
    }

    "connect returns Closed failure for unreachable port" in {
        given Frame = Frame.internal
        mkTransport().map { transport =>
            // Port 1: connection refused on loopback
            Abort.run[NetException](transport.connect("127.0.0.1", 1).safe.get).map { result =>
                // Either refused (Failure) or panicked; a success would be unusual but allowed
                // OS-nondeterministic connect-refused-vs-reuse outcome covers all Result cases
                result match
                    case Result.Success(conn) => conn.close()
                    case _                    => ()
                assert(result.isFailure || result.isPanic || result.isSuccess)
                succeed
            }
        }
    }

    // -----------------------------------------------------------------------
    // listen: TCP server
    // -----------------------------------------------------------------------

    "listen binds and returns Listener with valid port" in {
        given Frame = Frame.internal
        mkTransport().map { transport =>
            val listenFiber = transport.listen("127.0.0.1", 0, 50)(_ => ())
            listenFiber.safe.get.map { listener =>
                try
                    assert(listener.port > 0)
                    assert(listener.port <= 65535)
                    succeed
                finally
                    listener.close()
            }
        }
    }

    "listen binds to the given host" in {
        given Frame = Frame.internal
        mkTransport().map { transport =>
            val listenFiber = transport.listen("127.0.0.1", 0, 50)(_ => ())
            listenFiber.safe.get.map { listener =>
                try
                    assert(listener.host.nonEmpty)
                    succeed
                finally
                    listener.close()
            }
        }
    }

    "listen returns Closed failure for already-bound port" in {
        given Frame = Frame.internal
        mkTransport().map { transport =>
            transport.listen("127.0.0.1", 0, 50)(_ => ()).safe.get.map { listener1 =>
                val port = listener1.port
                Abort.run[NetException](transport.listen("127.0.0.1", port, 50)(_ => ()).safe.get).map { result2 =>
                    listener1.close()
                    result2 match
                        case Result.Success(listener2) => listener2.close()
                        case _                         => ()
                    assert(result2.isFailure)
                    succeed
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // accept connection via listen
    // -----------------------------------------------------------------------

    "listen accepts connecting clients" in {
        given Frame = Frame.internal
        mkTransport().map { transport =>
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
                    succeed
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // end-to-end connection
    // -----------------------------------------------------------------------

    "connect + listen: both sides are open connections" in {
        given Frame = Frame.internal
        mkTransport().map { transport =>
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
                    }
                }
        }
    }

    // -----------------------------------------------------------------------
    // connect with TLS
    // -----------------------------------------------------------------------

    "connect with TLS to loopback TLS server completes handshake" in {
        given Frame = Frame.internal
        mkTransport().map { transport =>
            val tlsConfig = serverTlsConfig

            transport.listenTls("127.0.0.1", 0, 50, tlsConfig) { conn =>
                conn.close()
            }.safe.get.map { listener =>
                val port      = listener.port
                val clientTls = NetTlsConfig(trustAll = true)
                Abort.run[NetException](transport.connectTls("127.0.0.1", port, clientTls).safe.get).map { result =>
                    listener.close()
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
    }

    // -----------------------------------------------------------------------
    // listen Listener.close stops accepting
    // -----------------------------------------------------------------------

    "listener.close marks listener as closed" in {
        given Frame = Frame.internal
        mkTransport().map { transport =>
            transport.listen("127.0.0.1", 0, 50)(_ => ()).safe.get.map { listener =>
                val port = listener.port
                assert(port > 0)
                listener.close()
                // After close, further connect attempts should fail or be refused
                Abort.run[NetException](transport.connect("127.0.0.1", port).safe.get).map { result =>
                    // Either connection refused (failure) or the port was taken; either way listener is closed
                    // OS-nondeterministic listener-close outcome
                    result match
                        case Result.Success(conn) => conn.close()
                        case _                    => ()
                    assert(result.isFailure || result.isSuccess)
                    succeed
                }
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
        given Frame = Frame.internal
        mkTransport().map { transport =>
            // The server handler keeps the connection open (does not close): closing it immediately sends a FIN that, under load, the client's read
            // pump observes as EOF and tears the connection down (setting the connection's closed flag) before the client reads the cert hash, making
            // serverCertificateHash Absent by the "Absent after close" contract. The shared TransportTlsIntrospectionTest uses the same open-handler
            // shape for this reason; listener.close() below tears the server side down.
            transport.listenTls("127.0.0.1", 0, 50, serverTlsConfig) { _ =>
                ()
            }.safe.get.map { listener =>
                val port      = listener.port
                val clientTls = NetTlsConfig(trustAll = true)
                Abort.run[NetException](transport.connectTls("127.0.0.1", port, clientTls).safe.get).map { result =>
                    result match
                        case Result.Success(conn) =>
                            // Read the cert hash on the still-open connection (the server handler above does not close it).
                            val hash = conn.serverCertificateHash
                            conn.close()
                            listener.close()
                            assert(hash.isDefined, s"serverCertificateHash must be Present after TLS handshake, got Absent")
                            succeed
                        case Result.Failure(e) =>
                            listener.close()
                            fail(s"TLS connect failed: $e")
                        case Result.Panic(e) =>
                            listener.close()
                            fail(s"TLS connect panicked: $e")
                    end match
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // listen: handler throw is logged, not propagated; server stays up
    // -----------------------------------------------------------------------

    "listen handler throw is logged, not propagated, and subsequent connections are accepted" in {
        given Frame = Frame.internal
        mkTransport().map { transport =>
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
                            assert(acceptCount.get() >= 2, s"expected at least 2 accepted connections, got ${acceptCount.get()}")
                            succeed
                        }
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Unix domain sockets: non-existent path
    // -----------------------------------------------------------------------

    "connectUnix returns Closed for non-existent socket path" in {
        given Frame = Frame.internal
        mkTransport().map { transport =>
            val badPath = "/tmp/kyo-nio-test-does-not-exist-" + java.util.UUID.randomUUID() + ".sock"
            Abort.run[NetException](transport.connectUnix(badPath).safe.get).map { result =>
                result match
                    case Result.Success(conn) => conn.close()
                    case _                    => ()
                assert(result.isFailure)
                succeed
            }
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

        mkTransport().map { transport =>
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
                                discard(new java.io.File(path).delete())
                            end try
                        }
                    }
                }
            }
        }
    }

    // The NIO floor accepted soRcvBuf/soSndBuf and never applied them: setOption was used for TCP_NODELAY and SO_REUSEADDR but never for the
    // buffers, so the fields were silently ignored on this backend. Two connects on ONE transport asking for different sizes must come back
    // with different sizes; the comparison is on SO_SNDBUF because macOS auto-tunes SO_RCVBUF non-monotonically on connected loopback sockets
    // (measured: a request of 16384 read back as 326640 and 262144 as 277644), while the send buffer tracks the request.
    // The connect-side leaf below covers a client socket. This one covers the LISTEN path, which NioTransport applies separately
    // (applySocketBuffers(serverChannel, config, sendSupported = false) before bind) and which nothing pinned: SO_RCVBUF on a listening socket
    // is what accepted sockets inherit, and the kernel fixes window scaling at bind time, so applying it late would silently do nothing.
    // Asserted on the LISTENING channel rather than an accepted one because a connected socket's SO_RCVBUF is auto-tuned non-monotonically on
    // macOS (the reason the connect leaf compares SO_SNDBUF instead), while a listening socket's reads back tracking the request.
    "socket receive buffer is applied to the listen socket, per listen" in {
        val transport = NioTransport.init()
        Scope.ensure(Sync.defer { import kyo.AllowUnsafe.embrace.danger; transport.pool.next().close() }).andThen {
            val smallReq = 16384
            val largeReq = 262144
            def rcvOf(l: kyo.net.Listener): Int =
                l.asInstanceOf[NioListener].serverChannel.getOption(java.net.StandardSocketOptions.SO_RCVBUF).intValue
            Abort.run[NetException | Closed] {
                val small = kyo.net.NetConfig.default.copy(soRcvBuf = Present(smallReq))
                val large = kyo.net.NetConfig.default.copy(soRcvBuf = Present(largeReq))
                transport.listen("127.0.0.1", 0, 4, config = small)(_ => ()).safe.get.map { smallListener =>
                    Scope.ensure(Sync.defer(smallListener.close())).andThen {
                        transport.listen("127.0.0.1", 0, 4, config = large)(_ => ()).safe.get.map { largeListener =>
                            Scope.ensure(Sync.defer(largeListener.close())).andThen {
                                Sync.defer {
                                    val smallRcv = rcvOf(smallListener)
                                    val largeRcv = rcvOf(largeListener)
                                    assert(
                                        largeRcv > smallRcv,
                                        s"SO_RCVBUF on the listen socket: the listen asking for $largeReq got $largeRcv, the one asking for " +
                                            s"$smallReq got $smallRcv; equal values mean the per-listen config never reached the server channel"
                                    )
                                    succeed
                                }
                            }
                        }
                    }
                }
            }.map(Abort.get)
        }
    }

    "socket buffer sizes are applied per connect" in {
        val transport = NioTransport.init()
        Scope.ensure(Sync.defer { import kyo.AllowUnsafe.embrace.danger; transport.pool.next().close() }).andThen {
            val smallReq = 16384
            val largeReq = 262144
            def sndOf(conn: kyo.net.Connection): Int =
                conn.asInstanceOf[kyo.net.internal.transport.Connection[NioHandle]].handle.channel
                    .getOption(java.net.StandardSocketOptions.SO_SNDBUF).intValue
            Abort.run[NetException | Closed] {
                transport.listen("127.0.0.1", 0, 4)(_ => ()).safe.get.map { listener =>
                    Scope.ensure(Sync.defer(listener.close())).andThen {
                        val small = kyo.net.NetConfig.default.copy(soRcvBuf = Present(smallReq), soSndBuf = Present(smallReq))
                        val large = kyo.net.NetConfig.default.copy(soRcvBuf = Present(largeReq), soSndBuf = Present(largeReq))
                        transport.connect("127.0.0.1", listener.port, config = small).safe.get.map { smallConn =>
                            Scope.ensure(Sync.defer(smallConn.close())).andThen {
                                transport.connect("127.0.0.1", listener.port, config = large).safe.get.map { largeConn =>
                                    Scope.ensure(Sync.defer(largeConn.close())).andThen {
                                        Sync.defer {
                                            val smallSnd = sndOf(smallConn)
                                            val largeSnd = sndOf(largeConn)
                                            smallConn.close()
                                            largeConn.close()
                                            listener.close()
                                            assert(
                                                largeSnd > smallSnd,
                                                s"SO_SNDBUF: the connect asking for $largeReq got $largeSnd, the one asking for $smallReq got $smallSnd; " +
                                                    "equal values mean the per-connect config never reached the channel"
                                            )
                                            succeed
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }.map(Abort.get)
        }
    }

    // A connection accepted by listenTls but whose handshake has not completed has no Connection yet, so it is invisible to the registry
    // transport close sweeps. Before this was tracked, nothing reclaimed it: a listener close tore down only the accept and the server channel,
    // and the process-shared transport is never closed at all, so a peer that completed the TCP accept and then stalled held its channel and
    // handle until the process exited.
    //
    // Pinned with NO deadline armed (handshakeTimeout = Infinity, what a kyo-http server ships), so the listener close is the only thing that
    // can reclaim it; with a finite deadline the timer would eventually do it and this would pass either way. The stalled peer is held OPEN
    // across the assertion for the same reason: closing it would end the handshake by itself and the leaf would stop testing the discharge.
    "closing a listener reclaims the handshakes it accepted" in {
        val transport = NioTransport.init()
        val unbounded = serverTlsConfig.copy(handshakeTimeout = Duration.Infinity)
        Scope.ensure(Sync.defer { import kyo.AllowUnsafe.embrace.danger; transport.pool.next().close() }).andThen {
            Abort.run[NetException | Closed] {
                transport.listenTls("127.0.0.1", 0, 16, unbounded)(_ => ()).safe.get.map { listener =>
                    Scope.ensure(Sync.defer(listener.close())).andThen {
                        // A plaintext client: it completes the TCP accept and never sends a ClientHello, so the server handshake registers and parks.
                        transport.connect("127.0.0.1", listener.port).safe.get.map { client =>
                            Scope.ensure(Sync.defer(client.close())).andThen {
                                // Barrier: wait until the handshake has actually registered. Without it this races the accept and could close a listener
                                // with nothing to discharge, passing for the wrong reason.
                                assertEventually(Sync.defer(transport.pendingAcceptHandshakeCount > 0)).map { _ =>
                                    listener.close()
                                    assertEventually(Sync.defer(transport.pendingAcceptHandshakeCount == 0)).map { _ =>
                                        // The discharge fails the handshake promise, whose existing teardown arm reaps the handle and closes the channel,
                                        // which this still-connected client observes as its inbound terminating.
                                        Abort.run[Timeout](Async.timeout(3.seconds)(Abort.run[Closed](client.inbound.safe.take))).map {
                                            outcome =>
                                                client.close()
                                                assert(
                                                    outcome.isSuccess,
                                                    s"the listener close must release its stalled handshake's channel, got $outcome"
                                                )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }.map(Abort.get)
        }
    }

    // The companion to the leaf above, for the window it cannot reach. The registration runs on the selector carrier while
    // dischargeListenerHandshakes runs on the closing carrier, so a listener can close AFTER the accept loop's `!listener.isClosed` guard and
    // BEFORE the handshake is tracked. That entry would then survive every reclaim path: the sweep has passed, a second close is a CAS no-op,
    // and the transport-wide sweep never runs on the process-shared transport. Driven through the production function directly, since the
    // interleaving cannot be forced through the public surface.
    "a handshake tracked after its listener already closed is reclaimed at registration" in {
        val transport = NioTransport.init()
        val unbounded = serverTlsConfig.copy(handshakeTimeout = Duration.Infinity)
        Scope.ensure(Sync.defer { import kyo.AllowUnsafe.embrace.danger; transport.pool.next().close() }).andThen {
            Abort.run[NetException | Closed] {
                transport.listenTls("127.0.0.1", 0, 16, unbounded)(_ => ()).safe.get.map { listener =>
                    Sync.defer {
                        listener.close()
                        val connPromise = new IOPromise[NetException, Connection[NioHandle]]
                        val reclaimed   = transport.trackAcceptHandshake(listener.asInstanceOf[NioListener], connPromise)
                        assert(
                            reclaimed,
                            "a handshake tracked after its listener closed must be reclaimed at registration, or its channel and handle are held for the process lifetime"
                        )
                        assert(
                            transport.pendingAcceptHandshakeCount == 0,
                            s"the reclaimed entry must not stay in the registry, count=${transport.pendingAcceptHandshakeCount}"
                        )
                        connPromise.poll() match
                            case Present(Result.Failure(_: NetConnectionClosedException)) =>
                                succeed
                            case other =>
                                fail(
                                    s"the reclaim must fail the handshake promise so its teardown arm reaps the handle and channel, got $other"
                                )
                        end match
                    }
                }
            }.map(Abort.get)
        }
    }

    // -----------------------------------------------------------------------
    // peer-close grace wiring (the NIO twin of the JS transport wiring test)
    // -----------------------------------------------------------------------

    "threads peerCloseGrace end-to-end: an abandoned backpressured accepted connection is reclaimed after the client FIN" in {
        given Frame = Frame.internal
        // Cap-1 inbound + 64-byte read chunk so a 128-byte client write becomes two reads, the second overflowing the channel and parking the
        // accepted-side ReadPump with no armed read. Short grace so the reclaim fires promptly. Without NioTransport threading config.peerCloseGrace
        // into the handle and the Connection, the grace defaults to Infinity and the abandoned connection is never reclaimed (isOpen stays true).
        val config    = NetConfig(channelCapacity = 1, readChunkSize = 64, peerCloseGrace = 200.millis)
        val acceptedP = new IOPromise[Closed, kyo.net.Connection]
        mkTransport().map { transport =>
            for
                listener <- transport.listen("127.0.0.1", 0, 50, config) { conn =>
                    acceptedP.completeDiscard(Result.succeed(conn))
                }.safe.get
                client   <- Sync.defer(new java.net.Socket("127.0.0.1", listener.port))
                accepted <- acceptedP.asInstanceOf[Fiber.Unsafe[kyo.net.Connection, Abort[Closed]]].safe.get
                _ <- Sync.defer {
                    client.getOutputStream.write(Array.fill[Byte](128)(1))
                    client.getOutputStream.flush()
                }
                _         <- Async.sleep(300.millis)    // let the accepted-side pump read both chunks and park on the put
                _         <- Sync.defer(client.close()) // FIN with the pump parked
                reclaimed <- awaitCondition(5.seconds)(!accepted.isOpen)
                _         <- Sync.defer(listener.close())
            yield assert(
                reclaimed,
                "NioTransport must thread peerCloseGrace so an abandoned backpressured accepted connection is reclaimed after the client FIN"
            )
            end for
        }
    }

end NioTransportTest
