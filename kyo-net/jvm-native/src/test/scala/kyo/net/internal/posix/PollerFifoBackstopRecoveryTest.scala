package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome

/** Deterministic guard for the poll-loop FIFO drain ([[PollerIoDriver.drainFifos]]), the recovery for the Native TLS lost-wakeup deadlock.
  *
  * The per-driver change FIFO and engine FIFO used to be drained by a `Fiber.Unsafe.init` task spawned per submission burst. If a scheduler strand
  * lost that one task, the FIFO sat undrained forever: the fd was never re-armed and the connection deadlocked (a lost wakeup, not a data race). The
  * fix makes the always-running poll-loop carrier the single authoritative consumer of both FIFOs (mirroring how [[IoUringDriver]] drains its engine
  * FIFO on its reap loop): `submitChange` / `submitEngineOp` only enqueue, and the poll loop drains both via `drainFifos` once per cycle, so a
  * submitted command or engine op is always drained within one poll cycle and there is no ephemeral spawned task to lose.
  *
  * The existing coverage of the deadlock is the probabilistic Native TLS load test (`PosixTransportTlsConcurrentEchoTest`), which only
  * `[STUCK]`/`[TIMEOUT]`s and never pins the recovery. These leaves pin it directly, mirroring how [[ChangeFifoOrderingTest]] and
  * [[EngineFifoSingleOwnerTest]] pin the FIFO single-owner contract directly rather than under load: build a driver over a real epoll/kqueue backend
  * through a [[RecordingPollerBackend]] spy whose poll loop is NEVER started, enqueue work via the public API, assert it is NOT drained (no poll loop,
  * no spawned task: this is exactly the stranded state the old design could wedge in permanently), then run one poll cycle's drain via `drainFifos()`
  * and assert the work executed. Without the poll-loop drain the work would sit forever; one `drainFifos()` recovers it. No sleep.
  *
  * Gate: `PosixTestSockets.assumePoller()` (real loopback pair for a real fd, as in [[ChangeFifoOrderingTest]]).
  */
class PollerFifoBackstopRecoveryTest extends Test:

    import AllowUnsafe.embrace.danger

    "poll-loop FIFO drain recovers work that no spawned task drains" - {
        "a change command sits undrained with no poll loop, then one poll cycle's drain registers the fd" in {
            PosixTestSockets.assumePoller()
            PosixTestSockets.loopbackPair().map { case (clientFd, acceptedFd) =>
                val targetFd = acceptedFd
                val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
                val real     = PollerBackend.default()
                val pollerFd = real.create()
                val backend  = RecordingPollerBackend(real)
                val driver   = TestDrivers.forBackend(backend, pollerFd, spy)

                // Arm a read through the public path: awaitRead -> submitChange enqueues an OpRegisterRead and returns. No poll loop is started and
                // submitChange no longer spawns a drain task, so nothing drains the FIFO yet (the stranded state the old spawn-loss could wedge in).
                val readPromise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                driver.awaitRead(PosixHandle.socket(targetFd, PosixHandle.DefaultReadBufferSize, Absent), readPromise)

                assert(
                    !backend.callLog.contains(s"registerRead($targetFd)"),
                    s"the change must sit undrained before the poll-loop drain runs: ${backend.callLog}"
                )

                // One poll cycle's drain on the poll-loop carrier (here, this carrier) drains the change FIFO in submission order.
                driver.drainFifos()

                // registeredRead(targetFd) is completed by the change drain when registerRead actually runs; the drain ran it, so this is already
                // complete. Synchronizing on it (no sleep) proves the poll-loop drain executed the stranded command.
                backend.registeredRead(targetFd).safe.get.map { _ =>
                    driver.close()
                    PosixTestSockets.closePeerForEof(spy, clientFd)
                    PosixTestSockets.closePeerForEof(spy, acceptedFd)
                    assert(
                        backend.callLog.contains(s"registerRead($targetFd)"),
                        s"the poll-loop drain must have run the stranded registerRead: ${backend.callLog}"
                    )
                }
            }
        }

        "an engine op sits undrained with no poll loop, then one poll cycle's drain runs it" in {
            PosixTestSockets.assumePoller()
            PosixTestSockets.loopbackPair().map { case (clientFd, acceptedFd) =>
                val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
                val real     = PollerBackend.default()
                val pollerFd = real.create()
                val backend  = RecordingPollerBackend(real)
                val driver   = TestDrivers.forBackend(backend, pollerFd, spy)

                val opRan = Promise.Unsafe.init[Unit, Any]()
                val ran   = new java.util.concurrent.atomic.AtomicBoolean(false)

                // submitEngineOp enqueues and returns; with no poll loop started and no spawned task, nothing runs the op yet.
                driver.submitEngineOp { () =>
                    ran.set(true)
                    opRan.completeDiscard(Result.succeed(()))
                }

                assert(!ran.get(), "the engine op must sit undrained before the poll-loop drain runs")

                // One poll cycle's drain runs the engine op on the poll-loop carrier (here, this carrier).
                driver.drainFifos()

                opRan.safe.get.map { _ =>
                    driver.close()
                    PosixTestSockets.closePeerForEof(spy, clientFd)
                    PosixTestSockets.closePeerForEof(spy, acceptedFd)
                    assert(ran.get(), "the poll-loop drain must have run the stranded engine op")
                }
            }
        }
    }

end PollerFifoBackstopRecoveryTest
