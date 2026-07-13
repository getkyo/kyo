package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Connection
import kyo.net.NetAddress
import kyo.net.NetException
import kyo.net.Test
import kyo.net.internal.transport.Connection as InternalConnection

/** End-to-end tests for the unified [[PosixTransport]] TCP / UDS surface (`connect`, `listen`, `accept`, `connectUnix`, `listenUnix`, `close`)
  * over a real [[PollerIoDriver]] (epoll on Linux, kqueue on macOS/BSD).
  *
  * Every leaf drives the actual syscall surface end to end on a loopback pair: a server `listen`s, a client `connect`s, the server's accept loop
  * hands the accepted connection to the handler, and bytes round-trip through both connections' pumps. The leaves assert concrete bytes (not
  * just non-emptiness), pin the accepted fd as a distinct real fd, and prove close on one end surfaces `Closed` on the other. The suite cancels
  * only where the host lacks a real poller (non-POSIX); it runs identically on JVM (Panama) and Native (`@extern`).
  */
class PosixTransportSurfaceTest extends Test:

    import AllowUnsafe.embrace.danger

    private val transportConfig = kyo.net.TransportConfig.default

    private def assumePoller(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PosixTransport TCP/UDS surface needs epoll (Linux) or kqueue (macOS/BSD)")

    /** Build a transport over a fresh real poller driver, run `body`, then close the transport and the driver. No accept-loop drain is needed:
      * with the poller (epoll/kqueue) the accept loop is readiness-driven and never parks in a blocking `accept`. `transport.close()` closes every
      * listener, and `PosixListener.close()` (on the calling fiber) deregisters the accept interest via `driver.cancel`, which inline-completes the
      * parked accept promise with `Closed`; that completion runs the accept loop's exit branch inline (`IOPromise.flush`), then the listener
      * `shutdown`s + closes the listen fd, all synchronously. So once `transport.close()` returns, no accept loop is still running and the recycled
      * fd is already closed: a later leaf cannot have its connection stolen, and there is nothing to await.
      */
    private def withTransport[A](body: PosixTransport => A < (Async & Abort[NetException | Closed] & Scope))(using
        Frame
    ): A < (Async & Abort[NetException | Closed] & Scope) =
        val driver    = PollerIoDriver.init(transportConfig)
        val transport = TestTransports.forTesting(transportConfig, driver, Ffi.load[SocketBindings], backendIsEpoll = false)
        discard(driver.start())
        // Run the body, then ALWAYS close the transport and driver, re-raising the body's outcome. transport.close() tears the readiness-driven
        // accept loops down synchronously (see the scaladoc), so no Async drain step is needed.
        Abort.run[NetException | Closed](body(transport)).map { result =>
            Sync.defer(transport.close()).andThen(Sync.defer(driver.close())).andThen(Abort.get(result))
        }
    end withTransport

    /** The read fd backing a connection (the proof it is a real, distinct socket fd). */
    private def fdOf(conn: Connection): Int =
        conn.asInstanceOf[InternalConnection[PosixHandle]].handle.readFd

    /** Read exactly `target` bytes from a connection's inbound channel, concatenated. */
    private def collect(conn: Connection, target: Int)(using Frame): Array[Byte] < (Async & Abort[Closed]) =
        Loop(Array.emptyByteArray) { acc =>
            if acc.length >= target then Loop.done(acc)
            else conn.inbound.safe.take.map(chunk => Loop.continue(acc ++ chunk.toArray))
        }

    /** Bind a plain socket to an ephemeral loopback port, then close it, returning the now free (no-listener) port. Done with raw
      * [[SocketBindings]] (not a transport listener) so no blocking-accept loop is ever parked here: connecting to this port must be refused.
      */
    private def deadPort()(using Frame, kyo.test.AssertScope): Int < Async =
        val sockets = Ffi.load[SocketBindings]
        val fd      = sockets.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (a, l)  = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", 0).getOrElse(fail("encode failed"))
        Sync.ensure(Sync.defer(a.close())) {
            assert(sockets.bind(fd, a, l).value == 0)
            val out = Buffer.alloc[Byte](SockAddr.inet4Size)
            val ol  = Buffer.alloc[Int](1)
            ol.set(0, SockAddr.inet4Size)
            val port =
                try
                    assert(sockets.getsockname(fd, out, ol).value == 0)
                    ((out.get(2) & 0xff) << 8) | (out.get(3) & 0xff)
                finally
                    out.close()
                    ol.close()
            sockets.close(fd).safe.get.map(_ => port)
        }
    end deadPort

    "PosixTransport TCP" - {
        "connect + listen + accept round-trips a known message through an echo handler" in {
            assumePoller()
            withTransport { transport =>
                for
                    // The accepted (server-side) connection and its fd, captured from inside the handler.
                    serverConnRef <- AtomicRef.init[Maybe[Connection]](Absent)
                    accepted      <- Channel.init[Unit](1)
                    listener <- transport.listen("127.0.0.1", 0, 16) { serverConn =>
                        // Echo handler: capture the accepted connection, signal readiness, then echo each chunk back.
                        discard(Sync.Unsafe.evalOrThrow {
                            Fiber.initUnscoped {
                                Abort.run[Closed] {
                                    Sync.Unsafe.defer(serverConnRef.set(Present(serverConn))).andThen {
                                        accepted.put(()).andThen {
                                            Loop.foreach {
                                                serverConn.inbound.safe.take.map { chunk =>
                                                    serverConn.outbound.safe.put(chunk).andThen(Loop.continue)
                                                }
                                            }
                                        }
                                    }
                                }.unit
                            }
                        })
                    }.safe.get
                    port = listener.port
                    client <- transport.connect("127.0.0.1", port).safe.get
                    message = "posix-tcp-echo-roundtrip".getBytes("UTF-8")
                    _         <- client.outbound.safe.put(Span.fromUnsafe(message))
                    _         <- accepted.take
                    serverOpt <- Sync.Unsafe.defer(serverConnRef.get)
                    echoed    <- collect(client, message.length)
                yield
                    val clientFd = fdOf(client)
                    val serverFd = serverOpt match
                        case Present(c) => fdOf(c)
                        case Absent     => fail("server connection was never captured")
                    client.close()
                    serverOpt.foreach(_.close())
                    assert(port > 0, s"ephemeral port not resolved: $port")
                    assert(echoed.sameElements(message), s"echo mismatch: got '${new String(echoed, "UTF-8")}'")
                    assert(clientFd >= 0, s"client fd not a real fd: $clientFd")
                    assert(serverFd >= 0, s"accepted fd not a real fd: $serverFd")
                    assert(serverFd != clientFd, s"accepted fd must differ from client fd (both=$clientFd)")
                end for
            }
        }

        "closing the client surfaces Closed on the accepted server connection (close propagation)" in {
            assumePoller()
            withTransport { transport =>
                for
                    serverConnRef <- AtomicRef.init[Maybe[Connection]](Absent)
                    accepted      <- Channel.init[Unit](1)
                    listener <- transport.listen("127.0.0.1", 0, 16) { serverConn =>
                        discard(Sync.Unsafe.evalOrThrow {
                            Fiber.initUnscoped {
                                Abort.run[Closed] {
                                    Sync.Unsafe.defer(serverConnRef.set(Present(serverConn))).andThen(accepted.put(()))
                                }.unit
                            }
                        })
                    }.safe.get
                    client <- transport.connect("127.0.0.1", listener.port).safe.get
                    _      <- accepted.take
                    server <- Sync.Unsafe.defer(serverConnRef.get).map {
                        case Present(c) => c
                        case Absent     => fail("server connection was never captured")
                    }
                    // Close the client end; the server's pending inbound read must surface Closed once the peer-close EOF tears it down.
                    _       <- Sync.Unsafe.defer(client.close())
                    outcome <- Abort.run[Closed](server.inbound.safe.take)
                yield
                    server.close()
                    assert(outcome.isFailure, s"expected Closed on the accepted connection after the client closed, got $outcome")
                end for
            }
        }

        "listener reports the bound TCP address" in {
            assumePoller()
            withTransport { transport =>
                transport.listen("127.0.0.1", 0, 16)(_ => ()).safe.get.map { listener =>
                    val addr = listener.address
                    assert(addr == NetAddress.Tcp("127.0.0.1", listener.port), s"unexpected address $addr")
                }
            }
        }

        "connecting to a non-listening port fails Closed" in {
            assumePoller()
            withTransport { transport =>
                // A port nothing listens on (a plain socket bound then closed, no accept loop), so the connect must be refused.
                deadPort().map { port =>
                    Abort.run[NetException | Closed](transport.connect("127.0.0.1", port).safe.get).map { outcome =>
                        assert(outcome.isFailure, s"expected Closed connecting to dead port $port, got $outcome")
                    }
                }
            }
        }

        "connect resolves the loopback name 'localhost' and round-trips bytes" in {
            // Regression guard for the kyo-http migration: its client passes a hostname (e.g. "localhost") straight to
            // transport.connect, but encodeInet only encoded numeric IPv4/IPv6 literals, so "localhost" failed Closed with
            // "unresolvable address". The loopback-name shortcut in encodeInet must map "localhost" to a loopback literal and
            // the connect must complete a real round-trip (the server here listens on the numeric 127.0.0.1 loopback).
            assumePoller()
            withTransport { transport =>
                for
                    accepted <- Channel.init[Unit](1)
                    listener <- transport.listen("127.0.0.1", 0, 16) { serverConn =>
                        discard(Sync.Unsafe.evalOrThrow {
                            Fiber.initUnscoped {
                                Abort.run[Closed] {
                                    accepted.put(()).andThen {
                                        Loop.foreach {
                                            serverConn.inbound.safe.take.map { chunk =>
                                                serverConn.outbound.safe.put(chunk).andThen(Loop.continue)
                                            }
                                        }
                                    }
                                }.unit
                            }
                        })
                    }.safe.get
                    client <- transport.connect("localhost", listener.port).safe.get
                    message = "posix-localhost-roundtrip".getBytes("UTF-8")
                    _      <- client.outbound.safe.put(Span.fromUnsafe(message))
                    _      <- accepted.take
                    echoed <- collect(client, message.length)
                yield
                    client.close()
                    assert(echoed.sameElements(message), s"localhost echo mismatch: got '${new String(echoed, "UTF-8")}'")
                end for
            }
        }

        "connect to a numeric IPv4 literal round-trips with no DNS resolution" in {
            // A numeric literal takes encodeInet's synchronous fast path: it is encoded directly and never reaches the
            // HostResolver. This proves the resolution work added for hostnames does not perturb the numeric path that
            // every loopback test relies on (and that no blocking resolver thread is taken for a numeric connect).
            assumePoller()
            withTransport { transport =>
                for
                    accepted <- Channel.init[Unit](1)
                    listener <- transport.listen("127.0.0.1", 0, 16) { serverConn =>
                        discard(Sync.Unsafe.evalOrThrow {
                            Fiber.initUnscoped {
                                Abort.run[Closed] {
                                    accepted.put(()).andThen {
                                        Loop.foreach {
                                            serverConn.inbound.safe.take.map { chunk =>
                                                serverConn.outbound.safe.put(chunk).andThen(Loop.continue)
                                            }
                                        }
                                    }
                                }.unit
                            }
                        })
                    }.safe.get
                    client <- transport.connect("127.0.0.1", listener.port).safe.get
                    message = "posix-numeric-roundtrip".getBytes("UTF-8")
                    _      <- client.outbound.safe.put(Span.fromUnsafe(message))
                    _      <- accepted.take
                    echoed <- collect(client, message.length)
                yield
                    client.close()
                    assert(echoed.sameElements(message), s"numeric echo mismatch: got '${new String(echoed, "UTF-8")}'")
                end for
            }
        }

        "connect to an unresolvable hostname fails Closed cleanly (no hang, no crash)" in {
            // A hostname that does not resolve must fail the connect Closed cleanly (not hang on the blocking pool, not panic). This exercises
            // the full resolve-then-fail path through transport.connect: the host is non-numeric and not a loopback name, so it goes through
            // HostResolver, the real system resolver yields a failure, and the connect promise must complete with it. The host is under the RFC
            // 6761 reserved `.invalid` TLD, which never resolves (the system resolver returns NXDOMAIN), so the lookup fails against the REAL
            // resolver with no injected stub. A real negative lookup is not bounded by the transport (a pathological host resolver can stall on
            // an upstream query before returning NXDOMAIN), so a generous Async.timeout bounds it: either the resolver fails (Closed) or the
            // bound expires (Timeout), both clean failures with no hang and no crash. The numeric and loopback round-trip leaves above cover the
            // resolve-SUCCESS path against the real resolver.
            assumePoller()
            withTransport { transport =>
                Abort.run[NetException | Closed | Timeout](
                    Async.timeout(10.seconds)(transport.connect("kyo-net-unresolvable-host.invalid", 80).safe.get)
                ).map { outcome =>
                    assert(outcome.isFailure, s"expected a clean failure connecting to an unresolvable host, got $outcome")
                }
            }
        }
    }

    "PosixTransport UDS" - {
        "connectUnix + listenUnix round-trips a known message through an echo handler" in {
            assumePoller()
            // A unique short path under /tmp (exists on Linux and macOS). /tmp keeps it well under the 108-byte sun_path limit, unlike the
            // macOS $TMPDIR; nanoTime gives uniqueness without java.util.UUID (Native has no SecureRandom). The path is fresh per run, so no
            // stale-file cleanup is needed here (and `java.io.File` is unavailable on Scala.js, where this shared test must still link).
            val path = s"/tmp/kyo-posix-uds-${java.lang.System.nanoTime()}.sock"
            withTransport { transport =>
                for
                    serverConnRef <- AtomicRef.init[Maybe[Connection]](Absent)
                    accepted      <- Channel.init[Unit](1)
                    listener <- transport.listenUnix(path, 16) { serverConn =>
                        discard(Sync.Unsafe.evalOrThrow {
                            Fiber.initUnscoped {
                                Abort.run[Closed] {
                                    Sync.Unsafe.defer(serverConnRef.set(Present(serverConn))).andThen {
                                        accepted.put(()).andThen {
                                            Loop.foreach {
                                                serverConn.inbound.safe.take.map { chunk =>
                                                    serverConn.outbound.safe.put(chunk).andThen(Loop.continue)
                                                }
                                            }
                                        }
                                    }
                                }.unit
                            }
                        })
                    }.safe.get
                    client <- transport.connectUnix(path).safe.get
                    message = "posix-uds-echo-roundtrip".getBytes("UTF-8")
                    _         <- client.outbound.safe.put(Span.fromUnsafe(message))
                    _         <- accepted.take
                    serverOpt <- Sync.Unsafe.defer(serverConnRef.get)
                    echoed    <- collect(client, message.length)
                yield
                    val clientFd = fdOf(client)
                    val serverFd = serverOpt match
                        case Present(c) => fdOf(c)
                        case Absent     => fail("server connection was never captured")
                    client.close()
                    serverOpt.foreach(_.close())
                    assert(listener.port == -1, s"Unix listener port must be -1, got ${listener.port}")
                    assert(listener.address == NetAddress.Unix(path), s"unexpected Unix address ${listener.address}")
                    assert(echoed.sameElements(message), s"UDS echo mismatch: got '${new String(echoed, "UTF-8")}'")
                    assert(serverFd != clientFd, s"accepted UDS fd must differ from client fd (both=$clientFd)")
                end for
            }
        }
    }

end PosixTransportSurfaceTest
