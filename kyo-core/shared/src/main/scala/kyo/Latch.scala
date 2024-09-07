package kyo

import java.util.concurrent.atomic.AtomicInteger
import kyo.scheduler.IOPromise
import scala.annotation.tailrec

abstract class Latch:

    def await(using Frame): Unit < Async

    def release(using Frame): Unit < IO

    def pending(using Frame): Int < IO
end Latch

object Latch:

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
                                    promise.completeUnit(Result.unit)
                            loop(count.get())
                        }

                    def pending(using Frame) = IO(count.get())

                    override def toString = s"Latch($count)"
            }
end Latch
