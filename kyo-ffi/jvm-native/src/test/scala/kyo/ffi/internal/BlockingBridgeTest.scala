package kyo.ffi.internal

import kyo.*
import kyo.ffi.Test

/** Regression coverage for the foundational `@Ffi.blocking` contract on JVM and Native.
  *
  * A `@Ffi.blocking` binding method is generated to wrap its synchronous downcall in [[BlockingBridge.run]], which returns a
  * `Fiber.Unsafe[A, Any]` rather than a bare value. The caller MUST await that fiber (`.safe.get`, or the synchronous `.block` used here)
  * and MUST NOT assume it is already completed. These tests pin the JVM/Native side of that contract: `run` lifts a thunk into a fiber, the
  * thunk runs exactly once on the calling carrier, and the result is delivered both synchronously (via `block`) and asynchronously (via
  * `onComplete`). The JS side (`runAsync` on a libuv worker) is exercised by the codegen emitter golden tests and the epoll/kqueue
  * integration suites.
  *
  * Lives in the `jvm-native` test set: both platforms share [[BlockingBridge.run]] there, while JS has its own `runAsync` bridge.
  */
class BlockingBridgeTest extends Test:

    import AllowUnsafe.embrace.danger

    private def deadline(after: Duration = Duration.fromJava(java.time.Duration.ofSeconds(5))): Clock.Deadline.Unsafe =
        Clock.live.unsafe.deadline(after)

    "run lifts a thunk into a Fiber.Unsafe carrying the result" in {
        val fiber = BlockingBridge.run(7 + 8)
        // The caller awaits the fiber rather than reading a bare value. block on an
        // already-completed fiber returns immediately on JVM/Native.
        assert(fiber.block(deadline()).map(_.eval) == Result.succeed(15))
    }

    "the thunk runs exactly once" in {
        var calls = 0
        val fiber = BlockingBridge.run {
            calls += 1
            "done"
        }
        assert(fiber.block(deadline()).map(_.eval) == Result.succeed("done"))
        assert(calls == 1)
    }

    "the result is delivered through onComplete (await, not assume-completed poll)" in {
        var observed: Result[Any, Int] = Result.succeed(-1)
        val fiber                      = BlockingBridge.run(42)
        fiber.onComplete(r => observed = r.map(_.eval))
        assert(observed == Result.succeed(42))
    }

    "a Unit-returning blocking call surfaces as a fiber of Unit" in {
        var sideEffect = 0
        val fiber: Fiber.Unsafe[Unit, Any] = BlockingBridge.run {
            sideEffect = 99
            ()
        }
        assert(fiber.block(deadline()).map(_.eval) == Result.succeed(()))
        assert(sideEffect == 99)
    }

    "the carrier thread runs the thunk inline (already complete by the time run returns)" in {
        val runner      = Thread.currentThread()
        var ran: Thread = null
        val fiber = BlockingBridge.run {
            ran = Thread.currentThread()
            1
        }
        // JVM/Native run the blocking downcall synchronously on the calling carrier,
        // so the thunk has already executed on this thread before run returns.
        assert(ran eq runner)
        assert(fiber.block(deadline()).map(_.eval) == Result.succeed(1))
    }
end BlockingBridgeTest
