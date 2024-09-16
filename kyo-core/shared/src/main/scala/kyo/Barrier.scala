package kyo

import java.util.concurrent.atomic.AtomicInteger
import kyo.scheduler.IOPromise
import scala.annotation.tailrec

/** A synchronization primitive that allows a fixed number of parties to wait for each other to reach a common point of execution.
  *
  *   - The Barrier is initialized with a specific number of parties. Each party calls `await` when it reaches the barrier point.
  *   - The barrier releases all waiting parties when the last party arrives.
  *   - The barrier can only be used once. After all parties have been released, the barrier cannot be reset.
  */
abstract class Barrier:

    /** Waits for the barrier to be released.
      *
      * @return
      *   Unit
      */
    def await(using Frame): Unit < Async

    /** Returns the number of parties still waiting at the barrier.
      *
      * @return
      *   The number of waiting parties
      */
    def pending(using Frame): Int < IO
end Barrier

object Barrier:

    /** Creates a new Barrier instance.
      *
      * @param n
      *   The number of parties that must call await before the barrier is released
      * @return
      *   A new Barrier instance
      */
    def init(n: Int)(using Frame): Barrier < IO =
        if n <= 0 then
            new Barrier:
                def await(using Frame)   = ()
                def pending(using Frame) = 0

                override def toString = "Barrier(0)"
        else
            IO {
                new Barrier:
                    val promise = IOPromise[Nothing, Unit]()
                    val count   = new AtomicInteger(n)

                    def await(using Frame) =
                        IO {
                            @tailrec def loop(c: Int): Unit < Async =
                                if c > 0 && !count.compareAndSet(c, c - 1) then
                                    loop(count.get)
                                else if c == 1 then
                                    promise.completeUnit(Result.unit)
                                else
                                    Async.get(promise)
                            loop(count.get())
                        }

                    def pending(using Frame) = IO(count.get())

                    override def toString = s"Barrier($count)"
            }
end Barrier
