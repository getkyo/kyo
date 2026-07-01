package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.Connection
import kyo.net.Listener
import kyo.net.NetTlsConfig
import kyo.net.Test
import kyo.net.TlsTestCertShared
import kyo.net.TransportConfig
import kyo.net.internal.tls.TlsRealEngines

// Regression guard for the io_uring STARTTLS multi-record upgrade stall: a TOCTOU race in the upgrade-byte handoff between the handshake-driving
// carrier (PosixTransport.driveUpgradeRead) and the io_uring reap carrier (IoUringDriver.complete). The two carriers used two independent volatile
// slots (upgradeCarryover / upgradeReadWaiter), so the reap could stage the stale recv's bytes as a carryover while the handshake (having read the
// old absent carryover) parked its waiter: the bytes were stranded against a parked waiter that nothing fulfilled and the upgrade hung forever. The
// harness runs many concurrent io_uring STARTTLS upgrades with a multi-record payload so that interleaving happens on essentially every run; with
// the single atomic handoff slot in place every upgrade completes its 32KB round-trip. Any stall is recorded and fails the test (it does not rely on
// the suite watchdog), so the before state fails on the assertion and the after state is genuinely green.
class IoUringMutualTlsStressTest extends Test:

    import AllowUnsafe.embrace.danger

    private val tcfg = TransportConfig.default

    private val upgradeRequest: Span[Byte] = Span.from(Array[Byte]('U'))
    private val upgradeReady: Span[Byte]   = Span.from(Array[Byte]('R'))

    private def collectN(conn: Connection, target: Int)(using Frame): Array[Byte] < (Async & Abort[Closed]) =
        Loop(Array.emptyByteArray) { acc =>
            if acc.length >= target then Loop.done(acc)
            else conn.inbound.safe.take.map(chunk => Loop.continue(acc ++ chunk.toArray))
        }

    "io_uring STARTTLS multi-record upgrade stress" in {
        PosixTestSockets.assumeUring()
        TlsRealEngines.assumeTlsReady()
        TlsTestCertShared.writePems.map { case (certPath, keyPath) =>
            val serverTls = NetTlsConfig(certChainPath = Present(certPath), privateKeyPath = Present(keyPath))
            val clientTls = NetTlsConfig(trustAll = true, sniHostname = Present("localhost"))
            val payload   = Array.fill[Byte](32768)(42) // spans multiple TLS records (max record ~16KB), as the failing leaf does

            // Every non-success outcome of an upgrade is recorded here with the stage it stalled at. The race symptom is a Timeout at the "upgrade"
            // stage; a non-empty record at the end fails the test. The list never being touched on the happy path keeps the success run allocation-free.
            val stalls = new java.util.concurrent.ConcurrentLinkedQueue[String]()

            def startTlsEchoServer(transport: kyo.net.internal.posix.PosixTransport): Listener < (Async & Abort[Closed]) =
                transport.listen("127.0.0.1", 0, 128) { serverConn =>
                    discard(Sync.Unsafe.evalOrThrow {
                        Fiber.initUnscoped {
                            Abort.run[Closed] {
                                serverConn.inbound.safe.take.flatMap { _ =>
                                    serverConn.outbound.safe.put(upgradeReady).andThen {
                                        transport.upgradeToTls(serverConn, serverTls, 16).safe.get.flatMap { tlsConn =>
                                            Loop.foreach {
                                                tlsConn.inbound.safe.take.flatMap(d => tlsConn.outbound.safe.put(d).andThen(Loop.continue))
                                            }
                                        }
                                    }
                                }
                            }.unit
                        }
                    })
                }.safe.get

            // groups INDEPENDENT io_uring transports run concurrently (each its own ring + reap loop + engine FIFO), each performing width
            // concurrent STARTTLS upgrades per round for rounds rounds, all under async concurrency 4: the cross-driver contention that surfaces the
            // handoff race. Sized so the reap carrier and the handshake carrier interleave their handoff check-then-act on essentially every run.
            val groups = 6
            val width  = 12
            val rounds = 4

            def oneUpgrade(transport: kyo.net.internal.posix.PosixTransport, port: Int, tag: String): Unit < (Async & Abort[Closed]) =
                val stage = new java.util.concurrent.atomic.AtomicReference[String]("init")
                Abort.run[Closed | Timeout](
                    Async.timeout(8.seconds) {
                        for
                            _       <- Sync.defer(stage.set("connect"))
                            conn    <- transport.connect("127.0.0.1", port).safe.get
                            _       <- Sync.defer(stage.set("put-signal"))
                            _       <- conn.outbound.safe.put(upgradeRequest)
                            _       <- Sync.defer(stage.set("await-ready"))
                            _       <- conn.inbound.safe.take
                            _       <- Sync.defer(stage.set("upgrade"))
                            tlsConn <- transport.upgradeToTls(conn, clientTls, 16).safe.get
                            _ <- Sync.defer {
                                val h = tlsConn.asInstanceOf[kyo.net.internal.transport.Connection[PosixHandle]].handle
                                java.lang.System.err.println(
                                    s"ZZTRACE-STRESS client tag=$tag fd=${h.readFd} id=${h.id.packed} ch=${tlsConn.inbound.hashCode()}"
                                )
                            }
                            _      <- Sync.defer(stage.set("put-payload"))
                            _      <- tlsConn.outbound.safe.put(Span.fromUnsafe(payload))
                            _      <- Sync.defer(stage.set("collect"))
                            echoed <- collectN(tlsConn, payload.length)
                        yield
                            tlsConn.close()
                            echoed
                    }
                ).map {
                    case Result.Success(echoed) =>
                        assert(
                            echoed.length == payload.length && echoed.sameElements(payload),
                            s"$tag: 32KB round-trip mismatch (${echoed.length})"
                        )
                    case other =>
                        // The upgrade did not complete its round-trip: record the stage it stalled at (the race manifests as a Timeout at the
                        // "upgrade" stage). The recorded entries fail the test at the end so a stall is a hard failure, not a swallowed log line.
                        discard(stalls.add(s"$tag stalled-at=${stage.get} -> $other"))
                }
            end oneUpgrade

            def oneGroup(g: Int): Unit < (Async & Abort[Closed]) =
                val driver    = IoUringDriver.init(tcfg)
                val transport = TestTransports.forTesting(tcfg, driver, Ffi.load[SocketBindings], backendIsEpoll = false)
                discard(driver.start())
                startTlsEchoServer(transport).map { listener =>
                    Loop(0) { round =>
                        if round >= rounds then Loop.done(())
                        else
                            Async.foreach(0 until width)(j => oneUpgrade(transport, listener.port, s"g$g-r$round-$j"))
                                .map(_ => Loop.continue(round + 1))
                    }.map { _ =>
                        listener.close()
                        transport.close()
                        driver.close()
                    }
                }
            end oneGroup

            Async.foreach(0 until groups)(oneGroup).andThen {
                assert(
                    stalls.isEmpty,
                    s"${stalls.size} upgrade(s) stalled: ${stalls.toArray.mkString("; ")}"
                )
            }
        }
    }

end IoUringMutualTlsStressTest
