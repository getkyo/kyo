package kyo.net.internal.posix

import kyo.*
import kyo.net.Test

/** Reproduction + regression guard (Netty #7337 class) on [[PollerIoDriver]].
  *
  * Every TLS engine op for every connection on a driver routes through one per-driver engine FIFO drained by the poll-loop carrier (`submitEngineOp`
  * enqueues; `drainEngineOps`, called each cycle from `drainFifos` on the poll loop, runs each op to completion before the next). Running
  * `op()` with NO try/catch would let a throw from any engine op (a TLS shim call, a buffer copy, an OOM, a user continuation fired by
  * `promise.completeDiscard`) escape the tail-recursive loop, abandoning the rest of the cycle's queued ops: EVERY subsequent engine op for
  * EVERY connection on that driver would be enqueued and not drained that cycle, a multi-connection silent hang plus unbounded `engineQueue` growth.
  *
  * This leaf reproduces it directly: an engine op for "connection A" throws, then a normal engine op for "connection B" is submitted on the SAME
  * driver, and the leaf asserts B's op still runs (the drain survived A's throw and kept draining). The engine ops are plain FIFO thunks (no TLS
  * engine needed): the drain-death bug is in the FIFO drain loop, not in any engine, so a thunk that throws exercises the exact gap with no TLS
  * setup. Without the try/catch this FAILS for the right reason: A's throw escapes the drain loop and B never runs (the leaf times out at the deadlock
  * ceiling).
  *
  * Runs on every poller host (epoll on Linux, kqueue on macOS/BSD); the engine FIFO drains only on the poll-loop carrier, so the poll loop is
  * started (it bounded-waits on the idle poller fd and drains the engine queue each cycle).
  *
  * Anti-flakiness: B is submitted only AFTER A's throw is observed (the `aThrew` latch fires from inside A's op, on the drain carrier, before it
  * throws), so the ordering is deterministic with no race on which op the drain sees first. The leaf synchronizes on B's promise resolving (the real
  * drain) rather than a timer; `Async.timeout` is only the deadlock ceiling so a dead drain fails the test fast instead of hanging the suite. No
  * sleep, no busy-spin.
  */
class PollerIoDriverEngineThrowTest extends Test:

    import AllowUnsafe.embrace.danger

    private def assumePoller(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PollerIoDriver needs epoll (Linux) or kqueue (macOS/BSD)")

    /** Build a driver over a real epoll/kqueue backend with its poll loop started (the engine FIFO drains only on the poll-loop carrier), run `body`,
      * then close it. The poll loop bounded-waits on the idle poller fd (no fds registered) and drains the engine queue each cycle.
      */
    private def withDriver[A](body: PollerIoDriver => A < (Abort[Closed] & Async))(using Frame): A < (Abort[Closed] & Async) =
        val real     = PollerBackend.default()
        val pollerFd = real.create()
        val driver   = TestDrivers.forBackend(real, pollerFd)
        discard(driver.start())
        Sync.ensure(Sync.defer(driver.close()))(body(driver))
    end withDriver

    "PollerIoDriver engine FIFO worker resilience" - {

        "a throwing engine op (connection A) does not wedge the worker: a later op (connection B) still runs" in {
            assumePoller()
            withDriver { driver =>
                val aThrew = Promise.Unsafe.init[Unit, Any]()
                val bRan   = Promise.Unsafe.init[Unit, Any]()

                // Connection A's engine op: signal it is about to throw (from inside the op, on the drain carrier), then throw. A correct drain
                // catches this and keeps draining so B's op runs; a drain with no guard lets the throw escape the loop and B is never reached.
                val opA: () => Unit = () =>
                    aThrew.completeDiscard(Result.succeed(()))
                    throw new RuntimeException("engine op A failed (injected for the repro)")

                // Connection B's engine op: a normal op that completes its promise. It runs after A's throw; without the try/catch it never would.
                val opB: () => Unit = () => bRan.completeDiscard(Result.succeed(()))

                driver.submitEngineOp(opA)
                // Submit B only after A's throw is observed, so B is provably enqueued behind a throwing A (the worker-death window).
                aThrew.safe.get.map { _ =>
                    driver.submitEngineOp(opB)
                    Abort.run[Timeout](Async.timeout(5.seconds)(bRan.safe.get)).map {
                        case Result.Success(_) => succeed
                        case Result.Failure(_: Timeout) =>
                            fail(
                                "engine op B never ran: a throwing op A escaped the FIFO drain loop and abandoned the rest of the queue (Netty #7337)"
                            )
                        case other => fail(s"unexpected outcome: $other")
                    }
                }
            }
        }
    }

end PollerIoDriverEngineThrowTest
