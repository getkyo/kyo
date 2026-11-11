package kyo.internal.server

import kyo.*
import kyo.kernel.ContextEffect
import scala.annotation.tailrec

/** The handler fibers a server has running, and the wait its close owes them.
  *
  * A close interrupts request handlers through their connections, but an interrupt is asynchronous: handler cleanup can outlive the
  * interrupted fiber. The close completes only once this count drains, so a caller that sees the server closed also
  * sees every handler's finalizers done.
  *
  * The count and the closed flag share one word, `count << 1 | closed`, so admitting a handler and starting the close are each a single
  * atomic step. A handler admitted before the close is counted in the drain; one arriving after is refused rather than run uncounted.
  *
  * A WebSocket session is not interrupted by its connection's close: it sees the peer's close through its stream and must still run what
  * follows, such as reading the close reason. The server's own close interrupts it instead, so it is also kept in `sessions` until it
  * completes. `close` raises the flag before it reads the sessions and `start` adds before it reads the flag, so a session
  * tracked as the close begins is interrupted by one side or the other.
  */
final private[kyo] class InflightHandlers private (
    state: AtomicLong.Unsafe,
    drained: Promise.Unsafe[Unit, Any],
    sessions: AtomicRef.Unsafe[Set[Fiber.Unsafe[Unit, Any]]]
):

    /** Starts and owns a handler unless close has begun. Only sessions need interruption here; requests belong to their connections. */
    def start(handler: => Unit < (Async & Scope), isSession: Boolean)(using Frame, AllowUnsafe): Maybe[Fiber.Unsafe[Unit, Any]] =
        if !enter() then Absent
        else
            val scope = AtomicRef.Unsafe.init[Maybe[Scope.Finalizer]](Absent)
            val fiber = Fiber.Unsafe.init {
                Scope.run {
                    ContextEffect.suspendWith(Tag[Scope]) { finalizer =>
                        Sync.Unsafe.defer {
                            scope.set(Present(finalizer))
                            handler
                        }
                    }
                }
            }
            if isSession then discard(sessions.updateAndGet(_ + fiber))
            fiber.onComplete { result =>
                if isSession then discard(sessions.updateAndGet(_ - fiber))
                result match
                    case Result.Success(_) => release()
                    case _                 =>
                        // An interrupted Scope.run starts its drain but cannot await it on the interrupted fiber.
                        // Retain the root created on this same handler fiber, including its nested scopes, until it drains.
                        scope.get() match
                            case Present(finalizer) => Fiber.Unsafe.init(finalizer.await).onComplete(_ => release())
                            case Absent             => release() // Interrupted before the body could acquire any resources.
                end match
            }
            if isSession && (state.get() & 1L) != 0L then interrupt(fiber)
            Present(fiber)
        end if
    end start

    @tailrec private def enter()(using AllowUnsafe): Boolean =
        val current = state.get()
        if (current & 1L) != 0L then false
        else if state.compareAndSet(current, current + 2L) then true
        else enter()
        end if
    end enter

    /** Refuses every later handler, interrupts the sessions, and answers a fiber that completes once the counted handlers have. */
    def close()(using Frame, AllowUnsafe): Fiber.Unsafe[Unit, Any] =
        if state.updateAndGet(_ | 1L) == 1L then drain()
        sessions.get().foreach(interrupt)
        drained
    end close

    private def interrupt(fiber: Fiber.Unsafe[Unit, Any])(using Frame, AllowUnsafe): Unit =
        discard(fiber.interrupt(Result.Panic(UnsafeServerDispatch.HandlerConnectionClosed)))

    private def release()(using AllowUnsafe): Unit =
        if state.addAndGet(-2L) == 1L then drain()

    private def drain()(using AllowUnsafe): Unit = discard(drained.completeDiscard(Result.succeed(())))

end InflightHandlers

private[kyo] object InflightHandlers:

    def init()(using AllowUnsafe): InflightHandlers =
        new InflightHandlers(
            AtomicLong.Unsafe.init(0L),
            Promise.Unsafe.initUninterruptible[Unit, Any](),
            AtomicRef.Unsafe.init(Set.empty[Fiber.Unsafe[Unit, Any]])
        )

end InflightHandlers
