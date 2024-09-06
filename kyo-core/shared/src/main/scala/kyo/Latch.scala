package kyo

import java.util.concurrent.atomic.AtomicInteger
import kyo.scheduler.IOPromise
import scala.annotation.tailrec

/** A synchronization primitive that allows one or more tasks to wait until a set of operations being performed in other tasks completes.
  *
  * A `Latch` is initialized with a count and can be awaited. It is released by calling `release` the specified number of times.
  */
abstract class Latch:

    /** Waits until the latch has counted down to zero.
      *
      * @return
      *   Unit wrapped in an Async effect
      */
    def await(using Frame): Unit < Async

    /** Decrements the count of the latch, releasing it if the count reaches zero.
      *
      * @return
      *   Unit wrapped in an IO effect
      */
    def release(using Frame): Unit < IO

    /** Returns the current count of the latch.
      *
      * @return
      *   The current count wrapped in an IO effect
      */
    def pending(using Frame): Int < IO
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
    def init(n: Int)(using Frame): Latch < IO =
        if n <= 0 then
            new Latch:
                def await(using Frame)   = ()
                def release(using Frame) = ()
                def pending(using Frame) = 0

                override def toString = "Latch(0)"
        else
            IO {
                new Latch:
                    val promise = IOPromise[Nothing, Unit]()
                    val count   = new AtomicInteger(n)

                    def await(using Frame) = Async.get(promise)

                    def release(using Frame) =
                        IO {
                            @tailrec def loop(c: Int): Unit =
                                if c > 0 && !count.compareAndSet(c, c - 1) then
                                    loop(count.get)
                                else if c == 1 then
                                    promise.completeUnit(Result.success(()))
                            loop(count.get())
                        }

                    def pending(using Frame) = IO(count.get())

                    override def toString = s"Latch($count)"
            }
end Latch
