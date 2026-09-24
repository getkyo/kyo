package kyo.internal.server

import kyo.*
import scala.annotation.tailrec

/** The handler fibers a server has running, and the wait its close owes them.
  *
  * A close interrupts handlers through their connections, but an interrupt is asynchronous: the handler's finalizers run on its own
  * fiber after the close has moved on. The close completes only once this count drains, so a caller that sees the server closed also
  * sees every handler's finalizers done.
  *
  * The count and the closed flag share one word, `count << 1 | closed`, so admitting a handler and starting the close are each a single
  * atomic step. A handler admitted before the close is counted in the drain; one arriving after is refused rather than run uncounted.
  */
final private[kyo] class InflightHandlers(using AllowUnsafe):

    private val state   = AtomicLong.Unsafe.init(0L)
    private val drained = Promise.Unsafe.init[Unit, Any]()

    /** Counts a handler about to start, unless the close has begun. A `true` must be followed by [[track]] of the handler's fiber. */
    @tailrec def enter(): Boolean =
        val current = state.get()
        if (current & 1L) != 0L then false
        else if state.compareAndSet(current, current + 2L) then true
        else enter()
        end if
    end enter

    /** Releases the handler counted by [[enter]] once its fiber completes, finalizers included. */
    def track(fiber: Fiber.Unsafe[?, ?])(using Frame): Unit =
        fiber.onComplete { _ =>
            if state.addAndGet(-2L) == 1L then drain()
        }

    /** Refuses every later handler and answers a fiber that completes once the counted ones have. */
    def close(): Fiber.Unsafe[Unit, Any] =
        if state.updateAndGet(_ | 1L) == 1L then drain()
        drained
    end close

    private def drain(): Unit = discard(drained.completeDiscard(Result.succeed(())))

end InflightHandlers
