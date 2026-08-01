package kyo.ffi.it

import kyo.*
import kyo.ffi.Ffi
import kyo.ffi.internal.BlockingMeter

/** JS-only regression guard for the `@Ffi.blocking` dispatch meter (`kyo.ffi.internal.BlockingMeter`).
  *
  * Reproduces the koffi pool-exhaustion crash: koffi caps concurrently in-flight async calls at `max_async_calls` and throws "Too many
  * asynchronous calls are running" synchronously at dispatch when the cap is exceeded. Firing a burst of `@Ffi.blocking` calls in a single
  * macrotask (as an io_uring driver does when it mass-closes sockets at teardown) hit that cap and tore the ring down. Before the meter this
  * burst throws at ~the 257th call (koffi's default 256 pool); with the meter every call is admitted-or-queued and none throws, and the
  * observed peak in-flight count stays at or below the configured bound -- proving it is the meter, not merely a raised pool, that holds.
  */
class BlockingMeterTest extends ItTestBase:

    "@Ffi.blocking dispatch meter" - {
        "a single-macrotask burst far beyond koffi's pool is metered, not thrown" in {
            import AllowUnsafe.embrace.danger
            val b = Ffi.load[ItStructsBindings]
            // Initialize the impl companion (which registers the `Circle` koffi struct) via a struct-PARAM call first. A struct-RETURNING
            // method calls `outStruct("Circle")` before it touches the companion `facade`, so as the very first call it would run before
            // `Circle` is registered; a struct-param method touches `facade` first, registering the struct. (Pre-existing codegen ordering,
            // unrelated to the meter; kyo-net's blocking bindings return primitives so it never hits this.)
            val _ = b.kyoItCircleArea(Circle(Center(0.0, 0.0), 1.0))
            // Well above the configured koffi pool (maxBlockingCalls * factor), so a passing run proves the meter queued the excess rather
            // than the pool having absorbed it.
            val burst = 2000
            // Fire every dispatch synchronously in ONE macrotask (no await between calls), the exact shape that exhausts the pool.
            val fibers: Seq[Fiber.Unsafe[Circle, Any]] =
                (1 to burst).map(_ => b.kyoItMakeCircleBlocking(1.0, 2.0, 3.0))
            Async.foreach(fibers)(_.safe.get).map { results =>
                assert(results.size == burst)
                assert(results.forall(_ == Circle(Center(1.0, 2.0), 3.0)))
                assert(BlockingMeter.peakInFlight >= 1)
                assert(BlockingMeter.peakInFlight <= kyo.ffi.maxBlockingCalls())
            }
        }
    }

end BlockingMeterTest
