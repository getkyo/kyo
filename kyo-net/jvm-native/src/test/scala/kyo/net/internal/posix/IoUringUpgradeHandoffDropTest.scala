package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.TransportConfig

/** Reproduce-first regression for a STARTTLS upgrade-handoff drop on io_uring: without an `onInboundClosedDuringRead` override,
  * [[IoUringDriver]] would fall back to [[kyo.net.internal.transport.IoDriver]]'s no-op default, so a STARTTLS upgrade racing the plaintext
  * [[kyo.net.internal.transport.ReadPump]]'s parked put would silently drop bytes already pulled off the socket instead of salvaging them into
  * [[PosixHandle.upgradeHandoff]] -- the same race [[PollerIoDriver]] handles (see `PollerIoDriver.scala:645-664`). The io_uring arm provides
  * the matching override.
  *
  * The main scenario drives the race directly rather than through a real TLS handshake ([[StartTlsUpgradeCloseRaceTest]] exercises that):
  * with `channelCapacity=1` and nothing consuming `conn.inbound`, chunk A fills the channel; chunk B's delivery then parks the pump's
  * putFiber (`Channel.offer` returns false, `ReadPump.offerToChannel` falls to the putFiber branch). Setting `upgrading=true` and calling
  * `detachForUpgrade()` closes `inbound`, which both returns `[A]` (the already-buffered chunk) and fails B's parked put with `Closed`,
  * invoking `driver.onInboundClosedDuringRead`. Without the override this would silently drop B; with it, B lands in `upgradeHandoff` as a
  * Carryover. The other two scenarios pin the hook's remaining contracts directly: fulfilling an already-parked handshake Waiter, and
  * discarding the bytes on an ordinary (non-upgrade) close.
  *
  * io_uring-only ([[PosixTestSockets.assumeUring]]): only io_uring had the missing override; the poller and NIO drivers already salvage.
  *
  * Anti-flakiness: waits for `conn.inbound.size() == 1` (chunk A landed) then for `!driver.hasInFlightRead(handle)` (chunk B's recv reaped
  * and its put parked, not still in flight) before detaching, and polls the async engine-FIFO-queued salvage's observable effect. No sleep.
  * Every scenario reclaims its client fd and handle via `Sync.ensure` so a failed assertion (a real regression) surfaces as that assertion,
  * not a cascading fd-leak failure from the abandoned cleanup.
  */
class IoUringUpgradeHandoffDropTest extends Test:

    import AllowUnsafe.embrace.danger

    private def awaitCondition(bound: Duration)(cond: => Boolean)(using Frame): Boolean < Async =
        val deadline = java.lang.System.nanoTime() + bound.toNanos
        Loop(()) { _ =>
            if cond then Loop.done(true)
            else if java.lang.System.nanoTime() >= deadline then Loop.done(false)
            else Async.sleep(2.millis).andThen(Loop.continue(()))
        }
    end awaitCondition

    /** Build a fresh io_uring driver, start it, and run `f` with the driver guaranteed closed afterward. Each scenario gets its own ring so
      * the three tests never share driver-level state.
      */
    private def withDriver[A](f: IoUringDriver => A < (Async & Abort[Closed]))(using Frame): A < (Async & Abort[Closed]) =
        val depth = math.max(256, kyo.net.ioPoolSize() * 64)
        val uring = Ffi.load[IoUringBindings]
        val ring  = Buffer.alloc[Byte](uring.kyo_uring_sizeof().toInt)
        val rc    = uring.io_uring_queue_init(depth, ring, 0)
        if rc != 0 then
            ring.close()
            throw Closed("IoUringUpgradeHandoffDropTest", summon[Frame], s"queue_init failed: rc=$rc")
        val driver = TestDrivers.forBindings(uring, ring)
        discard(driver.start())
        Sync.ensure(Sync.defer(driver.close()))(f(driver))
    end withDriver

    "IoUringDriver STARTTLS upgrade-handoff" - {

        "salvages a plaintext ReadPump chunk parked on a full inbound channel when detachForUpgrade races it" in {
            PosixTestSockets.assumeUring()
            given Frame = Frame.internal
            withDriver { driver =>
                val sock = Ffi.load[SocketBindings]
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    val transport = TestTransports.forTesting(
                        TransportConfig.default.copy(channelCapacity = 1),
                        driver,
                        sock,
                        backendIsEpoll = false
                    )
                    val handle = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    Sync.ensure(Sync.defer { discard(sock.close(client)); driver.closeHandle(handle) }) {
                        val conn = transport.openWith(handle, driver)
                        conn.start()

                        val chunkA = Array[Byte](1, 2, 3, 4, 5)
                        val chunkB = Array[Byte](9, 8, 7)

                        assert(sock.sendNow(client, Buffer.fromArray[Byte](chunkA), chunkA.length.toLong, 0).value == chunkA.length.toLong)

                        // Wait for chunk A to land in the (capacity-1) inbound channel: the pump's first offer succeeds, filling it.
                        awaitCondition(5.seconds)(conn.inbound.size().getOrElse(-1) == 1).map { landed =>
                            assert(landed, "chunk A never landed in the inbound channel (a hang, not the race under test)")

                            assert(sock.sendNow(
                                client,
                                Buffer.fromArray[Byte](chunkB),
                                chunkB.length.toLong,
                                0
                            ).value == chunkB.length.toLong)

                            // Chunk B's recv reaps, offerToChannel's offer fails (channel full, chunk A unconsumed), and the pump parks on
                            // putFiber instead of re-arming: hasInFlightRead drops to false and STAYS false (nothing re-arms a parked pump).
                            awaitCondition(5.seconds)(!driver.hasInFlightRead(handle)).map { parked =>
                                assert(parked, "chunk B's recv never reaped / its put never parked (a hang, not the race under test)")

                                handle.upgrading = true
                                val buffered = conn.detachForUpgrade()
                                val bufferedBytes: Array[Byte] =
                                    buffered.map(chunks => chunks.toArray.flatMap(_.toArray)).getOrElse(Array.emptyByteArray)
                                assert(
                                    bufferedBytes.toSeq == chunkA.toSeq,
                                    s"detachForUpgrade must return the already-buffered chunk A, got ${bufferedBytes.toSeq}"
                                )

                                // The core regression guard: chunk B, which was off the socket and parked in the pump's put when
                                // detachForUpgrade raced it, must be salvaged into upgradeHandoff as a Carryover instead of silently dropped.
                                // The parked put's onComplete callback (ReadPump.offerToChannel's putFiber.onComplete, which invokes
                                // driver.onInboundClosedDuringRead) fires as a rescheduled fiber resumption, not necessarily inline with
                                // inbound.close()'s synchronous queue flush, so poll rather than asserting immediately.
                                awaitCondition(5.seconds) {
                                    handle.upgradeHandoff.get() match
                                        case _: PosixHandle.UpgradeHandoff.Carryover => true
                                        case _                                       => false
                                }.map { salvaged =>
                                    handle.upgradeHandoff.get() match
                                        case c: PosixHandle.UpgradeHandoff.Carryover =>
                                            assert(
                                                c.bytes.toSeq == chunkB.toSeq,
                                                s"salvaged upgradeHandoff bytes ${c.bytes.toSeq} did not match chunk B ${chunkB.toSeq}"
                                            )
                                        case other =>
                                            fail(s"chunk B was dropped instead of salvaged into upgradeHandoff (slot=$other)")
                                    end match
                                    succeed
                                }
                            }
                        }
                    }
                }
            }
        }

        "fulfils an already-parked handshake Waiter directly with the salvaged bytes" in {
            PosixTestSockets.assumeUring()
            given Frame = Frame.internal
            withDriver { driver =>
                val sock = Ffi.load[SocketBindings]
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    val handle = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    handle.driver = driver
                    Sync.ensure(Sync.defer { discard(sock.close(client)); driver.closeHandle(handle) }) {
                        handle.upgrading = true

                        // Mirrors PosixTransport.driveUpgradeRead's own Waiter construction (PosixTransport.scala:1868-1883): a raw
                        // IOPromise observed via onComplete, wrapped as the Promise.Unsafe the UpgradeHandoff slot carries.
                        val delivered = new java.util.concurrent.atomic.AtomicReference[Result[kyo.net.NetException, Span[Byte]]](null)
                        val raw       = new kyo.scheduler.IOPromise[kyo.net.NetException, Span[Byte]]
                        raw.onComplete(result => delivered.set(result))
                        val waiterPromise = raw.asInstanceOf[Promise.Unsafe[Span[Byte], Abort[kyo.net.NetException]]]
                        handle.upgradeHandoff.set(PosixHandle.UpgradeHandoff.Waiter(waiterPromise, summon[Frame]))

                        val bytes = Array[Byte](11, 22, 33)
                        // Mirrors the real caller's invariant (PollerIoDriver/IoUringDriver's own read-dispatch sets lastPlaintextRead to the
                        // SAME array immediately before offerToChannel can ever fail into this hook): onInboundClosedDuringRead claims this
                        // slot before delivering (the one-shot CAS claim that prevents a duplicate feed), so a direct unit call must set it up
                        // first or the claim never wins and the salvage is a no-op by design, not a hang.
                        handle.lastPlaintextRead.set(Present(bytes))
                        driver.onInboundClosedDuringRead(handle, Span.fromUnsafe(bytes))

                        awaitCondition(5.seconds)(delivered.get() != null).map { fulfilled =>
                            assert(fulfilled, "the parked Waiter was never fulfilled (the salvage never reached it, or hung)")
                            delivered.get() match
                                case Result.Success(span) =>
                                    assert(
                                        span.toArrayUnsafe.toSeq == bytes.toSeq,
                                        s"waiter fulfilled with unexpected bytes: ${span.toArrayUnsafe.toSeq}"
                                    )
                                case other => fail(s"waiter completed with an unexpected outcome: $other")
                            end match
                            handle.upgradeHandoff.get() match
                                case PosixHandle.UpgradeHandoff.Idle => ()
                                case other =>
                                    fail(s"upgradeHandoff must return to Idle after fulfilling the waiter, got $other")
                            end match
                            succeed
                        }
                    }
                }
            }
        }

        "discards the bytes when the handle is not upgrading (ordinary close, unchanged behavior)" in {
            PosixTestSockets.assumeUring()
            given Frame = Frame.internal
            withDriver { driver =>
                val sock = Ffi.load[SocketBindings]
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    val handle = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    handle.driver = driver
                    Sync.ensure(Sync.defer { discard(sock.close(client)); driver.closeHandle(handle) }) {
                        // handle.upgrading stays false (the default): an ordinary close, not a STARTTLS upgrade window.
                        val bytes = Array[Byte](44, 55)
                        driver.onInboundClosedDuringRead(handle, Span.fromUnsafe(bytes))

                        // The guard is synchronous (no submitEngineOp on the non-upgrading path), so no queued work is even enqueued: the
                        // slot must stay Idle without needing to wait for the reap carrier.
                        handle.upgradeHandoff.get() match
                            case PosixHandle.UpgradeHandoff.Idle => ()
                            case other =>
                                fail(s"bytes must be discarded (not staged) on a non-upgrade close, got $other")
                        end match
                        succeed
                    }
                }
            }
        }
    }

end IoUringUpgradeHandoffDropTest
