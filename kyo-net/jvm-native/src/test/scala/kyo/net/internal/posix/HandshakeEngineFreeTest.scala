package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.NetTlsConfig
import kyo.net.Test
import kyo.net.TransportConfig
import kyo.net.internal.tls.TlsRealEngines
import kyo.net.internal.tls.TlsTestCert
import kyo.net.internal.transport.IoDriver

// This suite lives in jvm-native/src/test because PosixTransport's TLS handshake runs on JVM-posix and Native; JS uses the Node transport.

/** Regression guard for the resource teardown on the TLS handshake paths of [[PosixTransport]]: every failure path (connect-time, accept-time,
  * STARTTLS-upgrade) and the success-close path must release the connection's file descriptors, with no per-handshake accumulation, and must not
  * double-free the native engine.
  *
  * The failure paths build a handshake engine and, on failure, free it, close the raw fd, and tear down the handle (`completeOrTls`,
  * `handleAccepted`, `upgradeToTls`/`freeUpgradeResources`). The STARTTLS upgrade additionally detaches the plaintext connection, keeping the fd
  * open, so its failure path must close that detached fd explicitly: before the fix it leaked one fd per failed upgrade.
  *
  * The guard observes accumulation, not a per-call internal counter: it drives many real failing (or succeeding) handshakes and asserts the
  * process's open-fd count returns to baseline. The fd count is read process-specifically by probing the lowest free descriptor (`socket()` then
  * `close()` hands back the next fd the kernel would allocate, which equals the count of currently-open low descriptors). A per-iteration fd leak
  * makes that probe climb by the iteration count; a correct teardown keeps it flat. The STARTTLS leaf additionally `fstat`s the detached fd of a
  * single upgrade to pin that the exact descriptor is closed. Running the soak over real BoringSSL/OpenSSL engines also exercises the native free
  * on every path: a double-free would crash the process, so a green soak is the no-double-free guard.
  *
  * Gate: `assumePollerReady()` (real PollerIoDriver) and `TlsRealEngines.assumeTlsReady()` (a real BoringSSL/OpenSSL provider).
  */
class HandshakeEngineFreeTest extends Test:

    import AllowUnsafe.embrace.danger
    import NetTlsConfig.Version.*

    private val transportConfig = TransportConfig.default

    // A soak of real handshakes churns descriptors per iteration; the driver and listener hold a small fixed set. After the soak's async closes
    // drain (see settledOwnCount), the listener-port socket count returns to baseline (descriptors reused). This slack absorbs at most a couple of
    // benign in-flight connections still tearing down at the instant of the count; a per-iteration leak climbs to ~iterations (>= k), far above it.
    private val fdSlack = 2

    private def assumePollerReady(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PosixTransport TLS handshake tests need epoll (Linux) or kqueue (macOS/BSD)")

    private def serverTls(min: NetTlsConfig.Version, max: NetTlsConfig.Version): NetTlsConfig =
        NetTlsConfig(
            certChainPath = Present(TlsTestCert.certPath),
            privateKeyPath = Present(TlsTestCert.keyPath),
            minVersion = min,
            maxVersion = max
        )

    private def clientTls(min: NetTlsConfig.Version, max: NetTlsConfig.Version): NetTlsConfig =
        NetTlsConfig(trustAll = true, minVersion = min, maxVersion = max)

    // The high fd ceiling the open-fd scan walks. The process never holds anywhere near this many low descriptors in these suites; it bounds the
    // scan without missing any connection fd (loopback connections are allocated low).
    private val fdScanCeiling = 4096

    /** Count the OPEN descriptors that belong to a connection with `port` at one end (an accept-side server socket has `local == port`; a
      * connect-side client socket has `peer == port`). This counts only THIS test's sockets, never a foreign fd, because no other connection in the
      * process shares this listener's port.
      *
      * The earlier version of this check probed the process-wide lowest-free descriptor (allocate a socket, read its number, close it). That number
      * equals the count of ALL open low descriptors in the process, which is only a valid leak proxy in a single-threaded process. The module runs
      * its suites at `parallelism 4` (and the campaign command raises async concurrency to 4 on top), so three other suites open and close their own
      * descriptors throughout this soak; the process-wide probe then rose by THEIR fds, not a leak here, and the assertion flaked (a captured run
      * showed the probe climb from 118 to 138 while this test's own socket count held at 1, the listener, with all 19 of the rise foreign). Counting
      * only the port-attributed sockets makes the check immune to that cross-suite contamination while still climbing under a genuine per-iteration
      * leak (a leaked connect or accept fd keeps `port` at one of its ends).
      */
    private def portOf(buf: Buffer[Byte])(using AllowUnsafe): Int = ((buf.get(2) & 0xff) << 8) | (buf.get(3) & 0xff)
    private def countSocketsOnPort(sockets: SocketBindings, port: Int)(using AllowUnsafe): Int =
        var count = 0
        var fd    = 3
        while fd < fdScanCeiling do
            val st = Buffer.alloc[Byte](PosixConstants.statSize)
            val live =
                try sockets.fstat(fd, st).value == 0
                finally st.close()
            if live then
                val ln = Buffer.alloc[Byte](SockAddr.inet6Size)
                val ll = Buffer.alloc[Int](1); ll.set(0, SockAddr.inet6Size)
                val pn = Buffer.alloc[Byte](SockAddr.inet6Size)
                val pl = Buffer.alloc[Int](1); pl.set(0, SockAddr.inet6Size)
                try
                    val local = if sockets.getsockname(fd, ln, ll).value == 0 then portOf(ln) else -1
                    val peer  = if sockets.getpeername(fd, pn, pl).value == 0 then portOf(pn) else -1
                    if local == port || peer == port then count += 1
                finally
                    ln.close(); ll.close(); pn.close(); pl.close()
                end try
            end if
            fd += 1
        end while
        count
    end countSocketsOnPort

    /** Re-count the port-attributed sockets repeatedly, returning the lowest observation, stopping early once it settles to `base`. A failed
      * handshake routes its fd close through the driver asynchronously, so the last connection's descriptor can still be open at the instant the soak
      * loop returns; the short retries let those async closes drain. A real per-iteration leak never drains back to `base`, so the minimum stays high.
      */
    private def settledOwnCount(sockets: SocketBindings, port: Int, base: Int)(using Frame): Int < Async =
        Loop(0, Int.MaxValue) { (i, best) =>
            Sync.defer(countSocketsOnPort(sockets, port)).map { p =>
                val b = math.min(best, p)
                if b <= base || i >= 12 then Loop.done(b)
                else Async.sleep(40.millis).andThen(Loop.continue(i + 1, b))
            }
        }
    end settledOwnCount

    /** Build a transport over a fresh real poller driver, run `body`, then close the transport and the driver. */
    private def withTransport[A](body: PosixTransport => A < (Async & Abort[Closed] & Scope))(using
        Frame
    ): A < (Async & Abort[Closed] & Scope) =
        val driver    = PollerIoDriver.init(transportConfig)
        val transport = TestTransports.forTesting(transportConfig, driver, Ffi.load[SocketBindings], backendIsEpoll = false)
        discard(driver.start())
        Abort.run[Closed](body(transport)).map { result =>
            Sync.defer(transport.close()).andThen(Sync.defer(driver.close())).andThen(Abort.get(result))
        }
    end withTransport

    private def soak(k: Int)(iter: Int => Any < (Async & Abort[Closed] & Scope))(using Frame): Unit < (Async & Abort[Closed] & Scope) =
        Loop(0) { i =>
            if i >= k then Loop.done(())
            else iter(i).andThen(Loop.continue(i + 1))
        }

    "PosixTransport TLS handshake teardown releases descriptors" - {

        "failing connect/accept handshakes leak no descriptors over a soak" in {
            assumePollerReady()
            TlsRealEngines.assumeTlsReady()
            val sockets = Ffi.load[SocketBindings]
            val k       = 64
            withTransport { transport =>
                // The server accepts only TLS 1.3; each client offers only TLS 1.2, so every accept handshake fails (server-side engine + fd
                // teardown) and every connect fails after receiving the server's alert (client-side engine + fd teardown). Both descriptors per
                // iteration must be released.
                transport.listen("127.0.0.1", 0, 16, serverTls(TLS13, TLS13)) { _ => () }.safe.get.map { listener =>
                    // Baseline after the listener + driver are up and one warmup failure has settled any lazy allocation. The count is of sockets
                    // with this listener's port at one end (accept-side local, connect-side peer), so it ignores other suites' descriptors.
                    Abort.run[Closed](transport.connect("127.0.0.1", listener.port, clientTls(TLS12, TLS12)).safe.get).andThen {
                        Sync.defer(countSocketsOnPort(sockets, listener.port)).map { base =>
                            soak(k) { _ =>
                                Abort.run[Closed](transport.connect("127.0.0.1", listener.port, clientTls(TLS12, TLS12)).safe.get).map {
                                    o =>
                                        assert(o.isFailure, s"expected the version-mismatch handshake to fail, got $o")
                                }
                            }.andThen {
                                settledOwnCount(sockets, listener.port, base).map { after =>
                                    assert(
                                        after - base <= fdSlack,
                                        s"failing handshakes leaked descriptors: listener-port socket count rose from $base to $after over $k iterations"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        "failing STARTTLS upgrades release the detached fd over a soak" in {
            assumePollerReady()
            TlsRealEngines.assumeTlsReady()
            val sockets = Ffi.load[SocketBindings]
            val shim    = Ffi.load[PosixShimBindings]
            val k       = 32
            withTransport { transport =>
                // One upgrade up front, asserting the exact detached fd is closed (fstat < 0): the peer end is closed first, so the upgrade
                // handshake reads EOF and fails, and the failure path must close the detached plaintext fd it kept open.
                loopbackPair().map { case (clientFd, peerFd) =>
                    closeRaw(shim, peerFd)
                    val handle    = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                    val plaintext = transport.openWith(handle, transportDriver(transport))
                    plaintext.start()
                    Abort.run[Closed](transport.upgradeToTls(
                        plaintext,
                        clientTls(TLS12, TLS13),
                        transportConfig.channelCapacity
                    ).safe.get).map {
                        o =>
                            assert(o.isFailure, s"expected the EOF-during-handshake upgrade to fail, got $o")
                            // The failure path closes the detached plaintext fd in its teardown continuation, which runs a scheduler turn after
                            // upgradeToTls completes Failure, not synchronously with it. Await the close (fstat returns < 0, EBADF, on the closed
                            // fd); a genuine fd leak never satisfies it and surfaces as the per-test timeout.
                            assertEventually(Sync.defer {
                                val stat = Buffer.alloc[Byte](PosixConstants.statSize)
                                try sockets.fstat(handle.readFd, stat).value < 0
                                finally stat.close()
                            })
                    }.andThen {
                        // Track the exact detached plaintext fd each iteration creates, then assert every one is eventually closed. This is a
                        // direct per-fd check (the same fstat<0 technique the single upgrade above uses), immune to the cross-suite fd-table
                        // contamination a process-wide fd probe suffers under `parallelism 4`: a leaked detached fd stays fstat-able and fails it.
                        val createdFds = new java.util.concurrent.ConcurrentLinkedQueue[Int]()
                        soak(k) { _ =>
                            loopbackPair().map { case (cFd, pFd) =>
                                closeRaw(shim, pFd)
                                discard(createdFds.add(cFd))
                                val h  = PosixHandle.socket(cFd, PosixHandle.DefaultReadBufferSize, Absent)
                                val pc = transport.openWith(h, transportDriver(transport))
                                pc.start()
                                Abort.run[Closed](transport.upgradeToTls(
                                    pc,
                                    clientTls(TLS12, TLS13),
                                    transportConfig.channelCapacity
                                ).safe.get).map {
                                    o => assert(o.isFailure, s"expected the upgrade to fail on EOF, got $o")
                                }
                            }
                        }.andThen {
                            // Every detached plaintext fd the soak created must be closed (fstat < 0). A genuine per-iteration leak leaves one open
                            // and this never settles, surfacing as the per-test timeout. The fd numbers are this test's own, so a foreign reuse of a
                            // closed number cannot make a leaked fd look closed: the assertion is on the SET of fds we opened, not a process-wide count.
                            assertEventually(Sync.defer {
                                val stat = Buffer.alloc[Byte](PosixConstants.statSize)
                                try
                                    var allClosed = true
                                    val it        = createdFds.iterator()
                                    while it.hasNext do
                                        if sockets.fstat(it.next(), stat).value >= 0 then allClosed = false
                                    allClosed
                                finally stat.close()
                                end try
                            })
                        }
                    }
                }
            }
        }

        "successful handshakes release all descriptors on close over a soak" in {
            assumePollerReady()
            TlsRealEngines.assumeTlsReady()
            val sockets = Ffi.load[SocketBindings]
            val shim    = Ffi.load[PosixShimBindings]
            val k       = 32
            withTransport { transport =>
                transport.listen("127.0.0.1", 0, 16, serverTls(TLS12, TLS13)) { serverConn =>
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
                }.safe.get.map { listener =>
                    def roundTrip(): Unit < (Async & Abort[Closed] & Scope) =
                        transport.connect("127.0.0.1", listener.port, clientTls(TLS12, TLS13)).safe.get.map { client =>
                            val msg = "soak-roundtrip".getBytes("UTF-8")
                            client.outbound.safe.put(Span.fromUnsafe(msg)).andThen {
                                Loop(Array.emptyByteArray) { acc =>
                                    if acc.length >= msg.length then Loop.done(acc)
                                    else client.inbound.safe.take.map(c => Loop.continue(acc ++ c.toArray))
                                }.map { echoed =>
                                    client.close()
                                    discard(assert(echoed.sameElements(msg), s"round-trip mismatch: '${new String(echoed, "UTF-8")}'"))
                                }
                            }
                        }
                    roundTrip().andThen {
                        Sync.defer(countSocketsOnPort(sockets, listener.port)).map { base =>
                            soak(k)(_ => roundTrip()).andThen {
                                settledOwnCount(sockets, listener.port, base).map { after =>
                                    assert(
                                        after - base <= fdSlack,
                                        s"successful handshakes leaked descriptors on close: listener-port socket count rose from $base to $after over $k iterations"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private def closeRaw(shim: PosixShimBindings, fd: Int)(using AllowUnsafe): Unit = discard(shim.kyo_posix_close(fd))

    /** The single driver backing `transport` (the pool wraps exactly one). The STARTTLS leaf opens its plaintext connection over it directly. */
    private def transportDriver(transport: PosixTransport)(using Frame): IoDriver[PosixHandle] = transport.pool.next()

    /** Build a connected loopback socket pair (both non-blocking, as the transport's readiness model requires); returns (clientFd, peerFd). */
    private def loopbackPair()(using Frame, kyo.test.AssertScope): (Int, Int) < Async =
        val sock   = Ffi.load[SocketBindings]
        val server = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value.toInt
        val (a, l) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", 0).getOrElse(fail("encode failed"))
        Sync.ensure(Sync.defer(a.close())) {
            assert(sock.bind(server, a, l).value == 0)
            assert(sock.listen(server, 4).value == 0)
            val out = Buffer.alloc[Byte](SockAddr.inet4Size)
            val ol  = Buffer.alloc[Int](1)
            ol.set(0, SockAddr.inet4Size)
            val port =
                try
                    assert(sock.getsockname(server, out, ol).value == 0)
                    ((out.get(2) & 0xff) << 8) | (out.get(3) & 0xff)
                finally
                    out.close()
                    ol.close()
            val client   = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value.toInt
            val (ca, cl) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(fail("encode failed"))
            val connected =
                Sync.ensure(Sync.defer(ca.close()))(sock.connect(client, ca, cl).safe.get.map(r => assert(r.value == 0)))
            connected.andThen {
                val noAddr = Buffer.alloc[Byte](SockAddr.inet4Size)
                val noLen  = Buffer.alloc[Int](1)
                noLen.set(0, SockAddr.inet4Size)
                Sync.ensure(Sync.defer { noAddr.close(); noLen.close() }) {
                    sock.accept(server, noAddr, noLen).safe.get.map(_.value.toInt)
                }.map { accepted =>
                    val shim = Ffi.load[PosixShimBindings]
                    assert(shim.kyo_posix_set_nonblocking(client) == 0, "set client non-blocking")
                    assert(shim.kyo_posix_set_nonblocking(accepted) == 0, "set accepted non-blocking")
                    sock.close(server).safe.get.map(_ => (client, accepted))
                }
            }
        }
    end loopbackPair

end HandshakeEngineFreeTest
