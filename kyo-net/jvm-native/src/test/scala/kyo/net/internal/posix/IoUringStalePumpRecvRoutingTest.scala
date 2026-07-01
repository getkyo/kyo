package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.tls.TlsRealEngines
import kyo.net.internal.transport.ReadOutcome

/** Reproduce-first confirmation of a SEPARATE (not yet fixed) orphaned-recv routing gap, found while investigating the P10 cell-8
  * steady-state "Closed at collect" corruption: a stale, non-`handshakeOwned` recv armed by the PLAINTEXT ReadPump BEFORE
  * `detachForUpgrade` runs (the ordinary pre-upgrade continuation, not the handshake's own producer recv) can survive kernel-owned past
  * `onFinished`'s FULL flag-clear, exactly like [[IoUringOrphanHandshakeRecvRoutingTest]]'s `handshakeOwned=true` case -- but this one has
  * NO routing clause protecting it.
  *
  * [[IoUringDriver.complete]]'s three routing clauses are `upgradeActive || (upgrading && !handshakeOwned) || (handshakeOwned &&
  * isUpgraded)`. The middle clause exists for exactly this recv, but only while `upgrading` is still true; `upgradeActive` and `upgrading`
  * are cleared as TWO SEPARATE, non-atomic `@volatile var` writes in `onFinished` (`PosixTransport.upgradeRole`), so the middle clause only
  * covers the narrow race window BETWEEN those two writes, not the (potentially much longer) window after `onFinished` has fully run. Once
  * both flags are false and `tls` is `Present`, this recv is indistinguishable, using only `handshakeOwned`/`isUpgraded`, from a genuine
  * POST-upgrade `ReadPump` recv (both are `handshakeOwned=false`), so it falls through to the ordinary TLS-feed branch.
  *
  * Unlike the `handshakeOwned=true` orphan (whose bytes at least land in the SAME buffer `recvStagingFor` allocates, since it was armed
  * while `tls` was Absent same as this one -- see `submitRecv`'s `recvTarget` selection), this recv's kernel write landed in
  * `handle.readBuffer` (the raw plaintext buffer), but the mis-routed dispatch reads from `recvStagingFor(h)` instead -- a stale/unrelated
  * buffer, not the buffer the kernel actually filled. [[PosixHandle.recvStagingOwnerId]]'s ownership check does NOT catch this: `staging` is
  * still correctly tagged to the SAME handle `h` (no cross-connection leak), so the check passes; the bug is that the bytes it claims to
  * hold were never written by THIS recv.
  *
  * This test drives the exact reap-time state directly: arms a real, ordinary (non-handshakeOwned) recv on `acceptedH` while every upgrade
  * flag is still false (matching a real pre-upgrade `ReadPump` continuation), waits for the real SQE submit, then flips the handle's flags
  * through a full simulated upgrade lifecycle ending in the post-`onFinished` state (both flags false, `tls` Present) WHILE the recv is
  * still kernel-owned, and only then lets the peer's bytes reap it for real over io_uring.
  *
  * io_uring-only ([[PosixTestSockets.assumeUring]]): only io_uring has a kernel-owned recv that can outlive the whole upgrade this way.
  *
  * Anti-flakiness: no `Thread.sleep`, no busy-spin; `awaitCondition` polls real, observable state transitions, mirroring
  * [[IoUringOrphanHandshakeRecvRoutingTest.awaitCondition]].
  */
class IoUringStalePumpRecvRoutingTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    private def awaitCondition(bound: Duration)(cond: => Boolean)(using Frame): Boolean < Async =
        val deadline = java.lang.System.nanoTime() + bound.toNanos
        Loop(()) { _ =>
            if cond then Loop.done(true)
            else if java.lang.System.nanoTime() >= deadline then Loop.done(false)
            else Async.sleep(2.millis).andThen(Loop.continue(()))
        }
    end awaitCondition

    "IoUringDriver stale pre-upgrade pump recv" - {

        "reaping after onFinished fully clears both upgrade flags routes through the upgrade-handoff slot, not the normal TLS-feed branch" in {
            PosixTestSockets.assumeUring()
            TlsRealEngines.assumeTlsReady()
            given Frame = Frame.internal
            val depth   = math.max(256, kyo.net.TransportConfig.default.ioPoolSize * 64)
            val uring   = Ffi.load[IoUringBindings]
            val ring    = Buffer.alloc[Byte](uring.kyo_uring_sizeof().toInt)
            val rc      = uring.io_uring_queue_init(depth, ring, 0)
            if rc != 0 then
                ring.close()
                throw Closed("IoUringStalePumpRecvRoutingTest", summon[Frame], s"queue_init failed: rc=$rc")
            val driver = TestDrivers.forBindings(uring, ring)
            discard(driver.start())
            Sync.ensure(Sync.defer(driver.close())) {
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    val serverEngine = TlsRealEngines.singleEngine(isServer = true)
                    Sync.ensure(Sync.defer(serverEngine.free())) {
                        val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                        // Arm an ORDINARY recv exactly as the plaintext ReadPump's continuation would, BEFORE any upgrade flag is set:
                        // handshakeOwned=false, tls Absent (so submitRecv targets handle.readBuffer, not recvStagingFor), no gate active.
                        val producer = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                        driver.awaitRead(acceptedH, producer)

                        // Wait for the real submit (hasInFlightRead observes the registered PendingOp) before simulating the upgrade:
                        // flipping flags too early would race submitRecv's own tls-keyed buffer choice.
                        awaitCondition(5.seconds)(driver.hasInFlightRead(acceptedH)).map { armed =>
                            assert(armed, "stale pump recv's submitRecv never ran (a hang, not the routing hazard under test)")

                            // Simulate the WHOLE upgrade lifecycle running to completion while the recv above stays kernel-owned and in flight,
                            // ending in onFinished's exact final state (PosixTransport.scala:1303-1309 write order): upgradeActive false,
                            // handshakeReading false, tls Present, upgrading false.
                            acceptedH.isUpgraded = true
                            acceptedH.upgradeActive = true
                            acceptedH.upgradeActive = false
                            acceptedH.handshakeReading = false
                            acceptedH.tls = Present(serverEngine)
                            acceptedH.upgrading = false

                            val payload = Array[Byte](9, 8, 7, 6, 5)
                            assert(sock.sendNow(
                                client,
                                Buffer.fromArray[Byte](payload),
                                payload.length.toLong,
                                0
                            ).value == payload.length.toLong)

                            awaitCondition(5.seconds) {
                                acceptedH.upgradeHandoff.get() match
                                    case _: PosixHandle.UpgradeHandoff.Carryover => true
                                    case _                                       => false
                            }.map { staged =>
                                assert(
                                    staged,
                                    s"stale pump recv never staged a Carryover (slot=${acceptedH.upgradeHandoff.get()}, " +
                                        s"producer=${producer.poll()}); it misrouted into the normal post-upgrade path instead"
                                )
                                acceptedH.upgradeHandoff.get() match
                                    case PosixHandle.UpgradeHandoff.Carryover(bytes) =>
                                        assert(
                                            bytes.toSeq == payload.toSeq,
                                            s"staged Carryover bytes ${bytes.toSeq} != sent payload ${payload.toSeq}"
                                        )
                                    case other => fail(s"expected Carryover, got $other")
                                end match
                                // The stale recv's own throwaway producer promise must never complete: routing it through upgradeHandoff means
                                // nothing touches `producer` directly.
                                assert(producer.poll().isEmpty, s"producer promise must stay pending (orphaned); got ${producer.poll()}")
                                discard(sock.close(client))
                                succeed
                            }
                        }
                    }
                }
            }
        }
    }

end IoUringStalePumpRecvRoutingTest
