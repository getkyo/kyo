package kyo.net.internal.posix

import kyo.*
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** Reproduce-first coverage for the STARTTLS detach-vs-read strands on the poller backends: a pump read promise the upgrade detach cannot
  * see must still be failed, never stranded. The upgrade order (PosixTransport.upgradeToTls) is `upgradeActive`/`upgrading` set, then
  * detachForUpgrade (the default driver `cancel`, whose promise sweep reads `pendingReadPromise`), then `isUpgraded` set. Three promises
  * escape that sweep:
  *
  *   - a stray pump re-arm DEPOSITED AFTER the sweep ran (the registration apply rejects it while `upgradeActive && !handshakeReading`, and
  *     the rejection deliberately does not fail it, deferring to a sweep that already happened);
  *   - a pump read CONSUMED BY THE UPGRADE WINDOW: a dispatch under `upgradeActive` clears `pendingReadPromise`, routes the peer flight into
  *     the upgrade handoff, and never completes the promise, so the later sweep finds Absent;
  *   - a stray deposit IN THE GAP between the sweep and the `isUpgraded` write, which only the handshake's first producer arm can observe.
  *
  * Each leaf pins one shape with the exact production ordering; a bounded await that never completes is the strand. The pump parked on such a
  * promise is a fiber nothing ever resumes, invisible to the leak gates (no map entry, no open-fd owner), which is why these are driver-level
  * contract tests rather than transport-observable ones.
  */
class PollerIoDriverUpgradeDetachTest extends Test:

    import AllowUnsafe.embrace.danger

    private def assumePoller(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PollerIoDriver needs epoll (Linux) or kqueue (macOS/BSD)")

    private def sock = kyo.ffi.Ffi.load[SocketBindings]

    /** Poll `cond` on the fiber scheduler (never a thread block) until it holds or `bound` elapses; returns whether it held. */
    private def awaitCondition(bound: Duration)(cond: => Boolean)(using Frame): Boolean < Async =
        val deadline = java.lang.System.nanoTime() + bound.toNanos
        Loop(()) { _ =>
            if cond then Loop.done(true)
            else if java.lang.System.nanoTime() >= deadline then Loop.done(false)
            else Async.sleep(2.millis).andThen(Loop.continue(()))
        }
    end awaitCondition

    /** Bounded await on a read promise: Present(result) when it completes, Absent when nothing completes it within `bound` (the strand). */
    private def awaitOutcome(p: Promise.Unsafe[ReadOutcome, Abort[Closed]], bound: Duration)(using
        Frame,
        kyo.test.AssertScope
    ): Maybe[Result[Closed, ReadOutcome]] < Async =
        Abort.run[Closed | Timeout](Async.timeout(bound)(p.safe.get)).map {
            case Result.Success(outcome)        => Present(Result.succeed(outcome))
            case Result.Failure(_: Timeout)     => Absent
            case Result.Failure(closed: Closed) => Present(Result.fail(closed))
            case Result.Panic(e)                => Present(Result.panic(e))
        }

    "an awaitRead deposited after the upgrade sweep is failed, not stranded" in {
        assumePoller()
        val driver = PollerIoDriver.init()
        discard(driver.start())
        Sync.ensure(Sync.defer(driver.close())) {
            PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                val handle = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                Sync.ensure(Sync.defer {
                    driver.closeHandle(handle)
                    discard(sock.close(client).poll())
                }) {
                    // The transport order: flags, then the detach sweep, then the durable marker. The stray pump re-arm lands strictly
                    // after all of it (the pump's re-arm is deliberately ungated at the transport layer).
                    handle.upgradeActive = true
                    handle.upgrading = true
                    driver.cancel(handle)
                    handle.isUpgraded = true
                    val p = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                    driver.awaitRead(handle, p)
                    awaitOutcome(p, 2.seconds).map {
                        case Present(Result.Failure(_)) => succeed
                        case Absent =>
                            assert(
                                false,
                                "read deposited after the upgrade sweep was stranded: nothing completed it " +
                                    s"(pendingReadPromise=${handle.pendingReadPromise.isDefined}, upgradeActive=${handle.upgradeActive})"
                            )
                        case other =>
                            assert(false, s"unexpected outcome $other")
                    }
                }
            }
        }
    }

    "a pump read consumed by the upgrade window is failed, not stranded" in {
        assumePoller()
        val driver = PollerIoDriver.init()
        discard(driver.start())
        Sync.ensure(Sync.defer(driver.close())) {
            PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                val handle  = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                val clientH = PosixHandle.socket(client, PosixHandle.DefaultReadBufferSize, Absent)
                val flight  = Array[Byte](22, 33, 44)
                Sync.ensure(Sync.defer {
                    driver.closeHandle(handle)
                    discard(sock.close(client).poll())
                }) {
                    // A legitimately armed pump read, then the upgrade window opens and the peer's first flight arrives BEFORE the detach
                    // sweep runs (the server-side STARTTLS shape: the flight rides right behind the negotiation byte). The dispatch routes
                    // the flight into the upgrade handoff and consumes the promise out of pendingReadPromise, so the sweep finds Absent.
                    val p = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                    driver.awaitRead(handle, p)
                    handle.upgradeActive = true
                    handle.upgrading = true
                    assert(driver.write(clientH, Span.fromUnsafe(flight), 0) == WriteResult.Done)
                    awaitCondition(2.seconds) {
                        handle.upgradeHandoff.get() match
                            case PosixHandle.UpgradeHandoff.Carryover(bytes) => bytes.length == flight.length
                            case _                                           => false
                    }.map { consumed =>
                        assert(
                            consumed,
                            "the upgrade-window dispatch never routed the flight into the handoff " +
                                s"(handoff=${handle.upgradeHandoff.get()}, pendingReadPromise=${handle.pendingReadPromise.isDefined})"
                        )
                    }.andThen {
                        driver.cancel(handle)
                        handle.isUpgraded = true
                        awaitOutcome(p, 2.seconds).map {
                            case Present(Result.Failure(_)) =>
                                // The flight itself must survive in the handoff for the handshake to replay: failing the pump promise is
                                // teardown, not data loss.
                                handle.upgradeHandoff.get() match
                                    case PosixHandle.UpgradeHandoff.Carryover(bytes) =>
                                        assert(bytes.toList == flight.toList, s"handoff bytes ${bytes.toList} != ${flight.toList}")
                                    case other =>
                                        assert(false, s"the flight must stay staged in the handoff; got $other")
                            case Absent =>
                                assert(
                                    false,
                                    "pump read consumed by the upgrade window was stranded: the sweep missed it " +
                                        s"(pendingReadPromise=${handle.pendingReadPromise.isDefined})"
                                )
                            case other =>
                                assert(false, s"unexpected outcome $other")
                        }
                    }
                }
            }
        }
    }

    "a stray deposit in the sweep-to-marker gap is failed by the first producer arm" in {
        assumePoller()
        val driver = PollerIoDriver.init()
        discard(driver.start())
        Sync.ensure(Sync.defer(driver.close())) {
            PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                val handle = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                Sync.ensure(Sync.defer {
                    driver.closeHandle(handle)
                    discard(sock.close(client).poll())
                }) {
                    // The stray deposit lands after the sweep but BEFORE the isUpgraded write: the self-re-check cannot see the marker yet,
                    // so the only sweep left is the handshake's first armUpgradeProducerRead, which always runs (every handshake parks at
                    // least one read) and must fail the occupant it replaces.
                    handle.upgradeActive = true
                    handle.upgrading = true
                    driver.cancel(handle)
                    val p = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                    driver.awaitRead(handle, p)
                    handle.isUpgraded = true
                    driver.armUpgradeProducerRead(handle)
                    awaitOutcome(p, 2.seconds).map {
                        case Present(Result.Failure(_)) => succeed
                        case Absent =>
                            assert(
                                false,
                                "stray deposit in the sweep-to-marker gap was stranded: the producer arm did not fail the occupant " +
                                    s"(pendingReadPromise=${handle.pendingReadPromise.isDefined})"
                            )
                        case other =>
                            assert(false, s"unexpected outcome $other")
                    }
                }
            }
        }
    }

end PollerIoDriverUpgradeDetachTest
