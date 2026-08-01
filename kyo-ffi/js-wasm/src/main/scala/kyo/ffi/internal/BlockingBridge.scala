package kyo.ffi.internal

import kyo.*
import scala.collection.mutable
import scala.scalajs.js

/** Runtime bridge that lifts an asynchronous `@Ffi.blocking` koffi downcall into a `Fiber.Unsafe`.
  *
  * On JS the blocking C call cannot run on the carrier (the single Node event-loop thread): instead koffi dispatches it on a libuv worker
  * thread via `KoffiFacade.callAsync`, so the event loop is not blocked, and resolves the returned fiber from the completion callback. The
  * koffi `errno()` read inside that callback returns the errno of the async call (errno survives the async boundary), so the `marshal`
  * lambda supplied by the generated impl decodes the raw result and reads `KoffiFacade.errno()` exactly as the synchronous path does.
  *
  * Dispatches are METERED through [[BlockingMeter]]: koffi caps concurrently in-flight async calls at `max_async_calls` and THROWS when the
  * cap is exceeded (a single-macrotask burst of blocking calls, e.g. mass socket close at driver teardown, hits it). The meter admits at most
  * `kyo.ffi.maxBlockingCalls` dispatches at once and queues the rest, and `KoffiFacade` configures koffi's pool a factor above that, so the
  * meter (never koffi) is what applies backpressure and a burst becomes a queue instead of a throw. `runAsync` always returns immediately;
  * the returned fiber is the suspension.
  *
  * The consumer bridges to `< Async` with `.safe.get`; that is outside this bridge's scope.
  */
object BlockingBridge:

    /** Dispatch the `@Ffi.blocking` koffi downcall on a libuv worker and return a fiber resolved from the completion callback.
      *
      * The dispatch is submitted to [[BlockingMeter]]: it runs immediately if a permit is free, otherwise it is queued and dispatched when an
      * earlier call completes. Either way this returns immediately with the promise-backed fiber; call sites are synchronous unsafe-tier
      * (there is no `Async` context to suspend into), so the fiber IS the suspension.
      *
      * @param facade
      *   the per-trait koffi dispatch bag.
      * @param name
      *   the function key under which the koffi handle is stored in the bag.
      * @param args
      *   the marshalled argument array, identical to the synchronous call site's arg list.
      * @param marshal
      *   decodes the raw koffi result (and reads `KoffiFacade.errno()`) into the binding's return value. Invoked inside the completion
      *   callback on success.
      */
    def runAsync[A](facade: js.Dynamic, name: String, args: js.Array[js.Any], marshal: js.Any => A)(using
        AllowUnsafe
    ): Fiber.Unsafe[A, Any] =
        val p = Promise.Unsafe.init[A, Any]()
        def dispatch(): Unit =
            try
                KoffiFacade.callAsync(
                    facade,
                    name,
                    args,
                    (err, raw) =>
                        // Release the permit BEFORE completing the promise, so a continuation that immediately issues another
                        // @Ffi.blocking call sees the freed permit. release() admits the next queued dispatch (permit transfer) or
                        // decrements the in-flight count.
                        BlockingMeter.release()
                        if err != null && !js.isUndefined(err) then p.completeDiscard(Result.panic(js.JavaScriptException(err)))
                        else
                            // marshal can throw (e.g. a non-nullable Handle return that decodes to NULL throws FfiNullPointer). On
                            // JVM/Native that throw propagates synchronously out of the carrier call and is caught by the surrounding
                            // < Async evaluation; on JS the marshal runs inside this libuv completion callback, so a thrown exception
                            // would escape as an uncaught error and terminate the Node process, leaving the promise forever incomplete.
                            // Capture it into the fiber result (Result.apply -> Panic on throw) so .safe.get surfaces it as a failure the
                            // consumer can catch, matching the JVM/Native synchronous-throw outcome.
                            p.completeDiscard(Result(marshal(raw)))
                        end if
                )
            catch
                // A synchronous throw at dispatch (koffi pool exhausted despite headroom, or any koffi dispatch error): the completion
                // callback will never fire, so release the permit this dispatch was admitted on and complete the promise with the panic.
                // Uniform with the immediate and deferred paths; callers already handle a fiber Failure/Panic (a dispatch-time throw now
                // lands in the fiber rather than the caller's stack, and a deferred dispatch has no caller stack to throw into anyway).
                case t: Throwable =>
                    BlockingMeter.release()
                    p.completeDiscard(Result.panic(t))
            end try
        end dispatch
        BlockingMeter.submit(() => dispatch())
        p
    end runAsync

end BlockingBridge

/** Process-global in-flight meter for koffi async dispatches (JS/Wasm only).
  *
  * koffi's `max_async_calls` pool is one process-global limit shared by every `@Ffi.blocking` call, so the meter that protects it is global
  * too: a per-binding cap cannot bound the shared pool. JS/Wasm is single-threaded (koffi dispatch and completion delivery both run on the
  * Node main thread), so this needs no synchronization: a plain `Int` and a FIFO queue suffice.
  *
  * Admission is FIFO with no overtaking. One [[release]] admits at most one queued dispatch, transferring the permit rather than freeing it,
  * which bounds how long any queued dispatch (e.g. an io_uring reap re-arm queued behind a close burst) waits: at most the entries ahead of
  * it, each of which completes in bounded time. The bound is finalized by `Koffi.resolve` after it configures koffi's pool.
  */
private[ffi] object BlockingMeter:

    // Admitted concurrently-in-flight dispatch count and the FIFO queue of not-yet-admitted dispatch thunks.
    private var inFlight = 0
    private val pending  = mutable.Queue.empty[() => Unit]

    // Max concurrent admitted dispatches. Defaulted to the flag; `Koffi.resolve` sets the effective bound once, after configuring koffi's
    // pool (lowering it only when koffi rejected the config because a foreign koffi user loaded first).
    private var boundValue = kyo.ffi.maxBlockingCalls()

    // Highest in-flight count observed, for the regression test to assert the meter admitted at most the bound.
    private var peak = 0

    private[ffi] def bound: Int            = boundValue
    private[ffi] def bound_=(v: Int): Unit = boundValue = math.max(1, v)
    private[ffi] def peakInFlight: Int     = peak

    /** Admit `dispatch` now if under the bound, else enqueue it. Returns immediately either way. */
    private[ffi] def submit(dispatch: () => Unit): Unit =
        if inFlight < boundValue then
            inFlight += 1
            if inFlight > peak then peak = inFlight
            dispatch()
        else
            pending.enqueue(dispatch)
    end submit

    /** Called from a dispatch's completion (or its synchronous-throw catch) exactly once. Admits the next queued dispatch, transferring the
      * permit, or decrements the in-flight count when the queue is empty.
      */
    private[ffi] def release(): Unit =
        if pending.nonEmpty then pending.dequeue()()
        else inFlight -= 1
    end release

end BlockingMeter
