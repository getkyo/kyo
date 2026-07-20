package kyo.net.internal

import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.spi.SelectorProvider
import java.util.concurrent.atomic.AtomicBoolean as JAtomicBoolean
import kyo.*
import kyo.net.Test

/** Regression guard for exception containment in [[NioIoDriver]]'s select cycle.
  *
  * The loop runs as a scheduler task, one cycle per activation, each re-arming the next before returning. A `Throwable` escaping a cycle would
  * therefore end the chain outright: the worker hands an escaping exception to its uncaught handler and returns Done, so nothing re-arms. The
  * driver would be silently dead with its selector still open, every pending promise parked forever, and its done-fiber never completed.
  *
  * The cycle catches `Throwable` rather than only `NonFatal` (a silently dead driver is worse than a rethrow) and routes it to the same terminal
  * exit the closed path uses, which calls `close()`. This pins BOTH halves: the done-fiber completes as a panic AND the selector is released.
  * The second assertion is the one that matters, since before the loop ran on the scheduler a crash completed the promise with the selector
  * still open.
  */
class NioIoDriverCrashContainmentTest extends Test:

    import AllowUnsafe.embrace.danger

    /** A selector whose `select()` throws, so the failure lands inside the cycle body where containment must hold. Everything else is inert:
      * the cycle throws on its first statement, so nothing past `select` is reached, and `close()` is the one other call the terminal makes.
      */
    private class ThrowingSelector extends Selector:
        val closed                                    = new JAtomicBoolean(false)
        def isOpen: Boolean                           = !closed.get()
        def provider: SelectorProvider                = SelectorProvider.provider()
        def keys: java.util.Set[SelectionKey]         = java.util.Collections.emptySet()
        def selectedKeys: java.util.Set[SelectionKey] = java.util.Collections.emptySet()
        def selectNow(): Int                          = throw new IllegalStateException("injected select failure")
        def select(timeout: Long): Int                = throw new IllegalStateException("injected select failure")
        def select(): Int                             = throw new IllegalStateException("injected select failure")
        def wakeup(): Selector                        = this
        def close(): Unit                             = discard(closed.set(true))
    end ThrowingSelector

    "NioIoDriver select-cycle crash containment" - {
        "a throw inside a select cycle completes the done-fiber as a panic and still closes the selector" in {
            val selector = new ThrowingSelector
            val driver   = NioIoDriver.forSelector(selector)
            val done     = driver.start()

            // Without containment this get would hang: the chain would be gone with the promise never completed.
            done.safe.getResult.map { result =>
                assert(
                    result.isPanic,
                    s"a Throwable escaping a select cycle must complete the done-fiber as a panic, got: $result"
                )
                // The teardown proof. A crash previously completed the promise with the selector still open, which is the leak
                // documented for this driver; routing the crash through the terminal exit is what closes it.
                assert(
                    selector.closed.get(),
                    "the terminal exit must close the selector even when the cycle crashed, or a crashed driver leaks it"
                )
                succeed
            }
        }
    }

end NioIoDriverCrashContainmentTest
