package kyo

import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec

/** A synchronization primitive that allows one or more tasks to wait until a set of operations being performed in other tasks completes.
  *
  * A `Latch` is initialized with a count and can be awaited. It is released by calling `release` the specified number of times.
  */
final case class Latch private (unsafe: Latch.Unsafe):

    /** Waits until the latch has counted down to zero.
      *
      * @return
      *   Unit wrapped in an Async effect
      */
    def await(using Frame): Unit < Async = IO.Unsafe(unsafe.await().safe.get)

    /** Decrements the count of the latch, releasing it if the count reaches zero.
      *
      * @return
      *   Unit wrapped in an IO effect
      */
    def release(using Frame): Unit < IO = IO.Unsafe(unsafe.release())

    /** Returns the current count of the latch.
      *
      * @return
      *   The current count wrapped in an IO effect
      */
    def pending(using Frame): Int < IO = IO.Unsafe(unsafe.pending())

end Latch

/** Companion object for creating Latch instances. */
object Latch:

    /** Creates a new Latch initialized with the given count.
      *
      * @param n
      *   The initial count for the latch
      * @return
      *   A new Latch instance wrapped in an IO effect
      */
    def init(n: Int)(using Frame): Latch < IO = IO.Unsafe(Latch(Unsafe.init(n)))

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    sealed abstract class Unsafe:
        def await()(using AllowUnsafe): Fiber.Unsafe[Nothing, Unit]
        def release()(using AllowUnsafe): Unit
        def pending()(using AllowUnsafe): Int
        def safe: Latch = Latch(this)
    end Unsafe

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:

        val noop = new Unsafe:
            def await()(using AllowUnsafe)   = Fiber.unit.unsafe
            def release()(using AllowUnsafe) = ()
            def pending()(using AllowUnsafe) = 0

        def init(n: Int)(using AllowUnsafe): Unsafe =
            if n <= 0 then noop
            else
                new Unsafe:
                    val promise = Promise.Unsafe.init[Nothing, Unit]()
                    val count   = AtomicInt.Unsafe.init(n)

                    def await()(using AllowUnsafe) = promise

                    def release()(using AllowUnsafe) =
                        @tailrec def loop(c: Int): Unit =
                            if c > 0 && !count.cas(c, c - 1) then
                                loop(count.get())
                            else if c == 1 then
                                promise.completeDiscard(Result.unit)
                        loop(count.get())
                    end release

                    def pending()(using AllowUnsafe) = count.get()
    end Unsafe
end Latch
