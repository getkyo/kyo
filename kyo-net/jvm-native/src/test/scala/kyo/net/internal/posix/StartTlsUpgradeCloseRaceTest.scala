package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.NetConnectionClosedException
import kyo.net.NetException
import kyo.net.NetTlsConfig
import kyo.net.Test
import kyo.net.internal.tls.BoringSslBindings
import kyo.net.internal.tls.TlsTestCert

/** Probes the STARTTLS upgrade racing a concurrent close: the upgrade detaches the plaintext connection (`detachForUpgrade`), builds a new
  * TLS engine, and re-handshakes over the SAME fd; a `closeHandle` fired while that re-handshake is in flight must not use-after-free, must
  * not double-close the fd, must not hang, and must leave the connection in a consistent terminal state (a clean abort `Closed`).
  *
  * Driven over a real loopback socket pair with the real `PosixTransport.upgradeRole` and a real BoringSSL engine. Only the SERVER side
  * upgrades; the client never sends a ClientHello, so the server's re-handshake is GUARANTEED in flight (its `recvAndFeed` probes the socket,
  * gets EAGAIN, and arms a read it will park on) and cannot complete on its own. That makes the close land squarely in the documented upgrade
  * window, deterministically, not by luck.
  *
  * Gate: `PosixConstants.isLinux || PosixConstants.isMacOrBsd` + BoringSSL available.
  *
  * Anti-flakiness: `recvSignal.safe.get` latches on the real recvNow EAGAIN (the absence-of-data gate for the re-handshake in-flight moment).
  * MSG_PEEK cannot replace this latch. `RecordingSocketBindings.onRecvEagain` uses CAS-to-null (fires exactly once per spy instance). Each
  * of the 40 iterations creates a FRESH `RecordingSocketBindings` with a fresh `onRecvEagain` set (C5). No sleep.
  *
  * Uses `RecordingSocketBindings` with `onRecvEagain` to latch on the server-side EAGAIN that signals the re-handshake is in flight.
  * Asserts `spy.closeCounts.getOrDefault(serverFd, 0) == 1` (no double-close), `latchFired == 40`, and `abortBranch > 0`. The abort branch
  * additionally asserts the failure is [[NetConnectionClosedException]] with `.operation` naming `"handshake"` or `"upgrade"`, never
  * [[kyo.net.NetTlsHandshakeException]] and never a message-text match: `upgradeRole`'s failure channel is `Abort[NetException]`, disjoint
  * from `Closed`, so the close-mid-handshake case must surface as a typed leaf rather than an embedded `Closed` cause.
  *
  * The upgrade-handshake read no longer races the concurrent close. The detached plaintext `ReadPump` can re-arm a read on the fd the upgrade keeps
  * open (`detachForUpgrade` is a live withdrawal, `fdClosing=false`), but the poll carrier rejects that stray re-arm while the handle is upgrading and
  * the handshake has not yet taken read ownership (`upgradeActive && !handshakeReading`, [[PollerIoDriver]]), so the upgrade's own read is the only one
  * armed for the fd. This is the poller dual of the io_uring `PosixHandle.upgradeHandoff` slot. Runs on both epoll (Linux) and kqueue (macOS/BSD).
  */
class StartTlsUpgradeCloseRaceTest extends Test:

    import AllowUnsafe.embrace.danger

    private val transportConfig = kyo.net.TransportConfig.default

    private val serverTls = NetTlsConfig(
        certChainPath = Present(TlsTestCert.certPath),
        privateKeyPath = Present(TlsTestCert.keyPath)
    )

    private def boringSslAvailable: Boolean =
        try Ffi.load[BoringSslBindings].probeAvailable()
        catch case _: Throwable => false

    private def assumeReady(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then cancel("STARTTLS test needs epoll (Linux) or kqueue (macOS/BSD)")
        if !boringSslAvailable then cancel("BoringSSL not staged for this host")

    "STARTTLS upgrade racing a concurrent close" - {
        "a closeHandle fired while the server re-handshake is in flight aborts cleanly, closes the fd once, never UAFs or hangs" in {
            assumeReady()
            // Many iterations: invariant stress so any latent UAF / double-close / hang surfaces reliably (a hang fails the suite timeout).
            val iterations  = 40
            val latchFired  = new java.util.concurrent.atomic.AtomicInteger(0)
            val abortBranch = new java.util.concurrent.atomic.AtomicInteger(0)
            Loop.indexed { iter =>
                if iter >= iterations then
                    Loop.done {
                        assert(
                            latchFired.get() == iterations,
                            s"latch fired ${latchFired.get()}/$iterations times: the close did not land in the upgrade window every run"
                        )
                        assert(
                            abortBranch.get() > 0,
                            s"the close-mid-upgrade abort branch was never exercised (abortBranch=${abortBranch.get()})"
                        )
                        succeed
                    }
                else
                    val real     = Ffi.load[SocketBindings]
                    val backend  = PollerBackend.default()
                    val pollerFd = backend.create()
                    loopbackPair(real).map { case (clientFd, serverFd) =>
                        // C5: fresh RecordingSocketBindings per iteration so onRecvEagain is a fresh one-shot.
                        val recvSignal = Promise.Unsafe.init[Unit, Any]()
                        val spy        = RecordingSocketBindings(real)
                        spy.onRecvEagain = (fd: Int) =>
                            if fd == serverFd then recvSignal.completeDiscard(Result.succeed(()))
                        val driver     = TestDrivers.forBackend(backend, pollerFd, spy)
                        val driverDone = driver.start()
                        val transport  = TestTransports.forTesting(transportConfig, driver, spy, backendIsEpoll = false)
                        // Close the driver and the (never-upgraded) client fd at iteration end so fds do not leak across the 40 runs, then
                        // await the driver's own poll-loop-exit fiber (not discarding it): close() only requests teardown and returns
                        // immediately, without waiting for the poll-loop carrier to actually run it and release the fds it still holds.
                        // Sync.ensure's finalizer type is Sync-only (no Async), so the fiber await is chained after the whole ensure block
                        // instead, on the map that carries the loop outcome forward: discarding the exit fiber left a window where a
                        // not-yet-fully-torn-down driver from a prior iteration could still be blocked in the kernel poll call against its
                        // own (soon-to-be-reused) fd numbers when a later iteration's fresh driver and socket pair reused those same
                        // numbers, misrouting an event.
                        Sync.ensure(Sync.defer { driver.close(); discard(real.close(clientFd)) }) {
                            val serverHandle = PosixHandle.socket(serverFd, PosixHandle.DefaultReadBufferSize, Absent)
                            val serverPlain  = transport.openWith(serverHandle, driver)
                            serverPlain.start()

                            // Kick the SERVER upgrade. detachForUpgrade runs synchronously inside upgradeRole, the engine is built, and the
                            // first handshakeStep is submitted, all before this returns: the re-handshake is now in flight.
                            val serverUpgrade =
                                transport.upgradeRole(serverPlain, serverTls, transportConfig.channelCapacity, isServer = true).safe

                            // On the in-flight latch (server-handshake recvNow returned EAGAIN), fire the concurrent close on the SAME fd.
                            // Anti-flakiness: recvSignal.safe.get latches on the real EAGAIN (absence-of-data gate). No sleep.
                            val fireClose = recvSignal.safe.get.map { _ =>
                                Sync.defer {
                                    discard(latchFired.incrementAndGet())
                                    driver.closeHandle(serverHandle)
                                }
                            }

                            Fiber.initUnscoped(fireClose).map { closeFiber =>
                                Abort.run[NetException](serverUpgrade.get).map { upgradeResult =>
                                    closeFiber.get.map { _ =>
                                        // Idempotent extra close of the plaintext connection.
                                        serverPlain.close()
                                        // Real close count from spy (mirrors LatchingSockets.closesOf(serverFd)).
                                        val closes = spy.closeCounts.getOrDefault(serverFd, 0)
                                        assert(
                                            closes == 1,
                                            s"[iter $iter] upgrading fd=$serverFd closed $closes times (expected exactly 1: no double-close, no leak)"
                                        )
                                        upgradeResult match
                                            case Result.Failure(e: NetConnectionClosedException) =>
                                                assert(
                                                    e.operation == "handshake" || e.operation == "upgrade",
                                                    s"[iter $iter] close-mid-upgrade failure must name handshake or upgrade, got operation=${e.operation}"
                                                )
                                                discard(abortBranch.incrementAndGet())
                                                Loop.continue
                                            case Result.Success(conn) =>
                                                conn.close()
                                                assert(!conn.isOpen, s"[iter $iter] upgraded connection left open after a concurrent close")
                                                Loop.continue
                                            case Result.Failure(other) =>
                                                fail(s"[iter $iter] close-mid-upgrade failed with an unexpected NetException leaf: $other")
                                            case Result.Panic(e) =>
                                                fail(s"[iter $iter] upgrade racing close panicked (crash, not a clean state): $e")
                                        end match
                                    }
                                }
                            }
                        }.map((loopOutcome: Loop.Outcome[Unit, Unit]) => Abort.run(driverDone.safe.get).unit.map(_ => loopOutcome))
                    }
            }
        }
    }

    /** Build a connected, non-blocking loopback socket pair via `real`; returns (clientFd, acceptedFd). Mirrors StartTlsUpgradeTest. */
    private def loopbackPair(real: SocketBindings)(using Frame, kyo.test.AssertScope): (Int, Int) < Async =
        val server = real.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (a, l) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", 0).getOrElse(fail("encode failed"))
        Sync.ensure(Sync.defer(a.close())) {
            assert(real.bind(server, a, l).value == 0)
            assert(real.listen(server, 4).value == 0)
            val out = Buffer.alloc[Byte](SockAddr.inet4Size)
            val ol  = Buffer.alloc[Int](1)
            ol.set(0, SockAddr.inet4Size)
            val port =
                try
                    assert(real.getsockname(server, out, ol).value == 0)
                    ((out.get(2) & 0xff) << 8) | (out.get(3) & 0xff)
                finally
                    out.close()
                    ol.close()
            val client   = real.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
            val (ca, cl) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(fail("encode failed"))
            val connected =
                Sync.ensure(Sync.defer(ca.close()))(real.connect(client, ca, cl).safe.get.map(r => assert(r.value == 0)))
            connected.andThen {
                val noAddr = Buffer.alloc[Byte](SockAddr.inet4Size)
                val noLen  = Buffer.alloc[Int](1)
                noLen.set(0, SockAddr.inet4Size)
                Sync.ensure(Sync.defer { noAddr.close(); noLen.close() }) {
                    real.accept(server, noAddr, noLen).safe.get.map(_.value)
                }.map { accepted =>
                    val shim = Ffi.load[PosixShimBindings]
                    assert(shim.kyo_posix_set_nonblocking(client) == 0, "set client non-blocking")
                    assert(shim.kyo_posix_set_nonblocking(accepted) == 0, "set accepted non-blocking")
                    real.close(server).safe.get.map(_ => (client, accepted))
                }
            }
        }
    end loopbackPair

end StartTlsUpgradeCloseRaceTest
