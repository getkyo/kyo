package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.Test

/** Regression guard for engine ops offered to [[PollerIoDriver]] after its poll loop is gone.
  *
  * While the loop runs it is the engine FIFO's only consumer: every op is drained once per cycle, in order. After the terminal teardown there
  * is no consumer at all, and the wake `submitEngineOp` fires has no loop left to wake, so an op landing then would sit in the queue forever.
  * That is not hypothetical: a listener-close discharge routes its teardown through this FIFO, so the stranded op would be the one that frees
  * a TLS engine and closes an fd. `IoUringDriver.submitEngineOp` has always rechecked for this; the poller did not.
  *
  * Driven through the never-started driver, which tears down synchronously inside `close()` (no poll carrier to defer to). That reaches the
  * post-teardown state deterministically, with no race against a live loop, the same route
  * [[IoUringDriverPostTeardownCloseTest]] uses for the ring.
  */
class PollerIoDriverPostTeardownEngineOpTest extends Test:

    import AllowUnsafe.embrace.danger

    "PollerIoDriver post-teardown engine op" - {

        "an engine op offered after the terminal teardown still runs, instead of stranding in a queue nothing drains" in {
            PosixTestSockets.assumePoller()
            val backend  = PollerBackend.default()
            val pollerFd = backend.create()
            val driver   = TestDrivers.forBackend(backend, pollerFd, Ffi.load[SocketBindings])
            // No start(): close() then tears down synchronously, so teardownComplete is true when it returns.
            driver.close()

            val ran = AtomicBoolean.Unsafe.init(false)
            driver.submitEngineOp(() => ran.set(true))
            assert(
                ran.get(),
                "an engine op offered after the poll loop's terminal teardown must be drained by the offering carrier: nothing else will " +
                    "ever drain it, so a listener-close discharge queued here would never free its engine or close its fd"
            )
            succeed
        }

        "a re-entrant op offered from inside a late drain is drained by that same pass, not deadlocked against its own claim" in {
            PosixTestSockets.assumePoller()
            val backend  = PollerBackend.default()
            val pollerFd = backend.create()
            val driver   = TestDrivers.forBackend(backend, pollerFd, Ffi.load[SocketBindings])
            driver.close()

            val outerRan = AtomicBoolean.Unsafe.init(false)
            val innerRan = AtomicBoolean.Unsafe.init(false)
            // engineFreeSink routes exactly this shape: an op that submits another op while being drained. The drain pass must pick the
            // inner one up rather than the inner submit spinning on the claim its own carrier already holds.
            driver.submitEngineOp { () =>
                outerRan.set(true)
                driver.submitEngineOp(() => innerRan.set(true))
            }
            assert(outerRan.get(), "the outer op must have been drained by the offering carrier")
            assert(innerRan.get(), "the op the outer one submitted must be drained by the same pass, not stranded or deadlocked")
            succeed
        }
    }

end PollerIoDriverPostTeardownEngineOpTest
