package kyo

import kyo.scheduler.IOPromise
import scala.annotation.tailrec

/** A synchronization primitive that allows a fixed number of parties to wait for each other to reach a common point of execution.
  *
  *   - The Barrier is initialized with a specific number of parties. Each party calls `await` when it reaches the barrier point.
  *   - The barrier releases all waiting parties when the last party arrives.
  *   - The barrier can only be used once. After all parties have been released, the barrier cannot be reset.
  */
final case class Barrier private (unsafe: Barrier.Unsafe) extends AnyVal:

    /** Waits for the barrier to be released.
      *
      * @return
      *   Unit
      */
    def await(using Frame): Unit < Async = IO.Unsafe(unsafe.await().safe.get)

    /** Returns the number of parties still waiting at the barrier.
      *
      * @return
      *   The number of waiting parties
      */
    def pending(using Frame): Int < IO = IO.Unsafe(unsafe.pending())

end Barrier

object Barrier:

    /** Creates a new Barrier instance.
      *
      * @param n
      *   The number of parties that must call await before the barrier is released
      * @return
      *   A new Barrier instance
      */
    def init(n: Int)(using Frame): Barrier < IO = IO.Unsafe(Barrier(Unsafe.init(n)))

    /* WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    sealed abstract class Unsafe:
        def await()(using AllowUnsafe): Fiber.Unsafe[Nothing, Unit]
        def pending()(using AllowUnsafe): Int
        def safe: Barrier = Barrier(this)
    end Unsafe

    /* WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:

        val noop = new Unsafe:
            def await()(using AllowUnsafe)   = Fiber.unit.unsafe
            def pending()(using AllowUnsafe) = 0

        def init(n: Int)(using AllowUnsafe): Unsafe =
            if n <= 0 then noop
            else
                new Unsafe:
                    val promise = Promise.Unsafe.init[Nothing, Unit]()
                    val count   = AtomicInt.Unsafe.init(n)

                    def await()(using AllowUnsafe) =
                        @tailrec def loop(c: Int): Fiber.Unsafe[Nothing, Unit] =
                            if c > 0 && !count.cas(c, c - 1) then
                                loop(count.get())
                            else
                                if c == 1 then promise.completeUnit(Result.unit)
                                Fiber.Unsafe.fromPromise(promise)
                        loop(count.get())
                    end await

                    def pending()(using AllowUnsafe) = count.get()
    end Unsafe
end Barrier
