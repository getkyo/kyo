package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.NetConfig
import kyo.net.Test

/** Ghost-accept regression guard for the io_uring listener teardown: a CLOSED listener's still-queued accept arm must never run against a
  * recycled fd number and steal a later listener's connections.
  *
  * [[IoUringDriver.awaitAccept]] only ENQUEUES the accept arm on the engine FIFO; the arm preps its SQE later, on the reap carrier. If the
  * listener's close ran `cancel` + `close(fd)` on the caller carrier before that arm drained, `cancel` saw no pending entry (nothing was
  * registered yet), the fd number was freed and recycled (typically by the very next listener), and the late-draining arm prepped an accept
  * SQE against the RECYCLED socket with the closed listener's promise and handler. Each such ghost is single-shot, so every listener closed
  * in that window silently stole exactly one incoming connection from whatever socket reused its fd number, delivering it to the dead
  * listener's handler. [[kyo.net.internal.transport.IoDriver.closeListener]] fixes this by sequencing the WHOLE teardown (cancel, the
  * [[PosixHandle.requestClose]] arm guard, the SQE flush, the shutdown + fd close) as one engine op BEHIND any queued arm.
  *
  * The leaf reproduces the window deterministically with the sanctioned reap-carrier pin (a latch the test releases, not a sleep): listener
  * A's accept arm is enqueued behind the pin, A closes (an unsequenced close frees the fd immediately on this carrier), listener B is created
  * (recycling A's fd number), and the pin releases. TWO clients then connect to B. The ghost is single-shot, so an unsequenced teardown would
  * steal exactly one of the two connections for A's handler; the sequenced teardown routes both to B's handler and A's handler sees nothing. This is io_uring-mechanism-specific
  * (the queued-arm-vs-fd-recycle window only exists on the completion driver with its engine FIFO); the cross-backend behavioral coverage of
  * listener lifecycle lives in the shared [[kyo.net.TransportListenerTest]].
  *
  * Anti-flakiness: the window is structural (the pin holds every arm and the teardown in a known order), not timing-driven; the only waits
  * are promises completed by real accept dispatches. No sleep, no poll-retry.
  */
class IoUringListenerCloseRecycleTest extends Test:

    import AllowUnsafe.embrace.danger

    private val transportConfig = NetConfig.default

    "a closed listener's queued accept arm never steals a later listener's connections" in {
        PosixTestSockets.assumeUring()
        val driver = IoUringDriver.init()
        discard(driver.start())
        val transport = TestTransports.forTesting(driver, Ffi.load[SocketBindings], backendIsEpoll = false)
        Sync.ensure(Sync.defer { driver.close() }) {
            val gate  = new java.util.concurrent.CountDownLatch(1)
            val pinIn = Promise.Unsafe.init[Unit, Abort[Closed]]()
            driver.submitEngineOp { () =>
                pinIn.completeDiscard(Result.succeed(()))
                gate.await()
            }
            pinIn.safe.get.map { _ =>
                // Reap carrier pinned: listener A's accept arm and its teardown queue behind the pin in FIFO order. The Sync.ensure
                // releases the gate on ANY failure in this segment, so a failed listen can never leave the reap carrier parked (which would
                // wedge every later test on this scheduler); the normal-path countDown below fires first and the extra release is a no-op.
                val stolenByA = new java.util.concurrent.atomic.AtomicInteger(0)
                Sync.ensure(Sync.defer(gate.countDown())) {
                    transport.listen("127.0.0.1", 0, 16)(_ => discard(stolenByA.incrementAndGet())).safe.get.map { listenerA =>
                        listenerA.close()
                        val handledByB = new java.util.concurrent.atomic.AtomicInteger(0)
                        val bothToB    = Promise.Unsafe.init[Unit, Abort[Closed]]()
                        transport.listen("127.0.0.1", 0, 16) { conn =>
                            if handledByB.incrementAndGet() == 2 then bothToB.completeDiscard(Result.succeed(()))
                            conn.close()
                        }.safe.get.map { listenerB =>
                            gate.countDown()
                            transport.connect("127.0.0.1", listenerB.port).safe.get.map { c1 =>
                                transport.connect("127.0.0.1", listenerB.port).safe.get.map { c2 =>
                                    // A ghost accept is single-shot, so it can steal at most one connection: requiring BOTH dispatches to
                                    // reach B makes the theft deterministic regardless of which pending accept the kernel completes first.
                                    bothToB.safe.get.map { _ =>
                                        c1.close()
                                        c2.close()
                                        listenerB.close()
                                        assert(
                                            stolenByA.get() == 0,
                                            s"the closed listener's ghost accept stole ${stolenByA.get()} connection(s) from the next listener"
                                        )
                                        assert(
                                            handledByB.get() == 2,
                                            s"both connections must reach the live listener, got ${handledByB.get()}"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }.map(_ => succeed)
    }

end IoUringListenerCloseRecycleTest
