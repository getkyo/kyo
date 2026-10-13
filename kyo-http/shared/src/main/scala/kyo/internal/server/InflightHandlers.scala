package kyo.internal.server

import kyo.*
import scala.annotation.tailrec

/** The handler fibers a server has running, and the wait its close owes them.
  *
  * A close interrupts request handlers through their connections, but an interrupt is asynchronous: the handler's finalizers run on its
  * own fiber after the close has moved on. The close completes only once this count drains, so a caller that sees the server closed also
  * sees every handler's finalizers done.
  *
  * The count and the closed flag share one word, `count << 1 | closed`, so admitting a handler and starting the close are each a single
  * atomic step. A handler admitted before the close is counted in the drain; one arriving after is refused rather than run uncounted.
  *
  * A WebSocket session is not interrupted by its connection's close: it sees the peer's close through its stream and must still run what
  * follows, such as reading the close reason. The server's own close interrupts it instead, so it is also kept in `sessions` until it
  * completes. `close` raises the flag before it reads the sessions and [[trackSession]] adds before it reads the flag, so a session
  * tracked as the close begins is interrupted by one side or the other.
  */
final private[kyo] class InflightHandlers(using AllowUnsafe):

    private val state    = AtomicLong.Unsafe.init(0L)
    private val drained  = Promise.Unsafe.init[Unit, Any]()
    private val sessions = AtomicRef.Unsafe.init(Set.empty[Fiber.Unsafe[Unit, Any]])

    /** Counts a handler about to start, unless the close has begun. A `true` must be followed by [[track]] or [[trackSession]] of the
      * handler's fiber.
      */
    @tailrec def enter(): Boolean =
        val current = state.get()
        if (current & 1L) != 0L then false
        else if state.compareAndSet(current, current + 2L) then true
        else enter()
        end if
    end enter

    /** Releases the handler counted by [[enter]] once its fiber completes, finalizers included. */
    def track(fiber: Fiber.Unsafe[Unit, Any])(using Frame): Unit =
        fiber.onComplete(_ => release())

    /** [[track]] for a session the server's close interrupts, since its connection's close does not. */
    def trackSession(fiber: Fiber.Unsafe[Unit, Any])(using Frame): Unit =
        discard(sessions.updateAndGet(_ + fiber))
        fiber.onComplete { _ =>
            discard(sessions.updateAndGet(_ - fiber))
            release()
        }
        if (state.get() & 1L) != 0L then interrupt(fiber)
    end trackSession

    /** Refuses every later handler, interrupts the sessions, and answers a fiber that completes once the counted handlers have. */
    def close()(using Frame): Fiber.Unsafe[Unit, Any] =
        if state.updateAndGet(_ | 1L) == 1L then drain()
        sessions.get().foreach(interrupt)
        drained
    end close

    private def interrupt(fiber: Fiber.Unsafe[Unit, Any])(using Frame): Unit =
        discard(fiber.interrupt(Result.Panic(UnsafeServerDispatch.HandlerConnectionClosed)))

    private def release(): Unit =
        if state.addAndGet(-2L) == 1L then drain()

    private def drain(): Unit = discard(drained.completeDiscard(Result.succeed(())))

end InflightHandlers
