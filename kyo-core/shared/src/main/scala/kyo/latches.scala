package kyo

import java.util.concurrent.atomic.AtomicInteger

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
                    val await   = ()
                    val release = ()
                    val pending = 0

                    override def toString = "Latches(0)"
            else
                IOs {
                    new Latch:
                        val promise = Fibers.unsafeInitPromise[Unit]
                        val count   = new AtomicInteger(n)

                        val await = promise.get

                        val release =
                            IOs {
                                var c = count.get()
                                while c > 0 && !count.compareAndSet(c, c - 1) do
                                    c = count.get()
                                if c == 1 then
                                    promise.unsafeComplete(())
                                    ()
                            }

                        val pending = IOs(count.get())

                        override def toString = s"Latches($count)"
                }
        }
end Latches
