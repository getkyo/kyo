package kyo

import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec

/** A synchronization primitive that allows one or more tasks to wait until a set of operations being performed in other tasks completes.
  *
  * Latch provides a countdown mechanism where tasks can wait for a counter to reach zero before proceeding. The counter is decremented
  * through `release` operations, with waiting tasks unblocked once the count reaches zero.
  *
  * Latches are commonly used for coordinating startup sequences, signaling completion of distributed work, or implementing simple fork-join
  * patterns where one task must wait for multiple operations to complete.
  *
  * When initialized with count <= 0, the latch behaves as a no-op with all await operations completing immediately.
  *
  * @see
  *   [[kyo.Barrier]] A related primitive that synchronizes a fixed number of tasks at a specific execution point
  */
final case class Latch private (unsafe: Latch.Unsafe):

    /** Waits until the latch has counted down to zero.
      *
      * @return
      *   Unit wrapped in an Async effect
      */
    def await(using Frame): Unit < Async = Sync.Unsafe(unsafe.await().safe.get)

    /** Decrements the count of the latch, releasing it if the count reaches zero.
      *
      * @return
      *   Unit wrapped in an Sync effect
      */
    def release(using Frame): Unit < Sync = Sync.Unsafe(unsafe.release())

    /** Returns the current count of the latch.
      *
      * @return
      *   The current count wrapped in an Sync effect
      */
    def pending(using Frame): Int < Sync = Sync.Unsafe(unsafe.pending())

end Latch

/** Companion object for creating Latch instances. */
object Latch:

    /** Creates a new Latch initialized with the given count.
      *
      * @param count
      *   The initial count for the latch
      * @return
      *   A new Latch instance wrapped in an Sync effect
      */
    def init(count: Int)(using Frame): Latch < Sync =
        initWith(count)(identity)

    /** Uses a new Latch with the provided count.
      * @param count
      *   The initial count for the latch
      * @param f
      *   The function to apply to the new Latch
      * @return
      *   The result of applying the function
      */
    inline def initWith[A, S](count: Int)(inline f: Latch => A < S)(using inline frame: Frame): A < (S & Sync) =
        Sync.Unsafe(f(Latch(Unsafe.init(count))))

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
                            if c > 0 && !count.compareAndSet(c, c - 1) then
                                loop(count.get())
                            else if c == 1 then
                                promise.completeDiscard(Result.unit)
                        loop(count.get())
                    end release

                    def pending()(using AllowUnsafe) = count.get()
    end Unsafe
end Latch
