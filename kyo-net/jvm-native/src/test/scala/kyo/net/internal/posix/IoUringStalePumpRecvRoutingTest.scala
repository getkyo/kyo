package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.TlsRealEngines
import kyo.net.internal.transport.ReadOutcome

/** Reproduce-first confirmation of a second guarantee for the steady-state "Closed at collect" corruption: a stale,
  * non-`handshakeOwned` recv armed by the PLAINTEXT ReadPump BEFORE `detachForUpgrade` runs (the ordinary pre-upgrade continuation, not the
  * handshake's own producer recv) can survive kernel-owned past `onFinished`'s FULL flag-clear, exactly like
  * [[IoUringOrphanHandshakeRecvRoutingTest]]'s `handshakeOwned=true` case.
  *
  * First guarantee (routing, covered by the sibling test's scope): the routing condition's 4th clause
  * (`!handshakeOwned && h.isUpgraded && !armedPostUpgrade`) correctly identifies this recv as stale instead of letting it fall through to
  * the ordinary TLS-feed branch, which would have read from `recvStagingFor` -- a buffer this recv's kernel write never touched (it targeted
  * `handle.readBuffer`, armed while `tls` was still Absent).
  *
  * Second guarantee (this test's actual scope): once identified as stale, staging the bytes as an `upgradeHandoff` Carryover is
  * correct only while a live `driveUpgradeRead` consumer could still drain it. But this recv reaps AFTER `onFinished` has ALREADY run to
  * completion (`h.tls` is `Present`): the handshake-driving fiber that used to consume `upgradeHandoff` is DONE and will never check the
  * slot again, so staging a Carryover here would silently lose these bytes forever, leaving a permanent gap in the ciphertext stream (the
  * actual "Closed at collect" mechanism: the engine desyncs on whatever chunk reaps next). The driver feeds these bytes to the engine directly
  * and delivers any resulting plaintext through `PosixHandle.inboundSink` instead.
  *
  * This test drives the exact reap-time state directly: arms a real, ordinary (non-handshakeOwned) recv on `acceptedH` while every upgrade
  * flag is still false (matching a real pre-upgrade `ReadPump` continuation), waits for the real SQE submit, then flips the handle's flags
  * through a full simulated upgrade lifecycle ending in the post-`onFinished` state (both flags false, `tls` Present) WHILE the recv is
  * still kernel-owned, and only then lets the peer's bytes reap it for real over io_uring. The sent bytes are not valid ciphertext for the
  * never-handshaked `serverEngine` (constructing a real paired handshake is exactly what the full-stack `IoUringMutualTlsStressTest`
  * exercises), so the observable outcome is a fatal-record teardown rather than delivered plaintext; the assertions that matter here are
  * that (a) `upgradeHandoff` NEVER becomes a `Carryover` (the bytes are never routed to the dead slot) and (b) the handle reaches a terminal
  * state (the new path was genuinely exercised, not silently skipped).
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

        "reaping after onFinished has fully run feeds the engine directly instead of staging a now-dead upgrade-handoff Carryover" in {
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

                            // Observe whether the new path is exercised: inboundSink is the delivery target for a successful decode (not
                            // reached here, since the bytes below are not valid ciphertext for this never-handshaked engine, but installing
                            // it confirms the code never falls back to completing `producer` or leaving the connection silently open).
                            val delivered = new java.util.concurrent.atomic.AtomicReference[Array[Byte]](null)
                            acceptedH.inboundSink = bytes => delivered.set(bytes.toArray)

                            val payload = Array[Byte](9, 8, 7, 6, 5)
                            assert(sock.sendNow(
                                client,
                                Buffer.fromArray[Byte](payload),
                                payload.length.toLong,
                                0
                            ).value == payload.length.toLong)

                            // Not valid TLS ciphertext for a never-handshaked engine: the expected outcome is a fatal-record teardown (the
                            // same `closeHandle` path IoUringMutualTlsStressTest's real handshakes exercise on genuinely bad data), not
                            // delivered plaintext. What this test actually pins is the ABSENCE of a mis-routed plaintext delivery.
                            awaitCondition(5.seconds)(acceptedH.isClosing()).map { closed =>
                                assert(
                                    closed,
                                    "stale recv's post-onFinished feed never reached a terminal state (a hang, not the fix under test)"
                                )
                                // The core regression guard: the bytes must NEVER be staged as a Carryover. Once onFinished has fully run, that
                                // slot has no consumer left, so staging it there would silently lose the bytes forever (the actual "Closed at
                                // collect" loss mechanism) instead of being fed to the engine (whatever the engine then does with them).
                                acceptedH.upgradeHandoff.get() match
                                    case _: PosixHandle.UpgradeHandoff.Carryover =>
                                        fail(s"stale recv's bytes were staged as a Carryover (slot=${acceptedH.upgradeHandoff.get()}) " +
                                            "instead of being fed to the engine -- they would be lost forever, no consumer remains")
                                    case _ => ()
                                end match
                                // The raw bytes aren't a valid TLS record for the never-handshaked engine, so the fatal-record path fires
                                // before any plaintext is produced: inboundSink must never see a delivery here.
                                assert(
                                    delivered.get() == null,
                                    s"unexpected plaintext delivered from an undecodable record: ${delivered.get().toSeq}"
                                )
                                // The stale recv's own throwaway producer promise must never complete: its bytes are delivered via
                                // inboundSink (or dropped on a fatal record), never through this recv's own promise.
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
