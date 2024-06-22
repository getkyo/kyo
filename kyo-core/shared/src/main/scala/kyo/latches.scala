package kyo

import java.util.concurrent.atomic.AtomicInteger
import kyo.internal.Trace
import scala.annotation.tailrec

abstract class Latch:

    def await(using Trace): Unit < Fibers

    def release(using Trace): Unit < IOs

    def pending(using Trace): Int < IOs
end Latch

object Latches:

    def init(n: Int): Latch < IOs =
        if n <= 0 then
            new Latch:
                def await(using Trace)   = ()
                def release(using Trace) = ()
                def pending(using Trace) = 0

                override def toString = "Latches(0)"
        else
            IOs {
                new Latch:
                    val promise = Fibers.unsafeInitPromise[Unit]
                    val count   = new AtomicInteger(n)

                    def await(using Trace) = promise.get

                    def release(using Trace) =
                        IOs {
                            @tailrec def loop(c: Int): Unit =
                                if c > 0 && !count.compareAndSet(c, c - 1) then
                                    loop(count.get)
                                else if c == 1 then
                                    discard(promise.unsafeComplete(Result.success(())))
                            loop(count.get())
                        }

                    def pending(using Trace) = IOs(count.get())

                    override def toString = s"Latches($count)"
            }
end Latches
