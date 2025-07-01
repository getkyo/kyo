package kyo.bench.arena

import java.util.concurrent.locks.LockSupport

class BlockingBench extends ArenaBench.ForkOnly(()):

    def block(): Unit =
        LockSupport.parkNanos(100000) // 0.1 ms

    override def kyoBenchFiber() =
        import kyo.*

        Sync(block())
    end kyoBenchFiber

    def catsBench() =
        import cats.effect.*

        IO.blocking(block())
    end catsBench

    def zioBench() =
        import zio.*

        ZIO.attemptBlocking(block()).orDie
    end zioBench
end BlockingBench
