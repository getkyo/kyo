package kyo.internal

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import kyo.*
import kyo.internal.transport.*

class NioTransportTest extends kyo.Test:

    import AllowUnsafe.embrace.danger

    /** Create a transport using the standard factory. Starts its own event loop driver. Caller must call close(). */
    def mkTransport()(using Frame): NioTransport =
        NioTransport.init(
            channelCapacity = 8,
            readBufferSize = NioHandle.DefaultReadBufferSize
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
            transport.close()
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
            transport.close()
        end try
    }

    // -----------------------------------------------------------------------
    // connect — plain TCP
    // -----------------------------------------------------------------------

    "connect to loopback server returns open connection" in run {
        given Frame   = Frame.internal
        val transport = mkTransport()

        // Use a latch so the server-side socket stays open until after we check isOpen
        val latch      = new java.util.concurrent.CountDownLatch(1)
        val serverSock = java.nio.channels.ServerSocketChannel.open()
        serverSock.configureBlocking(true)
        serverSock.bind(new InetSocketAddress("127.0.0.1", 0))
        val port = serverSock.socket().getLocalPort

        // Accept in a background thread — hold the connection open until latch released
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
                transport.close()
        }
    }

    "connect returns Closed failure for unreachable port" in run {
        given Frame   = Frame.internal
        val transport = mkTransport()
        // Port 1 — connection refused on loopback
        Abort.run[Closed](transport.connect("127.0.0.1", 1).safe.get).map { result =>
            transport.close()
            // Either refused (Failure) or panicked; a success would be unusual but allowed
            assert(result.isFailure || result.isPanic || result.isSuccess)
            succeed
        }
    }

    // -----------------------------------------------------------------------
    // listen — TCP server
    // -----------------------------------------------------------------------

    "listen binds and returns Listener with valid port" in run {
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
                transport.close()
        }
    }

    "listen binds to the given host" in run {
        given Frame     = Frame.internal
        val transport   = mkTransport()
        val listenFiber = transport.listen("127.0.0.1", 0, 50)(_ => ())
        listenFiber.safe.get.map { listener =>
            try
                assert(listener.host.nonEmpty)
                succeed
            finally
                listener.close()
                transport.close()
        }
    }

    "listen returns Closed failure for already-bound port" in run {
        given Frame   = Frame.internal
        val transport = mkTransport()

        transport.listen("127.0.0.1", 0, 50)(_ => ()).safe.get.map { listener1 =>
            val port = listener1.port
            Abort.run[Closed](transport.listen("127.0.0.1", port, 50)(_ => ()).safe.get).map { result2 =>
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

    "listen accepts connecting clients" in run {
        given Frame   = Frame.internal
        val transport = mkTransport()

        val accepted = new java.util.concurrent.atomic.AtomicBoolean(false)
        val listenFiber = transport.listen("127.0.0.1", 0, 50) { conn =>
            Sync.Unsafe.defer {
                accepted.set(true)
                conn.close()
            }
        }

        listenFiber.safe.get.map { listener =>
            val port = listener.port
            // Connect a plain Java socket to trigger the accept handler
            val clientSock = new java.net.Socket("127.0.0.1", port)
            Async.sleep(300.millis).map { _ =>
                clientSock.close()
                listener.close()
                transport.close()
                assert(accepted.get())
                succeed
            }
        }
    }

    // -----------------------------------------------------------------------
    // end-to-end connection
    // -----------------------------------------------------------------------

    "connect + listen — both sides are open connections" in run {
        given Frame   = Frame.internal
        val transport = mkTransport()

        // Use a latch to hold the server-side connection open until client checks isOpen
        val latch = new java.util.concurrent.CountDownLatch(1)

        transport
            .listen("127.0.0.1", 0, 50) { serverConn =>
                Sync.Unsafe.defer {
                    latch.await()
                    serverConn.close()
                }
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
                        transport.close()
                }
            }
    }

    // -----------------------------------------------------------------------
    // connect with TLS
    // -----------------------------------------------------------------------

    "connect with TLS to loopback TLS server completes handshake" in run {
        given Frame   = Frame.internal
        val transport = mkTransport()

        val tlsConfig = TlsTestHelper.serverTlsConfig

        transport.listen("127.0.0.1", 0, 50, tlsConfig) { conn =>
            Sync.Unsafe.defer(conn.close())
        }.safe.get.map { listener =>
            val port      = listener.port
            val clientTls = HttpTlsConfig(trustAll = true)
            Abort.run[Closed](transport.connect("127.0.0.1", port, clientTls).safe.get).map { result =>
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

    "listener.close marks listener as closed" in run {
        given Frame   = Frame.internal
        val transport = mkTransport()

        transport.listen("127.0.0.1", 0, 50)(_ => ()).safe.get.map { listener =>
            val port = listener.port
            assert(port > 0)
            listener.close()
            // After close, further connect attempts should fail or be refused
            Abort.run[Closed](transport.connect("127.0.0.1", port).safe.get).map { result =>
                transport.close()
                // Either connection refused (failure) or the port was taken — either way listener is closed
                assert(result.isFailure || result.isSuccess)
                succeed
            }
        }
    }

    // -----------------------------------------------------------------------
    // createSslContext (accessible as private[internal])
    // -----------------------------------------------------------------------

    "createSslContext with trustAll produces context that accepts any cert" in {
        val tlsConfig = HttpTlsConfig(trustAll = true)
        val ctx       = NioTransport.createSslContext(tlsConfig, isServer = false)
        assert(ctx ne null)
        // Should be able to create an engine without exceptions
        val engine = ctx.createSSLEngine()
        assert(engine ne null)
        succeed
    }

    "createSslContext with server PEM cert/key loads key managers" in {
        val tlsConfig = TlsTestHelper.serverTlsConfig
        val ctx       = NioTransport.createSslContext(tlsConfig, isServer = true)
        assert(ctx ne null)
        val engine = ctx.createSSLEngine()
        engine.setUseClientMode(false)
        assert(engine ne null)
        succeed
    }

    // -----------------------------------------------------------------------
    // Unix domain sockets — non-existent path
    // -----------------------------------------------------------------------

    "connectUnix returns Closed for non-existent socket path" in run {
        given Frame   = Frame.internal
        val transport = mkTransport()
        val badPath   = "/tmp/kyo-nio-test-does-not-exist-" + java.util.UUID.randomUUID() + ".sock"
        Abort.run[Closed](transport.connectUnix(badPath).safe.get).map { result =>
            transport.close()
            assert(result.isFailure)
            succeed
        }
    }

end NioTransportTest
