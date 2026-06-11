package kyo.ffi.internal

import kyo.*

/** Runtime bridge that lifts a synchronous `@Ffi.blocking` downcall into a `Fiber.Unsafe`.
  *
  * On JVM and Native the blocking C call runs synchronously on the carrier thread that invokes the binding method. The `@Ffi.blocking`
  * downcall (the JVM safe, non-critical Panama downcall or the Scala Native `@blocking @extern` call) lets the GC and the Kyo scheduler's
  * `BlockingMonitor` recognise that the carrier is parked in a kernel operation: the monitor stops routing new tasks to the blocked carrier
  * and drains its queue to other workers, so a genuinely-blocking syscall does not permanently starve the scheduler. This matches the
  * existing `NativeIoDriver.pollOnce`, which calls the `@blocking` `epoll_wait` / `kqueue` downcalls directly on the carrier rather than
  * offloading to a pool.
  *
  * Because the call is already complete by the time `run` returns, the bridge wraps the result in an already-completed `Promise.Unsafe`
  * (which is a `Fiber.Unsafe`). The consumer bridges to `< Async` with `.safe.get`; that is outside this bridge's scope.
  */
object BlockingBridge:

    /** Run the `@Ffi.blocking` downcall on the carrier thread and return an already-completed fiber carrying its result.
      *
      * `thunk` is the synchronous downcall together with its errno read and result marshalling: it runs here, on the carrier, while the
      * `@blocking` downcall parks the thread. The completed `Promise.Unsafe` is returned as a `Fiber.Unsafe[A, Any]`.
      */
    def run[A](thunk: => A)(using AllowUnsafe): Fiber.Unsafe[A, Any] =
        val p = Promise.Unsafe.init[A, Any]()
        p.completeDiscard(Result.succeed(thunk))
        p
    end run

end BlockingBridge
