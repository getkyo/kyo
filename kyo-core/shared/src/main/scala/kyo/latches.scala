package kyo

import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec

abstract class Latch:

    def await: Unit < Fibers

    def release: Unit < IOs

    def pending: Int < IOs
end Latch

object Latches:

    def init[S](n: Int < S): Latch < (IOs & S) =
        n.map { n =>
            if n <= 0 then
                new Latch:
                    def await   = ()
                    def release = ()
                    def pending = 0

                    override def toString = "Latches(0)"
            else
                IOs {
                    new Latch:
                        val promise = Fibers.unsafeInitPromise[Unit]
                        val count   = new AtomicInteger(n)

                        def await = promise.get

                        val release =
                            IOs {
                                @tailrec def loop(c: Int): Unit =
                                    if c > 0 && !count.compareAndSet(c, c - 1) then
                                        loop(count.get)
                                    else if c == 1 then
                                        discard(promise.unsafeComplete(()))
                                loop(count.get())
                            }

                        def pending = IOs(count.get())

                        override def toString = s"Latches($count)"
                }
        }
end Latches
