package kyo

import scala.annotation.tailrec

/** A synchronization primitive that allows a fixed number of parties to wait for each other to reach a common point of execution.
  *
  * Barrier provides a coordination mechanism where a predetermined number of fibers or threads (parties) must all arrive at a specific
  * synchronization point before any of them can proceed. This is useful for scenarios where concurrent operations must wait for each other
  * to complete before continuing execution.
  *
  * Key characteristics:
  *   - The Barrier is initialized with a specific number of parties
  *   - Each party calls `await` when it reaches the barrier point
  *   - All parties remain blocked until the last party arrives at the barrier
  *   - When the last party arrives, all waiting parties are released simultaneously
  *   - The barrier can only be used once - it cannot be reset after all parties have been released
  *   - If initialized with parties <= 0, the barrier behaves as a no-op (await returns immediately)
  *
  * @see
  *   [[Latch]] A related primitive that allows one or more tasks to wait until a set of operations completes
  */
final case class Barrier private (unsafe: Barrier.Unsafe):

    /** Waits for the barrier to be released.
      *
      * @return
      *   Unit
      */
    def await(using Frame): Unit < Async = Sync.Unsafe(unsafe.await().safe.get)

    /** Returns the number of parties still waiting at the barrier.
      *
      * @return
      *   The number of waiting parties
      */
    def pending(using Frame): Int < Sync = Sync.Unsafe(unsafe.pending())

end Barrier

object Barrier:

    /** Creates a new Barrier instance.
      *
      * @param parties
      *   The number of parties that must call await before the barrier is released
      * @return
      *   A new Barrier instance
      */
    def init(parties: Int)(using Frame): Barrier < Sync = initWith(parties)(identity)

    /** Uses a new Barrier with the provided number of parties.
      * @param parties
      *   The number of parties that must call await before the barrier is released
      * @param f
      *   The function to apply to the new Barrier
      * @return
      *   The result of applying the function
      */
    inline def initWith[A, S](parties: Int)(inline f: Barrier => A < S)(using inline frame: Frame): A < (S & Sync) =
        Sync.Unsafe(f(Barrier(Unsafe.init(parties))))

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    sealed abstract class Unsafe:
        def await()(using AllowUnsafe): Fiber.Unsafe[Nothing, Unit]
        def pending()(using AllowUnsafe): Int
        def safe: Barrier = Barrier(this)
    end Unsafe

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
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
                            if c > 0 && !count.compareAndSet(c, c - 1) then
                                loop(count.get())
                            else
                                if c == 1 then promise.completeDiscard(Result.unit)
                                promise
                        loop(count.get())
                    end await

                    def pending()(using AllowUnsafe) = count.get()
    end Unsafe
end Barrier
